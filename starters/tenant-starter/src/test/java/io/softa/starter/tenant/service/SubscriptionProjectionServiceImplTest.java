package io.softa.starter.tenant.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entitlement.TenantEntitlementChangedEvent;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantSubscription;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.enums.SubscriptionStatus;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import io.softa.starter.tenant.service.impl.SubscriptionProjectionServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The projection arithmetic: which period covers today, which status that implies, and when the
 * entitlement-changed event fires.
 *
 * <p>The five statuses are not cosmetic variants — {@code EXPIRED} and {@code NEVER_SUBSCRIBED} grant the
 * same thing but mean opposite things to ops (a lapsed customer versus a lead), so
 * {@link #noPeriods_neverSubscribed()} and {@link #onlyPastPeriods_expired()} pin them apart.
 */
class SubscriptionProjectionServiceImplTest {

    private static final long TENANT = 1001L;
    private static final long SUB_ID = 9001L;
    /**
     * The fixture tenant sits in {@code UTC+00:00}, and the service computes every date in the tenant's own
     * zone — so the reference date has to be UTC's today, not the machine's. Using {@code LocalDate.now()}
     * here made these cases pass or fail depending on which side of UTC midnight the suite happened to run
     * on.
     */
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private ModelService<?> modelService;
    private TenantSubscriptionService subscriptionService;
    private ApplicationEventPublisher eventPublisher;
    private SubscriptionProjectionServiceImpl service;
    private List<TenantSubscriptionPeriod> periods;
    private TenantSubscription stored;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        subscriptionService = mock(TenantSubscriptionService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new SubscriptionProjectionServiceImpl(modelService, subscriptionService, eventPublisher);

        periods = new ArrayList<>();
        stored = new TenantSubscription();
        stored.setId(SUB_ID);

        when(subscriptionService.getById(SUB_ID)).thenAnswer(inv -> Optional.of(stored));
        when(subscriptionService.updateProjection(any(TenantSubscription.class))).thenReturn(true);
        when(modelService.searchList(anyString(), any(FlexQuery.class), eq(TenantSubscriptionPeriod.class)))
                .thenAnswer(inv -> periods);
        // The plan catalog the tier-first selection reads. Tiers are what the ordering is defined on, so the
        // stub has to carry them: free lowest, enterprise highest.
        when(modelService.searchList(eq("Plan"), any(FlexQuery.class), eq(Plan.class)))
                .thenReturn(List.of(plan("plan.free", 0), plan("plan.pro", 10), plan("plan.enterprise", 20)));
    }

    // ─── the four statuses ───

    @Test
    @DisplayName("no periods at all falls back to EXPIRED — a shape provisioning no longer produces")
    void noPeriods_expired() {
        // Provisioning writes a base period at creation, so a live tenant always has at least one row and
        // this input does not occur. Pinned anyway because the projection must still answer for a row that
        // predates that rule, or one whose periods were deleted directly in the database: EXPIRED grants the
        // same nothing that the old NEVER_SUBSCRIBED did, without needing a state for "never bought".
        assertThat(refresh().getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(refresh().getPlanId()).isNull();
    }

    @Test
    void onlyPastPeriods_expired() {
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID,
                TODAY.minusDays(400), TODAY.minusDays(35)));
        TenantSubscription out = refresh();
        assertThat(out.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(out.getPlanId()).isNull();
    }

    @Test
    void onlyFuturePeriod_scheduled_andStillOnFloor() {
        periods.add(period("plan.enterprise", SubscriptionPeriodType.PAID,
                TODAY.plusDays(30), TODAY.plusDays(395)));
        TenantSubscription out = refresh();
        assertThat(out.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PENDING);
        // Scheduling is not early activation: the plan must stay null or the tenant would get it now.
        assertThat(out.getPlanId()).isNull();
        assertThat(out.getNextStartDate()).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    void paidPeriodCoveringToday_paid() {
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(5), TODAY.plusDays(5)));
        TenantSubscription out = refresh();
        assertThat(out.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAID);
        assertThat(out.getPlanId()).isEqualTo("plan.pro");
        assertThat(out.getCurrentEndDate()).isEqualTo(TODAY.plusDays(5));
    }

    @Test
    void trialPeriodCoveringToday_trial() {
        periods.add(period("plan.enterprise", SubscriptionPeriodType.TRIAL,
                TODAY.minusDays(1), TODAY.plusDays(29)));
        TenantSubscription out = refresh();
        assertThat(out.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.TRIAL);
        // Trial is not a reduced tier — it projects the same plan a paid period would.
        assertThat(out.getPlanId()).isEqualTo("plan.enterprise");
    }

    @Test
    void openEndedPeriod_coversToday() {
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(10), null));
        assertThat(refresh().getPlanId()).isEqualTo("plan.pro");
        assertThat(refresh().getCurrentEndDate()).isNull();
    }

    @Test
    void currentAndFuturePeriod_bothProjected() {
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(5), TODAY.plusDays(20)));
        periods.add(period("plan.enterprise", SubscriptionPeriodType.PAID,
                TODAY.plusDays(21), TODAY.plusDays(400)));
        TenantSubscription out = refresh();
        // PAID at the tenant level while a later period sits scheduled — the two coexist, which is the
        // pairing most likely to be misread as contradictory.
        assertThat(out.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAID);
        assertThat(out.getPlanId()).isEqualTo("plan.pro");
        assertThat(out.getNextStartDate()).isEqualTo(TODAY.plusDays(21));
    }

    // ─── gaps and overlaps ───

    @Test
    void gapBetweenPeriods_scheduledAndOnFloor() {
        // Gaps are legitimate — the tenant is simply on the floor plan in between. Having lapsed earlier
        // does not change the answer to "what applies today", so this reads as PENDING, and the past
        // period shows as ended in the detail list.
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID,
                TODAY.minusDays(200), TODAY.minusDays(60)));
        periods.add(period("plan.enterprise", SubscriptionPeriodType.PAID,
                TODAY.plusDays(60), TODAY.plusDays(425)));
        TenantSubscription out = refresh();
        assertThat(out.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(out.getPlanId()).isNull();
        assertThat(out.getNextStartDate()).isEqualTo(TODAY.plusDays(60));
    }

    @Test
    void adjacentPeriods_noGap_secondOneApplies() {
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID,
                TODAY.minusDays(30), TODAY.minusDays(1)));
        periods.add(period("plan.enterprise", SubscriptionPeriodType.PAID, TODAY, TODAY.plusDays(364)));
        assertThat(refresh().getPlanId()).isEqualTo("plan.enterprise");
    }

    @Test
    void overlappingPeriods_latestStartWins_deterministically() {
        // Overlaps are rejected on write, so this is corrupt data (direct DB write / import / legacy). The
        // point of the assertion is that the outcome is *deterministic*: authorization reads this
        // projection, so picking arbitrarily would make the granted plan flip between refreshes.
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID,
                TODAY.minusDays(10), TODAY.plusDays(10)));
        periods.add(period("plan.enterprise", SubscriptionPeriodType.PAID,
                TODAY.minusDays(2), TODAY.plusDays(30)));
        assertThat(refresh().getPlanId()).isEqualTo("plan.enterprise");   // latest start

        // Same rows, reversed insertion order → same answer.
        periods.clear();
        stored.setProjectedForDate(null);
        periods.add(period("plan.enterprise", SubscriptionPeriodType.PAID,
                TODAY.minusDays(2), TODAY.plusDays(30)));
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID,
                TODAY.minusDays(10), TODAY.plusDays(10)));
        assertThat(refresh().getPlanId()).isEqualTo("plan.enterprise");
    }

    // ─── staleness gate ───

    @Test
    void alreadyProjectedForToday_isNoOp() {
        stored.setProjectedForDate(TODAY);
        service.refresh(tenant());
        verify(subscriptionService, never()).updateProjection(any(TenantSubscription.class));
    }

    @Test
    void refreshNow_ignoresTheGate() {
        stored.setProjectedForDate(TODAY);
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY, null));
        service.refreshNow(tenant());
        // The write path needs this: the projection is current for today, yet the periods just moved.
        verify(subscriptionService).updateProjection(any(TenantSubscription.class));
    }

    @Test
    void projectedForFutureDate_stillRecomputes() {
        // A tenant moved westward has its local today move backwards, so the stored date can sit in the
        // future. A "projected before today" test would freeze the projection here forever.
        stored.setProjectedForDate(TODAY.plusDays(1));
        service.refresh(tenant());
        verify(subscriptionService).updateProjection(any(TenantSubscription.class));
    }

    @Test
    void lapsedSubscription_clearsThePlanRatherThanLeavingItBehind() {
        // A tenant that used to be on Pro and whose only period has ended. The projection must actively
        // null the plan out, and the write must be a full overwrite — `updateOne(entity)` without the
        // explicit `false` funnels through `BeanTool.objectToMap(entity, true)`, which drops null fields,
        // so the row would keep `plan.pro` while reading EXPIRED. The resolver reads `planId`, so that
        // tenant would go on receiving the plan it stopped paying for.
        stored.setPlanId("plan.pro");
        stored.setSubscriptionStatus(SubscriptionStatus.PAID);
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(30), TODAY.minusDays(1)));

        service.refresh(tenant());

        ArgumentCaptor<TenantSubscription> captor = ArgumentCaptor.forClass(TenantSubscription.class);
        verify(subscriptionService).updateProjection(captor.capture());
        TenantSubscription written = captor.getValue();
        assertThat(written.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(written.getPlanId())
                .as("an expired tenant must fall back to the floor plan, not keep its own")
                .isNull();
        assertThat(written.getPeriodType()).isNull();
        assertThat(written.getCurrentPeriodId()).isNull();
        assertThat(written.getCurrentStartDate()).isNull();
        assertThat(written.getCurrentEndDate()).isNull();
        assertThat(written.getNextStartDate()).isNull();
    }

    // ─── event only on a real change ───

    @Test
    void planUnchanged_publishesNoEvent() {
        stored.setPlanId("plan.pro");
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(5), TODAY.plusDays(5)));
        service.refresh(tenant());
        // Refreshing every tenant daily must not turn the role-cleanup chain into a daily full sweep.
        verify(eventPublisher, never()).publishEvent(any(TenantEntitlementChangedEvent.class));
    }

    @Test
    void planChanged_publishesEvent() {
        stored.setPlanId("plan.enterprise");
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(5), TODAY.plusDays(5)));
        service.refresh(tenant());
        verify(eventPublisher).publishEvent(any(TenantEntitlementChangedEvent.class));
    }

    @Test
    void lapsingToFloor_publishesEvent() {
        // The downgrade that matters most: yesterday's plan is gone, so over-entitled role grants must be
        // cleaned up. No write happens on the period side here, which is why the projection must emit it.
        stored.setPlanId("plan.pro");
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID,
                TODAY.minusDays(400), TODAY.minusDays(1)));
        service.refresh(tenant());
        verify(eventPublisher).publishEvent(any(TenantEntitlementChangedEvent.class));
    }

    // ─── helpers ───

    private TenantSubscription refresh() {
        return service.refresh(tenant());
    }

    private TenantInfo tenant() {
        TenantInfo t = new TenantInfo();
        t.setId(TENANT);
        t.setSubscriptionId(SUB_ID);
        t.setDefaultTimezone(Timezone.UTC_P_00_00);
        return t;
    }

    // ─── overlapping periods: the tier decides, which is what makes the base period harmless ───

    @Test
    @DisplayName("a paid period wins over the base period that overlaps it")
    void paidBeatsOverlappingFree() {
        // Every tenant owns an open-ended base period, so ANY sold period overlaps it. Without tier-first
        // ordering this is the common case that breaks: the tenant would be reported on whichever row started
        // most recently, and a back-dated base row would silently demote a paying customer.
        // The base row deliberately starts LATER than the paid one, which is what the old "latest start wins"
        // rule got wrong: a base period recorded (or back-dated) after the sale would take over.
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(200), TODAY.plusDays(20)));
        periods.add(period("plan.free", SubscriptionPeriodType.TRIAL, TODAY.minusDays(10), null));

        TenantSubscription out = refresh();

        assertThat(out.getPlanId()).isEqualTo("plan.pro");
        assertThat(out.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAID);
    }

    @Test
    @DisplayName("the higher tier wins even when the lower one started more recently")
    void higherTierBeatsLaterStart() {
        // Directly contradicts the former rule ("latest start wins"). Recording a Pro period today must not
        // take an Enterprise customer down a tier.
        periods.add(period("plan.enterprise", SubscriptionPeriodType.PAID, TODAY.minusDays(90), TODAY.plusDays(90)));
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY, TODAY.plusDays(30)));

        assertThat(refresh().getPlanId()).isEqualTo("plan.enterprise");
    }

    @Test
    @DisplayName("a trial on a higher tier outranks a paid period on a lower one")
    void tierOutranksPeriodType() {
        // Tier is the only level-two criterion; period type is not a tie-break. An Enterprise trial genuinely
        // grants more modules than a Pro subscription, and entitlement follows the plan, not the payment.
        // Enterprise starts earlier, so "latest start" would pick Pro — the tier has to be what decides.
        periods.add(period("plan.enterprise", SubscriptionPeriodType.TRIAL, TODAY.minusDays(60), TODAY.plusDays(14)));
        periods.add(period("plan.pro", SubscriptionPeriodType.PAID, TODAY.minusDays(5), TODAY.plusDays(60)));

        TenantSubscription out = refresh();

        assertThat(out.getPlanId()).isEqualTo("plan.enterprise");
        assertThat(out.getSubscriptionStatus())
                .as("the winning row's own type decides the status")
                .isEqualTo(SubscriptionStatus.TRIAL);
    }

    @Test
    @DisplayName("a period whose plan is missing from the catalog loses rather than throwing")
    void danglingPlanSortsLowest() {
        // Bad data, but letting it win would hand the tenant a plan nothing can resolve. Losing means the
        // tenant keeps whatever else covers today — which for every tenant includes its base period.
        periods.add(period("plan.free", SubscriptionPeriodType.TRIAL, TODAY.minusDays(30), null));
        periods.add(period("plan.deleted", SubscriptionPeriodType.PAID, TODAY.minusDays(1), TODAY.plusDays(30)));

        assertThat(refresh().getPlanId()).isEqualTo("plan.free");
    }

    private static Plan plan(String id, int tier) {
        Plan p = new Plan();
        p.setId(id);
        p.setTier(tier);
        return p;
    }

    private TenantSubscriptionPeriod period(String planId, SubscriptionPeriodType type,
                                            LocalDate start, LocalDate end) {
        TenantSubscriptionPeriod p = new TenantSubscriptionPeriod();
        p.setId((long) (periods.size() + 1));
        p.setSubscriptionId(SUB_ID);
        p.setPlanId(planId);
        p.setPeriodType(type);
        p.setEffectiveStartDate(start);
        p.setEffectiveEndDate(end);
        return p;
    }
}
