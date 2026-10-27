package io.softa.starter.tenant.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import io.softa.starter.tenant.service.SubscriptionProjectionService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * The guards on the period table — the only thing standing between ops and a subscription history that
 * cannot be interpreted.
 *
 * <p>No database constraint can express any of them. Overlap in particular has no unique index that could
 * catch it (two rows over the same dates are two perfectly legal rows), and the projection downstream has to
 * pick <i>one</i> period as today's: given an overlap it applies a deterministic tie-break and serves
 * whichever plan wins, silently. That tie-break exists as a last resort for data that should never have been
 * written, so this guard is what keeps it unreachable.
 *
 * <p>The accept cases matter as much as the reject cases: adjacency is the ordinary renewal and a gap is a
 * legitimate sale, so a guard that over-rejects breaks the two things ops does most.
 */
class SubscriptionPeriodValidationTest {

    private static final long SUB_ID = 9001L;
    private static final LocalDate START = LocalDate.of(2026, 8, 1);

    private static final Plan FREE = plan("plan.free", 0);
    private static final Plan PRO = plan("plan.pro", 10);

    private TenantSubscriptionPeriodServiceImpl service;
    /** What a catalog-wide scan sees — {@code floorPlan()} picks the lowest tier out of this. */
    private List<Plan> catalog;
    /** What a by-id lookup resolves to — {@code planById()}, for the trial tier check. */
    private Plan lookedUpPlan;
    /** The subscription's already-stored periods, scanned for overlap. */
    private List<TenantSubscriptionPeriod> stored;

    @BeforeEach
    void setUp() {
        catalog = new ArrayList<>(List.of(FREE, PRO));
        lookedUpPlan = PRO;
        stored = new ArrayList<>();
        service = newService();
    }

    // ─── required fields ───

    @Test
    @DisplayName("a period with no subscription belongs to nobody")
    void subscriptionRequired() {
        assertThatThrownBy(() -> service.createOne(period(null, START, START.plusMonths(12), "plan.pro")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must belong to a subscription");
    }

    @Test
    @DisplayName("a period with no start date cannot be placed on the timeline")
    void startDateRequired() {
        assertThatThrownBy(() -> service.createOne(period(SUB_ID, null, START.plusMonths(12), "plan.pro")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start date is required");
    }

    @Test
    @DisplayName("a blank plan is rejected rather than stored as a period selling nothing")
    void planRequired() {
        // Reached, not skipped: `applyPatch` deliberately forwards blank-plan rows so this message is what
        // the user sees. A row dropped earlier looked like a successful save with the period missing.
        assertThatThrownBy(() -> service.createOne(period(SUB_ID, START, START.plusMonths(12), "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plan is required");
    }

    @Test
    @DisplayName("a period with no type cannot be told apart from a trial")
    void periodTypeRequired() {
        TenantSubscriptionPeriod period = period(SUB_ID, START, START.plusMonths(12), "plan.pro");
        period.setPeriodType(null);

        assertThatThrownBy(() -> service.createOne(period))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Period type");
    }

    // ─── the date bounds ───

    @Test
    @DisplayName("an end date before the start date is rejected")
    void endBeforeStart_rejected() {
        // The form validates this too, but the form is not the boundary — this endpoint is. A backwards
        // period is worse than an empty one: it covers no day at all, so the projection reports the tenant as
        // never subscribed while ops is looking at a row that says otherwise.
        assertThatThrownBy(() -> service.createOne(period(SUB_ID, START, START.minusDays(1), "plan.pro")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot precede its start date");
    }

    @Test
    @DisplayName("a single-day period — start equals end — is allowed")
    void sameDayStartAndEnd_allowed() {
        // Both bounds are inclusive, so this is one covered day, not zero. Rejecting it would make a one-day
        // trial unexpressible.
        assertThatCode(() -> service.createOne(period(SUB_ID, START, START, "plan.pro")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a sold period must be time-boxed — an open end date is rejected")
    void openEndedSoldPeriod_rejected() {
        // Expiry sweeps, auto-downgrade and billing all key off the end date; an open-ended paid
        // period would keep the tenant on the paid tier forever. Only the floor period is
        // legitimately open-ended.
        assertThatThrownBy(() -> service.createOne(period(SUB_ID, START, null, "plan.pro")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have an end date");
    }

    // ─── the floor plan is recorded, exactly once ───
    //
    // Both guards that used to live here are gone, and both rested on "a floor period and no period express
    // the same state". Provisioning now writes an open-ended floor TRIAL period at tenant creation, so that
    // premise is false and either guard would reject the row the tenant cannot exist without. What survives
    // is the thing they were really protecting: one baseline, unambiguous.

    @Test
    @DisplayName("the floor plan can be recorded — it is what provisioning writes")
    void floorPlanPeriod_accepted() {
        assertThatCode(() -> service.createOne(period(SUB_ID, START, START.plusMonths(12), FREE.getId())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a second floor period is rejected — there is exactly one, created with the tenant")
    void secondFloorPeriod_rejected() {
        // The cardinality rule that replaced the prohibition. Two baselines would put the tenant's floor
        // entitlement in two places, and an operator editing one would leave the other saying otherwise.
        stored.add(storedPeriod(1L, START, null, FREE.getId()));

        assertThatThrownBy(() -> service.createOne(period(SUB_ID, START.plusYears(1), null, FREE.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has its");
    }

    @Test
    @DisplayName("the cardinality scan excludes the row being edited, so the one floor period stays editable")
    void editingOwnFloorPeriod_allowed() {
        // Setting an end date on the base period is the entire mechanism for time-boxing a tenant on its base period, so
        // excluding the row under edit from the scan is load-bearing, not defensive. Exercised through
        // validate() directly: the update entry point loads the row first and this fixture has no store
        // behind getById, so going through updateOne would fail on plumbing before reaching the guard.
        stored.add(storedPeriod(7L, START, null, FREE.getId()));

        TenantSubscriptionPeriod edit = period(SUB_ID, START, START.plusMonths(3), FREE.getId());
        edit.setId(7L);

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "validate", edit, 7L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with no plan catalog seeded, the floor guard stands down rather than rejecting everything")
    void noCatalog_floorGuardSkipped() {
        // `floorPlan()` returns null before the catalog is seeded. Treating that as "everything is the floor"
        // would make a fresh install unable to record any period at all.
        catalog = List.of();

        assertThatCode(() -> service.createOne(period(SUB_ID, START, START.plusMonths(12), "plan.pro")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the base period's start date cannot be moved — only its end date is the operator's")
    void floorPeriodStartDate_immutable() {
        // The start date anchors "this tenant has had base access since it existed". Moving it forward opens a
        // stretch nothing covers, and with the floor-plan fallback gone that means zero modules; moving it back
        // claims access before the tenant existed.
        stored.add(storedPeriod(9L, START, null, FREE.getId()));

        TenantSubscriptionPeriod moved = period(SUB_ID, START.plusDays(10), null, FREE.getId());
        moved.setId(9L);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validate", moved, 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("setting the base period's end date is allowed — that is how base access is time-boxed")
    void floorPeriodEndDate_settable() {
        stored.add(storedPeriod(9L, START, null, FREE.getId()));

        TenantSubscriptionPeriod boxed = period(SUB_ID, START, START.plusMonths(3), FREE.getId());
        boxed.setId(9L);

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "validate", boxed, 9L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the base period cannot be deleted — the message points at the end date instead")
    void floorPeriodDeletion_refused() {
        // Deleting it does the opposite of what the operator intends: the resolver reads "no floor period" as
        // "the row is missing" and falls back to granting the floor plan, so a tenant on its base period deleted out of its
        // baseline quietly keeps base access. Completes the invariant — without this it is "at most one floor
        // period", not "exactly one".
        stored.add(storedPeriod(5L, START, null, FREE.getId()));

        assertThatThrownBy(() -> service.deleteById(5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("give that period an end date instead");
    }

    @Test
    @DisplayName("a sold period is still deletable")
    void soldPeriodDeletion_allowed() {
        // The guard has to be about the floor plan specifically, not about deletion — a mis-entered Pro period
        // is exactly what delete is for.
        stored.add(storedPeriod(6L, START, START.plusMonths(6), "plan.pro"));

        assertThatCode(() -> service.deleteById(6L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a trial on a floor-level plan is accepted — the base period is exactly that")
    void trialOnFloorLevelPlan_accepted() {
        // Formerly rejected on the grounds that trialling something no better than the floor grants nothing.
        // The base period is a floor-plan TRIAL by design: TRIAL because nobody paid, which is what keeps it
        // out of revenue reads, and floor because that is the baseline every tenant gets.
        Plan legacy = plan("plan.legacy", null);   // no tier — treated as floor level
        catalog = List.of(FREE, PRO, legacy);
        lookedUpPlan = legacy;

        TenantSubscriptionPeriod period = period(SUB_ID, START, START.plusMonths(1), legacy.getId());
        period.setPeriodType(SubscriptionPeriodType.TRIAL);

        assertThatCode(() -> service.createOne(period)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a paid period on that same plan is fine — the tier rule is trial-only")
    void paidOnNonUpgradePlan_allowed() {
        // Selling a low-tier plan is a pricing decision, not a data-integrity problem; only the trial case is
        // incoherent. Pinned so the guard cannot drift into blocking paid sales.
        Plan legacy = plan("plan.legacy", null);
        catalog = List.of(FREE, PRO, legacy);
        lookedUpPlan = legacy;

        assertThatCode(() -> service.createOne(period(SUB_ID, START, START.plusMonths(1), legacy.getId())))
                .doesNotThrowAnyException();
    }

    // ─── overlap is accepted, not rejected ───
    //
    // These three used to assert the opposite. Overlap was rejected so that "the period covering today" had
    // one answer — a rule that became impossible to keep the moment every tenant started owning an open-ended
    // base period: every sale overlaps it, so rejecting overlap would reject every sale. Ambiguity is now
    // resolved rather than prevented, by the projection picking the highest plan tier among the periods
    // covering a date. Kept as accept-cases so the reversal is visible and cannot be undone by accident.

    @Test
    @DisplayName("a period landing inside an existing one is accepted")
    void containedOverlap_accepted() {
        stored.add(storedPeriod(1L, START, START.plusMonths(12)));

        assertThatCode(() -> service.createOne(
                period(SUB_ID, START.plusMonths(3), START.plusMonths(6), "plan.pro")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a period straddling an existing one's end is accepted")
    void tailOverlap_accepted() {
        stored.add(storedPeriod(1L, START, START.plusMonths(12)));

        assertThatCode(() -> service.createOne(
                period(SUB_ID, START.plusMonths(11), START.plusMonths(18), "plan.pro")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an open-ended existing period no longer blocks what comes after it")
    void openEndedIncumbent_doesNotBlockLater() {
        // The base period is exactly this shape — open-ended, starting at tenant creation. Under the old rule
        // it blocked every later period, i.e. it blocked selling anything at all.
        stored.add(storedPeriod(1L, START, null));

        assertThatCode(() -> service.createOne(
                period(SUB_ID, START.plusYears(5), START.plusYears(6), "plan.pro")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adjacent periods — the next starts the day after — are not an overlap")
    void adjacentPeriods_allowed() {
        // The ordinary renewal, and the whole reason the bound is `isBefore` rather than `!isAfter`: an
        // off-by-one here rejects every seamless renewal ops enters.
        stored.add(storedPeriod(1L, START, START.plusMonths(12)));

        assertThatCode(() -> service.createOne(
                period(SUB_ID, START.plusMonths(12).plusDays(1), START.plusMonths(24), "plan.pro")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a gap between periods is allowed — the customer simply did not buy those months")
    void gapBetweenPeriods_allowed() {
        stored.add(storedPeriod(1L, START, START.plusMonths(12)));

        assertThatCode(() -> service.createOne(
                period(SUB_ID, START.plusMonths(18), START.plusMonths(30), "plan.pro")))
                .doesNotThrowAnyException();
    }

    // ─── fixtures ───

    /**
     * Both collaborators are injected fields, so they go in by reflection (as in
     * {@code TenantSubscriptionProjectionWriteTest}).
     *
     * <p>Two of them are called {@code modelService}: the subclass's own, and the inherited one
     * {@code EntityServiceImpl} writes through. The inherited one is named explicitly by its declaring class —
     * the subclass field shadows it for the 3-argument setter, and leaving it unset makes every accept case
     * NPE on the write, which would let a guard that wrongly rejects pass as "threw something".
     */
    @SuppressWarnings("unchecked")
    private TenantSubscriptionPeriodServiceImpl newService() {
        TenantSubscriptionPeriodServiceImpl impl = spy(new TenantSubscriptionPeriodServiceImpl());
        ModelService<Long> modelService = mock(ModelService.class);

        // One mocked signature serves both plan reads, so they are told apart the way the call sites differ:
        // `floorPlan()` scans the catalog with no filter, `planById()` asks for one id.
        when(modelService.searchList(eq("Plan"), any(FlexQuery.class), eq(Plan.class)))
                .thenAnswer(invocation -> {
                    FlexQuery query = invocation.getArgument(1);
                    return Filters.isEmpty(query.getFilters())
                            ? catalog
                            : (lookedUpPlan == null ? List.of() : List.of(lookedUpPlan));
                });
        // No tenant owns this subscription in these fixtures, so `stampOwnerTenant` leaves tenantId null and
        // `refreshOwner` logs that it cannot refresh — neither is under test here.
        when(modelService.searchList(eq("TenantInfo"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        when(modelService.createOne(anyString(), anyMap())).thenReturn(4242L);

        ReflectionTestUtils.setField(impl, "modelService", modelService);
        ReflectionTestUtils.setField(impl, EntityServiceImpl.class, "modelService", modelService,
                ModelService.class);
        ReflectionTestUtils.setField(impl, "projectionService", mock(SubscriptionProjectionService.class));
        // The floor-cardinality scan reads the stored rows; the list is mutated per test, so the stub returns
        // it live.
        doReturn(stored).when(impl).searchList(any(FlexQuery.class));
        // By-id reads resolve against the same list. Both `ownerOf` and the delete guard look a period up this
        // way, so without it a stored row is invisible to them and a guard keyed on the row's plan cannot fire.
        doAnswer(invocation -> {
            Long wanted = invocation.getArgument(0);
            return stored.stream().filter(row -> wanted != null && wanted.equals(row.getId())).findFirst();
        }).when(impl).getById(anyLong());
        // Deletion itself is the framework's; these fixtures only exercise what guards it.
        doReturn(true).when(impl).deleteByIds(anyList());
        return impl;
    }

    private static TenantSubscriptionPeriod period(Long subscriptionId, LocalDate from, LocalDate to,
                                                   String planId) {
        TenantSubscriptionPeriod period = new TenantSubscriptionPeriod();
        period.setSubscriptionId(subscriptionId);
        period.setEffectiveStartDate(from);
        period.setEffectiveEndDate(to);
        period.setPlanId(planId);
        period.setPeriodType(SubscriptionPeriodType.PAID);
        return period;
    }

    private static TenantSubscriptionPeriod storedPeriod(Long id, LocalDate from, LocalDate to) {
        return storedPeriod(id, from, to, "plan.pro");
    }

    /** Same, with the plan named — the floor-cardinality cases turn on which plan a stored row is on. */
    private static TenantSubscriptionPeriod storedPeriod(Long id, LocalDate from, LocalDate to, String planId) {
        TenantSubscriptionPeriod period = period(SUB_ID, from, to, planId);
        period.setId(id);
        return period;
    }

    private static Plan plan(String id, Integer tier) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setTier(tier);
        return plan;
    }
}
