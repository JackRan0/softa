package io.softa.starter.tenant.entitlement;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import io.softa.starter.tenant.service.SubscriptionProjectionService;
import io.softa.starter.tenant.service.TenantSubscriptionPeriodService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether an expiring period gets a reminder, and which kind — the decision that depends on what follows it.
 *
 * <p>Three outcomes, and the distinction is not cosmetic. A seamless renewal must stay silent: telling a
 * customer who just renewed that their plan is about to lapse is wrong. A gap-separated renewal must speak,
 * because the coverage genuinely stops in between — and it must say so rather than ask for a renewal the
 * customer has already made, or its own "ignore this if you have renewed" line turns the one message they
 * need to read into the easiest one to dismiss.
 *
 * <p>None of this was covered before; the successor check shipped with no test at all.
 */
class SubscriptionReminderSuccessorTest {

    private static final long TENANT = 1001L;
    private static final long SUB_ID = 9001L;
    /** The fixture tenant sits in UTC, so the reference date must be UTC's today, not the machine's. */
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    /** Reminder points are {7, 1} days out, so a period ending in 7 days is due. */
    private static final LocalDate ENDS = TODAY.plusDays(7);

    private ModelService<?> modelService;
    private TenantSubscriptionPeriodService periodService;
    private ApplicationEventPublisher eventPublisher;
    private SubscriptionProjectionJob job;
    private List<TenantSubscriptionPeriod> periods;
    private long nextId = 1;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        periodService = mock(TenantSubscriptionPeriodService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        periods = new ArrayList<>();
        // Reminder hour 0 so the tenant-local hour gate is always open — this class is about the successor
        // rule, and the hour / dedup rules have their own seam (`dueReminderDays`).
        modelService = mock(ModelService.class);
        job = new SubscriptionProjectionJob(mock(SubscriptionProjectionService.class), periodService,
                modelService, eventPublisher, 0, Set.of(7, 1));

        when(periodService.searchList(any(FlexQuery.class))).thenAnswer(inv -> periods);
    }

    @Test
    @DisplayName("seamless renewal — the next period starts the day after, so no reminder at all")
    void seamlessRenewal_silent() {
        periods.add(period(ENDS.minusDays(300), ENDS));
        periods.add(period(ENDS.plusDays(1), ENDS.plusDays(365)));

        job.remindUpcoming(List.of(tenant()));

        verify(eventPublisher, never()).publishEvent(any(SubscriptionExpiryReminderEvent.class));
    }

    @Test
    @DisplayName("nothing follows — a plain expiry reminder, no gap date")
    void noSuccessor_plainReminder() {
        periods.add(period(ENDS.minusDays(300), ENDS));

        job.remindUpcoming(List.of(tenant()));

        SubscriptionExpiryReminderEvent event = captureEvent();
        assertThat(event.nextStartDate())
                .as("null is what tells the notifier to use the plain renew-me wording")
                .isNull();
        assertThat(event.daysLeft()).isEqualTo(7);
    }

    @Test
    @DisplayName("gap-separated renewal — reminded, and the event carries when coverage resumes")
    void gapSeparatedRenewal_carriesResumeDate() {
        // Renewed, but not for the two months in between: the workspace really does drop to the floor plan.
        periods.add(period(ENDS.minusDays(300), ENDS));
        periods.add(period(ENDS.plusDays(60), ENDS.plusDays(425)));

        job.remindUpcoming(List.of(tenant()));

        assertThat(captureEvent().nextStartDate())
                .as("without this date the message cannot name the uncovered stretch")
                .isEqualTo(ENDS.plusDays(60));
    }

    @Test
    @DisplayName("several later periods — the gap ends at the earliest of them")
    void gapEndsAtEarliestSuccessor() {
        periods.add(period(ENDS.minusDays(300), ENDS));
        periods.add(period(ENDS.plusDays(200), ENDS.plusDays(565)));
        periods.add(period(ENDS.plusDays(30), ENDS.plusDays(195)));

        job.remindUpcoming(List.of(tenant()));

        assertThat(captureEvent().nextStartDate()).isEqualTo(ENDS.plusDays(30));
    }

    @Test
    @DisplayName("Pro ends over an open-ended base period — a downgrade, not a lapse")
    void downgradeOntoFree_namesTheSuccessorPlan() {
        // The shape every tenant has after the reform: an open-ended floor period from tenant creation, with a
        // purchased period sold on top of it. Before this rule, the base period was never found — it starts
        // BEFORE the Pro period rather than after — so the customer was told their access lapses. It does not:
        // the base period still covers the day after and the projection moves them onto it.
        catalog(Map.of("plan.free", 0, "plan.pro", 10));
        periods.add(basePeriod(ENDS.minusDays(300)));
        periods.add(period(ENDS.minusDays(30), ENDS));

        job.remindUpcoming(List.of(tenant()));

        SubscriptionExpiryReminderEvent event = captureEvent();
        assertThat(event.successorPlanId())
                .as("the notifier needs the plan taking over to say 'you drop to this' instead of 'you lose access'")
                .isEqualTo("plan.free");
        assertThat(event.planId()).isEqualTo("plan.pro");
    }

    @Test
    @DisplayName("base period time-boxed while Pro runs — nothing is lost, so nothing is said")
    void freeEndingUnderARunningPaidPeriod_silent() {
        // An operator gives the base row an end date while a Pro period covers that date anyway. The tenant
        // notices nothing, so a reminder here is pure noise — and it is the case the old start-date rule got
        // wrong in the other direction, finding no successor and mailing an expiry warning.
        catalog(Map.of("plan.free", 0, "plan.pro", 10));
        TenantSubscriptionPeriod free = basePeriod(ENDS.minusDays(300));
        free.setEffectiveEndDate(ENDS);
        periods.add(free);
        periods.add(period(ENDS.minusDays(30), ENDS.plusDays(300)));

        job.remindUpcoming(List.of(tenant()));

        verify(eventPublisher, never()).publishEvent(any(SubscriptionExpiryReminderEvent.class));
    }

    /** Stub the plan catalog the tier rule reads. Without it every plan sorts equal and no tier is comparable. */
    private void catalog(Map<String, Integer> tiers) {
        List<Plan> plans = tiers.entrySet().stream().map(e -> plan(e.getKey(), e.getValue())).toList();
        when(modelService.searchList(eq("Plan"), any(FlexQuery.class), eq(Plan.class))).thenReturn(plans);
    }

    private static Plan plan(String id, int tier) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setTier(tier);
        return plan;
    }

    private TenantSubscriptionPeriod basePeriod(LocalDate start) {
        TenantSubscriptionPeriod p = period(start, null);
        p.setPlanId("plan.free");
        p.setPeriodType(SubscriptionPeriodType.TRIAL);
        return p;
    }

    private SubscriptionExpiryReminderEvent captureEvent() {
        ArgumentCaptor<SubscriptionExpiryReminderEvent> captor =
                ArgumentCaptor.forClass(SubscriptionExpiryReminderEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private TenantInfo tenant() {
        TenantInfo t = new TenantInfo();
        t.setId(TENANT);
        t.setName("Acme Corp");
        t.setSubscriptionId(SUB_ID);
        t.setDefaultTimezone(Timezone.UTC_P_00_00);
        return t;
    }

    private TenantSubscriptionPeriod period(LocalDate start, LocalDate end) {
        TenantSubscriptionPeriod p = new TenantSubscriptionPeriod();
        p.setId(nextId++);
        p.setSubscriptionId(SUB_ID);
        p.setPlanId("plan.pro");
        p.setPeriodType(SubscriptionPeriodType.PAID);
        p.setEffectiveStartDate(start);
        p.setEffectiveEndDate(end);
        return p;
    }
}
