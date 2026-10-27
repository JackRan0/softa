package io.softa.starter.tenant.entitlement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.constant.ModuleConstant;
import io.softa.starter.tenant.constant.PlanConstant;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.PlanEntitlement;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import org.junit.jupiter.api.DisplayName;
import io.softa.starter.tenant.entity.TenantSubscription;
import io.softa.starter.tenant.service.SubscriptionProjectionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolution logic for {@link EntitlementResolver}. Feeds a mocked {@link ModelService} (cache always
 * misses) and asserts the effective module set: plan = the subscription row's projected planId (null
 * there means no period covers today, so the floor plan applies), modules = plan_entitlement,
 * fail-closed to the floor set. The fallback is the lowest-{@code tier} plan — <b>no plan id is hardcoded</b>; see
 * {@link #fallbackIsLowestTierPlan_notByHardcodedId()} and {@link #noPlansConfigured_emptyEntitlement()}.
 */
class EntitlementResolverTest {

    private static final long TENANT = 1001L;
    private static final long SUB_ID = 9001L;

    private ModelService<?> modelService;
    private EntitlementResolver resolver;
    private List<TenantSubscription> subs;
    private List<TenantSubscriptionPeriod> periodRows;
    private List<Plan> plans;
    private Map<String, List<String>> planModules;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        CacheService cacheService = mock(CacheService.class);
        when(cacheService.get(anyString(), eq(EntitlementInfo.class))).thenReturn(null);  // always miss → compute
        // The projection refresh is exercised by SubscriptionProjectionServiceImplTest; here it is a
        // no-op so these cases isolate the plan → modules resolution.
        SubscriptionProjectionService projectionService = mock(SubscriptionProjectionService.class);
        when(projectionService.refresh(any(TenantInfo.class)))
                .thenAnswer(inv -> subs.isEmpty() ? null : subs.getFirst());
        resolver = new EntitlementResolver(modelService, cacheService, projectionService);

        subs = new ArrayList<>();
        periodRows = new ArrayList<>();
        // Default catalog: free(0) < pro(10) < enterprise(20). Lowest tier (free) is the fallback.
        plans = new ArrayList<>(List.of(
                plan(PlanConstant.PLAN_FREE, 0),
                plan(PlanConstant.PLAN_PRO, 10),
                plan(PlanConstant.PLAN_ENTERPRISE, 20)));
        planModules = new HashMap<>(Map.of(
                PlanConstant.PLAN_FREE, List.of(ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM),
                PlanConstant.PLAN_PRO, List.of(ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM,
                        ModuleConstant.ATTENDANCE, ModuleConstant.ADMIN),
                PlanConstant.PLAN_ENTERPRISE, List.of(ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM,
                        ModuleConstant.ATTENDANCE, ModuleConstant.ADMIN, ModuleConstant.AI)));

        when(modelService.searchList(eq("TenantInfo"), any(FlexQuery.class), eq(TenantInfo.class)))
                .thenAnswer(inv -> List.of(tenantInfo()));
        when(modelService.searchList(eq("TenantSubscription"), any(FlexQuery.class), eq(TenantSubscription.class)))
                .thenAnswer(inv -> subs);
        when(modelService.searchList(eq("PlanEntitlement"), any(FlexQuery.class), eq(PlanEntitlement.class)))
                .thenAnswer(inv -> planEntitlements(filterValue(inv.getArgument(1))));
        when(modelService.searchList(eq("Plan"), any(FlexQuery.class), eq(Plan.class)))
                .thenAnswer(inv -> plansMatching(inv.getArgument(1)));
        // Period rows exist only to answer "does this tenant own a baseline period at all". Default empty,
        // which is the pre-migration shape and the one the cases above rely on.
        when(modelService.searchList(eq("TenantSubscriptionPeriod"), any(FlexQuery.class),
                eq(TenantSubscriptionPeriod.class))).thenAnswer(inv -> periodRows);
    }

    // ─── no covering period: two situations, opposite answers ───

    @Test
    @DisplayName("a base period that an operator ended grants nothing — the fallback must not undo that")
    void basePeriodEnded_grantsNothing() {
        // Time-boxing the base period is the whole mechanism for cutting a tenant on its base period off (a competitor
        // evaluating the product, say). Falling back to the floor plan here would hand back exactly the access
        // the operator removed, and hand it back forever.
        // The subscription row exists — it always does — but its projection covers nothing: planId null means
        // no period covers today. The base row is still THERE, which is what separates this from the case below.
        subs.add(sub(null));
        periodRows.add(floorPeriod());

        assertThat(modules(TENANT)).isEmpty();
        assertThat(resolver.resolve(TENANT).planId()).isNull();
    }

    @Test
    @DisplayName("a tenant with no baseline period still gets the floor plan, so a missed migration is survivable")
    void noFloorPeriod_fallsBack() {
        // A subscription row written before provisioning began creating the base period. Granting nothing
        // would take access away from an existing customer on the deploy that introduced this; the resolver
        // logs a warning and serves the floor instead, and the remedy is to give it its base period.
        periodRows.clear();

        assertThat(modules(TENANT)).containsExactlyInAnyOrder(
                ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM);
    }

    private static TenantSubscriptionPeriod floorPeriod() {
        TenantSubscriptionPeriod period = new TenantSubscriptionPeriod();
        period.setPlanId(PlanConstant.PLAN_FREE);
        return period;
    }

    // ─── plan / lifecycle ───

    @Test
    void noSubscription_defaultsToFallback() {
        assertThat(modules(TENANT)).containsExactlyInAnyOrder(
                ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM);
    }

    @Test
    void proPlan_yieldsProModules() {
        subs.add(sub(PlanConstant.PLAN_PRO));
        assertThat(modules(TENANT)).containsExactlyInAnyOrder(
                ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM,
                ModuleConstant.ATTENDANCE, ModuleConstant.ADMIN);
    }

    @Test
    void enterprisePlan_yieldsEnterpriseModules() {
        subs.add(sub(PlanConstant.PLAN_ENTERPRISE));  // trial and paid grant the same modules
        assertThat(modules(TENANT)).contains(ModuleConstant.AI);
    }

    @Test
    void noPeriodCoversToday_degradesToFloor() {
        // Lapsed, scheduled-but-not-started and never-bought all project to a null plan — one branch
        // here, because all three grant the same thing. Telling them apart is a display concern.
        subs.add(noCurrentPeriod());
        assertThat(modules(TENANT)).containsExactlyInAnyOrder(
                ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM);
        assertThat(resolver.resolve(TENANT).planId()).isEqualTo(PlanConstant.PLAN_FREE);  // = lowest tier
    }

    // ─── fail-closed ───

    @Test
    void unconfiguredPlan_failsClosedToFallbackBase() {
        subs.add(sub(PlanConstant.PLAN_PRO));
        planModules.remove(PlanConstant.PLAN_PRO);   // pro's modules missing
        assertThat(modules(TENANT)).containsExactlyInAnyOrder(
                ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM);
    }

    @Test
    void nullTenant_defaultsToFallback() {
        assertThat(resolver.resolve(null).entitledModuleIds()).containsExactlyInAnyOrder(
                ModuleConstant.CORE_HR, ModuleConstant.USERS, ModuleConstant.SYSTEM);
    }

    // ─── fallback is tier-driven, never a hardcoded id ───

    @Test
    void fallbackIsLowestTierPlan_notByHardcodedId() {
        // A deployment whose plans are named nothing like "plan.free" — the lowest tier is the floor.
        plans = new ArrayList<>(List.of(plan("basic", 5), plan("premium", 50)));
        planModules = new HashMap<>(Map.of("basic", List.of(ModuleConstant.CORE_HR)));

        assertThat(resolver.resolve(TENANT).planId()).isEqualTo("basic");           // min tier
        assertThat(resolver.resolve(TENANT).entitledModuleIds()).containsExactly(ModuleConstant.CORE_HR);
    }

    @Test
    void noPlansConfigured_emptyEntitlement() {
        plans = new ArrayList<>();   // empty catalog → no floor → no access

        assertThat(resolver.resolve(TENANT).entitledModuleIds()).isEmpty();
        assertThat(resolver.resolve(TENANT).planId()).isNull();
    }

    // ─── helpers ───

    private Set<String> modules(Long tenantId) {
        return resolver.resolve(tenantId).entitledModuleIds();
    }

    /** Plan mock: an id-filter query = planById (return the match); an empty query = the fallback
     *  lookup (return the whole catalog so the resolver picks min tier). */
    private List<Plan> plansMatching(FlexQuery q) {
        FilterUnit unit = q.getFilters() == null ? null : q.getFilters().getFilterUnit();
        if (unit == null) {
            return plans;
        }
        String id = String.valueOf(unit.getValue());
        return plans.stream().filter(p -> id.equals(p.getId())).toList();
    }

    private List<PlanEntitlement> planEntitlements(String planId) {
        List<PlanEntitlement> out = new ArrayList<>();
        for (String m : planModules.getOrDefault(planId, List.of())) {
            PlanEntitlement e = new PlanEntitlement();
            e.setPlanId(planId);
            e.setModuleId(m);
            out.add(e);
        }
        return out;
    }

    private static String filterValue(FlexQuery q) {
        return String.valueOf(q.getFilters().getFilterUnit().getValue());
    }

    /** A subscription row whose projection says "this plan covers today". */
    private static TenantSubscription sub(String planId) {
        TenantSubscription s = new TenantSubscription();
        s.setId(SUB_ID);
        s.setPlanId(planId);
        return s;
    }

    /** A subscription row whose projection says "no period covers today" — the floor applies. */
    private static TenantSubscription noCurrentPeriod() {
        TenantSubscription s = new TenantSubscription();
        s.setId(SUB_ID);
        s.setPlanId(null);
        return s;
    }

    /** The tenant registry row — points at the version via subscriptionId (null when none). */
    private TenantInfo tenantInfo() {
        TenantInfo t = new TenantInfo();
        t.setId(TENANT);
        t.setSubscriptionId(subs.isEmpty() ? null : subs.get(0).getId());
        return t;
    }

    private static Plan plan(String id, int tier) {
        Plan p = new Plan();
        p.setId(id);
        p.setTier(tier);
        return p;
    }
}
