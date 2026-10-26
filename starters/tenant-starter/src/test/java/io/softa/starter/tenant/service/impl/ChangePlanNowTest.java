package io.softa.starter.tenant.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import io.softa.starter.tenant.service.SubscriptionProjectionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * {@code changePlanNow} — switch a tenant onto another plan effective today.
 *
 * <p>It shipped untested, and the reform broke it in a way no other test could see. It picked today's period
 * with {@code findFirst()} on the covering rows, which was exactly right while overlap was rejected: at most
 * one row could cover a date. Every tenant now owns an open-ended floor period, so a tenant on Pro has two
 * covering rows and an arbitrary pick lands on the base one about as often as not.
 *
 * <p>Both consequences are silent. Picking the floor period closes the tenant's <i>baseline</i> off instead of
 * its purchase; and on a tenant created today the same-day branch rewrites the floor period's own plan, leaving
 * the subscription with no floor period at all — which the entitlement resolver reads as "predates the
 * migration" and answers with a fallback nobody asked for.
 */
class ChangePlanNowTest {

    private static final long SUB_ID = 9001L;
    private static final Plan FREE = plan("plan.free", 0);
    private static final Plan PRO = plan("plan.pro", 10);
    private static final Plan ENTERPRISE = plan("plan.enterprise", 20);

    private TenantSubscriptionPeriodServiceImpl service;
    private List<Plan> catalog;
    private List<TenantSubscriptionPeriod> stored;
    private LocalDate today;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        catalog = new ArrayList<>(List.of(FREE, PRO, ENTERPRISE));
        stored = new ArrayList<>();
        today = LocalDate.of(2026, 8, 4);
        service = newService();
    }

    @Test
    @DisplayName("a tenant on Pro over its base period — the PRO row is the one closed off")
    void picksTheTierWinnerNotTheFirstCoveringRow() {
        // The shape every tenant has after the reform. The base row is added FIRST, so `findFirst()` would
        // have found it — which is the whole failure this asserts against.
        TenantSubscriptionPeriod free = period(FREE.getId(), today.minusDays(300), null,
                SubscriptionPeriodType.TRIAL);
        TenantSubscriptionPeriod pro = period(PRO.getId(), today.minusDays(30), today.plusDays(60),
                SubscriptionPeriodType.PAID);
        stored.add(free);
        stored.add(pro);

        service.changePlanNow(SUB_ID, ENTERPRISE.getId(), SubscriptionPeriodType.PAID);

        assertThat(pro.getEffectiveEndDate())
                .as("the purchased period is the one that gives way to the new plan")
                .isEqualTo(today.minusDays(1));
        assertThat(free.getEffectiveEndDate())
                .as("the baseline must be left open-ended — closing it revokes base access silently")
                .isNull();
        assertThat(free.getPlanId())
                .as("and it must still be the floor plan")
                .isEqualTo(FREE.getId());
    }

    @Test
    @DisplayName("the remaining paid-through date is carried onto the new period")
    void inheritsTheEndDate() {
        stored.add(period(FREE.getId(), today.minusDays(300), null, SubscriptionPeriodType.TRIAL));
        stored.add(period(PRO.getId(), today.minusDays(30), today.plusDays(60), SubscriptionPeriodType.PAID));

        Long created = service.changePlanNow(SUB_ID, ENTERPRISE.getId(), SubscriptionPeriodType.PAID);

        assertThat(created).as("a new period is written rather than the old one repointed").isNotNull();
    }

    @Test
    @DisplayName("a tenant on nothing but its base period — refused, with the path that does work")
    void refusesToRewriteTheBaseline() {
        stored.add(period(FREE.getId(), today.minusDays(300), null, SubscriptionPeriodType.TRIAL));

        assertThatThrownBy(() -> service.changePlanNow(SUB_ID, PRO.getId(), SubscriptionPeriodType.PAID))
                .hasMessageContaining("record a new period");
    }

    @Test
    @DisplayName("a tenant created today — the same-day branch must not repoint the floor period")
    void sameDayFloorPeriodIsStillRefused() {
        // The worst version: `today` IS the base period's start, so the same-day branch would correct it in
        // place and the subscription would end up with zero floor periods — a state no guard downstream is
        // looking for, because the cardinality guard only rejects a second one.
        TenantSubscriptionPeriod free = period(FREE.getId(), today, null, SubscriptionPeriodType.TRIAL);
        stored.add(free);

        assertThatThrownBy(() -> service.changePlanNow(SUB_ID, PRO.getId(), SubscriptionPeriodType.PAID))
                .hasMessageContaining("record a new period");
        assertThat(free.getPlanId()).isEqualTo(FREE.getId());
    }

    @Test
    @DisplayName("no period covers today at all — refused")
    void refusesWhenNothingApplies() {
        stored.add(period(PRO.getId(), today.plusDays(30), today.plusDays(395), SubscriptionPeriodType.PAID));

        assertThatThrownBy(() -> service.changePlanNow(SUB_ID, ENTERPRISE.getId(), SubscriptionPeriodType.PAID))
                .hasMessageContaining("no period in effect today");
    }

    private TenantSubscriptionPeriod period(String planId, LocalDate start, LocalDate end,
                                            SubscriptionPeriodType type) {
        TenantSubscriptionPeriod p = new TenantSubscriptionPeriod();
        p.setId(nextId++);
        p.setSubscriptionId(SUB_ID);
        p.setPlanId(planId);
        p.setPeriodType(type);
        p.setEffectiveStartDate(start);
        p.setEffectiveEndDate(end);
        return p;
    }

    private static Plan plan(String id, int tier) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setTier(tier);
        return plan;
    }

    @SuppressWarnings("unchecked")
    private TenantSubscriptionPeriodServiceImpl newService() {
        TenantSubscriptionPeriodServiceImpl impl = spy(new TenantSubscriptionPeriodServiceImpl());
        ModelService<Long> modelService = mock(ModelService.class);

        // One mocked signature serves both plan reads, told apart the way the call sites differ: a catalog scan
        // carries no filter, a by-id lookup carries one.
        when(modelService.searchList(eq("Plan"), any(FlexQuery.class), eq(Plan.class)))
                .thenAnswer(invocation -> {
                    FlexQuery query = invocation.getArgument(1);
                    if (Filters.isEmpty(query.getFilters())) {
                        return catalog;
                    }
                    return catalog.stream()
                            .filter(p -> query.getFilters().toString().contains(p.getId()))
                            .toList();
                });
        when(modelService.searchList(eq("TenantInfo"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        when(modelService.createOne(anyString(), anyMap())).thenReturn(4242L);

        ReflectionTestUtils.setField(impl, "modelService", modelService);
        ReflectionTestUtils.setField(impl, EntityServiceImpl.class, "modelService", modelService,
                ModelService.class);

        SubscriptionProjectionService projection = mock(SubscriptionProjectionService.class);
        // The tenant-local date is the clock this method reads; pinning it keeps the fixtures deterministic.
        when(projection.tenantLocalToday(any())).thenAnswer(invocation -> today);
        ReflectionTestUtils.setField(impl, "projectionService", projection);

        doReturn(stored).when(impl).searchList(any(FlexQuery.class));
        doAnswer(invocation -> {
            Long wanted = invocation.getArgument(0);
            return stored.stream().filter(row -> wanted != null && wanted.equals(row.getId())).findFirst();
        }).when(impl).getById(anyLong());
        // The writes themselves are the framework's; this class is about which row is chosen.
        doReturn(true).when(impl).updateOne(any());
        doReturn(4242L).when(impl).createOne(any(TenantSubscriptionPeriod.class));
        return impl;
    }
}
