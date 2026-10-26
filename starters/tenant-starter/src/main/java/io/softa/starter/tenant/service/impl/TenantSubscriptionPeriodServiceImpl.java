package io.softa.starter.tenant.service.impl;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.service.PeriodSelection;
import io.softa.starter.tenant.service.PlanCatalog;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import io.softa.starter.tenant.service.SubscriptionPeriodPatch;
import io.softa.starter.tenant.service.SubscriptionProjectionService;
import io.softa.starter.tenant.service.TenantSubscriptionPeriodService;

/**
 * {@link TenantSubscriptionPeriodService} — the guards and the projection refresh live here, so this is
 * the only sanctioned way into the table.
 *
 * <p>Every mutating override follows the same shape: validate, delegate to the framework, then refresh the
 * owning subscription's projection. The refresh is deliberately <b>not</b> deferred to the hourly job:
 * ops expects the tenant list to reflect what it just entered.
 */
@Slf4j
@Service
public class TenantSubscriptionPeriodServiceImpl
        extends EntityServiceImpl<TenantSubscriptionPeriod, Long>
        implements TenantSubscriptionPeriodService {

    @Autowired
    private ModelService<?> modelService;

    @Autowired
    private SubscriptionProjectionService projectionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOne(TenantSubscriptionPeriod entity) {
        stampOwnerTenant(entity);
        validate(entity, null);
        Long id = super.createOne(entity);
        refreshOwner(entity.getSubscriptionId());
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateOne(TenantSubscriptionPeriod entity) {
        Assert.notNull(entity == null ? null : entity.getId(), "Period id is required to update it.");
        // Also on update: it repairs a row whose tenant was never stamped (anything predating the column).
        stampOwnerTenant(entity);
        TenantSubscriptionPeriod merged = merge(entity);
        validate(merged, merged.getId());
        boolean ok = super.updateOne(entity);
        refreshOwner(merged.getSubscriptionId());
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        refuseFloorPeriodDeletion(id == null ? List.of() : List.of(id));
        Long owner = ownerOf(id);
        boolean ok = super.deleteById(id);
        refreshOwner(owner);
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteByIds(List<Long> ids) {
        refuseFloorPeriodDeletion(ids == null ? List.of() : ids);
        List<Long> owners = ids == null ? List.of() : ids.stream().map(this::ownerOf)
                .filter(Objects::nonNull).distinct().toList();
        boolean ok = super.deleteByIds(ids);
        owners.forEach(this::refreshOwner);
        return ok;
    }

    /**
     * The base period cannot be deleted. It is the tenant's baseline entitlement — the row that makes
     * "what may this tenant reach" answerable from data — and provisioning creates it once, at tenant
     * creation, so nothing puts it back.
     *
     * <p>Deleting it does the opposite of what the operator doing it intends. The entitlement resolver reads
     * "no floor period at all" as "this row is MISSING" and falls back to granting the floor plan's modules,
     * so a base-only tenant deleted out of its baseline quietly keeps base access. Cutting a tenant off is done by
     * giving that period an end date, which is why the message says so.
     *
     * <p>Enforced here rather than in the frontend because there are four ways in: the delete endpoints, the
     * relation patch's {@code delete} list, and a generic call. All of them land on these two methods.
     */
    private void refuseFloorPeriodDeletion(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Plan floor = floorPlan();
        if (floor == null) {
            return;
        }
        for (Long id : ids) {
            TenantSubscriptionPeriod period = id == null ? null : getById(id).orElse(null);
            Assert.notTrue(period != null && floor.getId().equals(period.getPlanId()),
                    "The {0} period cannot be deleted — it is this tenant's baseline. To cut the tenant off, "
                            + "give that period an end date instead.", floor.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long changePlanNow(Long subscriptionId, String planId, SubscriptionPeriodType periodType) {
        Assert.notNull(subscriptionId, "Subscription id is required.");
        LocalDate today = projectionService.tenantLocalToday(ownerTenant(subscriptionId));
        // The period that actually applies today, by plan tier — NOT the first covering row.
        //
        // `findFirst()` was written when overlap was rejected, so at most one row could cover a date and
        // whichever came back was the answer. Every tenant now owns an open-ended floor period, so a tenant on
        // Pro has two covering rows and an arbitrary pick lands on the base one about as often as not. Changing
        // plan would then close the tenant's baseline off instead of its purchase — and on a tenant created
        // today it would take the same-day branch below and rewrite the floor period's own plan, leaving the
        // subscription with no floor period at all, which the resolver reads as "predates the migration" and
        // answers with a fallback nobody asked for.
        Map<String, Integer> tierByPlan = PeriodSelection.tierByPlan(modelService);
        TenantSubscriptionPeriod current =
                PeriodSelection.winnerOn(periodsOf(subscriptionId), today, tierByPlan);
        Assert.notNull(current, "This tenant has no period in effect today; record a new period instead.");

        // Refuse the floor period as the target even when it is what applies. "Change plan" means selling
        // something on top of the baseline, never rewriting the baseline: closing it off or repointing it
        // breaks the same invariant the deletion and immutable-start guards protect, and would do it through a
        // third door. A tenant genuinely on its base period alone upgrades by recording a period, which is the path
        // the error names.
        Plan floor = PlanCatalog.floorPlan(modelService);
        if (floor != null) {
            Assert.notTrue(floor.getId().equals(current.getPlanId()),
                    "This tenant is on the {0} plan; record a new period for the plan it is moving to "
                            + "instead of changing the baseline.", floor.getId());
        }

        // Inherit the paid-through date before closing the old period off, otherwise the customer loses
        // the remainder of what they already paid for.
        LocalDate inheritedEnd = current.getEffectiveEndDate();
        if (today.equals(current.getEffectiveStartDate())) {
            // Changed on the same day it started — correct that period in place rather than leaving a
            // zero-length one behind.
            current.setPlanId(planId);
            current.setPeriodType(periodType);
            updateOne(current);
            return current.getId();
        }
        current.setEffectiveEndDate(today.minusDays(1));
        updateOne(current);

        TenantSubscriptionPeriod next = new TenantSubscriptionPeriod();
        next.setSubscriptionId(subscriptionId);
        next.setPlanId(planId);
        next.setPeriodType(periodType);
        next.setEffectiveStartDate(today);
        next.setEffectiveEndDate(inheritedEnd);
        return createOne(next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyPatch(Long subscriptionId, SubscriptionPeriodPatch patch) {
        Assert.notNull(subscriptionId, "Subscription id is required to edit its periods.");
        if (patch == null) {
            return;
        }
        // Logged because a patch that binds partially is otherwise invisible: the request succeeds, the
        // projection is refreshed against whatever did land, and the row the user filled in is simply not
        // there. The plan is included because it is the field most likely to arrive empty — the UI sends a
        // reference, and only its id belongs in this DTO.
        log.info("Subscription {} period patch — create={} update={} delete={} plans={}", subscriptionId,
                nonNull(patch.getCreate()).size(), nonNull(patch.getUpdate()).size(),
                nonNull(patch.getDelete()).size(),
                nonNull(patch.getCreate()).stream().map(SubscriptionPeriodPatch.PeriodInput::getPlanId).toList());

        // Delete first so an interval being replaced is free before anything claims it; create last so a
        // new row is checked against the state the rest of the patch already produced.
        List<Long> toDelete = nonNull(patch.getDelete());
        if (!toDelete.isEmpty()) {
            deleteByIds(toDelete);
        }
        for (SubscriptionPeriodPatch.PeriodInput row : nonNull(patch.getUpdate())) {
            Assert.notNull(row.getId(), "Period id is required to update it.");
            updateOne(toEntity(row, subscriptionId));
        }
        for (SubscriptionPeriodPatch.PeriodInput row : nonNull(patch.getCreate())) {
            // Deliberately NOT skipped when the plan is blank. That is what an earlier version did, on the
            // theory that a blank row was one the form had left behind — and it silently swallowed rows the
            // user had filled in, because a plan that fails to bind also arrives blank. `validate` already
            // requires a plan, so letting the row through turns invisible data loss into a message.
            TenantSubscriptionPeriod period = toEntity(row, subscriptionId);
            period.setId(null);
            if (period.getPeriodType() == null) {
                period.setPeriodType(SubscriptionPeriodType.PAID);
            }
            if (period.getEffectiveStartDate() == null) {
                period.setEffectiveStartDate(projectionService.tenantLocalToday(ownerTenant(subscriptionId)));
            }
            createOne(period);
        }
        // Refresh once more at the end, against the subscription the caller named.
        //
        // Every operation above refreshes on its own, but each derives the owner from the row it touched —
        // and `deleteByIds` derives it by loading the row, so an id that no longer exists yields no owner
        // and the refresh is silently skipped. A patch is then able to change which periods exist while
        // leaving the projection describing the old ones, which is the one state this table must never be
        // in: authorization reads the projection, and `currentPeriodId` would point at a row that is gone.
        // Here the owner is an argument, so it cannot go missing.
        refreshOwner(subscriptionId);
    }

    /**
     * Derive the period's tenant from its subscription.
     *
     * <p>Always overwritten rather than filled in when absent: a caller-supplied value has no authority
     * here — the subscription decides whose period this is, and accepting anything else would let a request
     * label one tenant's period as another's.
     */
    private void stampOwnerTenant(TenantSubscriptionPeriod entity) {
        if (entity == null || entity.getSubscriptionId() == null) {
            return;
        }
        TenantInfo tenant = ownerTenant(entity.getSubscriptionId());
        entity.setTenantId(tenant == null ? null : tenant.getId());
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    /**
     * The owning subscription comes from the tenant, never from the payload — otherwise a crafted request
     * could attach a period to someone else's subscription.
     */
    private TenantSubscriptionPeriod toEntity(SubscriptionPeriodPatch.PeriodInput row, Long subscriptionId) {
        TenantSubscriptionPeriod period = new TenantSubscriptionPeriod();
        period.setId(row.getId());
        period.setSubscriptionId(subscriptionId);
        period.setPlanId(row.getPlanId());
        period.setPeriodType(row.getPeriodType());
        period.setEffectiveStartDate(row.getEffectiveStartDate());
        period.setEffectiveEndDate(row.getEffectiveEndDate());
        return period;
    }

    /**
     * The four guards. {@code selfId} is the row being updated, excluded from the overlap scan so a row
     * never conflicts with itself.
     */
    private void validate(TenantSubscriptionPeriod period, Long selfId) {
        Assert.notNull(period, "Period is required.");
        Assert.notNull(period.getSubscriptionId(), "Period must belong to a subscription.");
        Assert.notNull(period.getEffectiveStartDate(), "Period start date is required.");
        Assert.notBlank(period.getPlanId(), "Period plan is required.");
        Assert.notNull(period.getPeriodType(), "Period type (trial / paid) is required.");

        if (period.getEffectiveEndDate() != null) {
            Assert.isTrue(!period.getEffectiveEndDate().isBefore(period.getEffectiveStartDate()),
                    "Period end date {0} cannot precede its start date {1}.",
                    period.getEffectiveEndDate(), period.getEffectiveStartDate());
        }

        // The floor plan IS recorded now — provisioning writes one open-ended TRIAL period on it at tenant
        // creation, and that row is the tenant's baseline entitlement. Two guards used to stand here and both
        // rested on the opposite premise ("a floor period and no period express the same state", "only plans
        // above the floor can be trialled"); keeping either would reject the very row provisioning writes.
        //
        // What replaces them is a cardinality rule rather than a prohibition: at most ONE floor period per
        // subscription. That is what the old guards were really protecting — one baseline, unambiguous — and
        // it still holds, while allowing the one row that has to exist.
        Plan floor = floorPlan();

        // A sold (above-floor) period must be time-boxed: expiry sweeps, auto-downgrade and billing all
        // key off the end date, so an open-ended paid period would keep the tenant on the paid tier
        // forever. Only the floor period is legitimately open-ended — it IS the baseline entitlement.
        if (floor == null || !floor.getId().equals(period.getPlanId())) {
            Assert.notNull(period.getEffectiveEndDate(),
                    "A sold period must have an end date — only the floor period may be open-ended.");
        }

        if (floor != null && floor.getId().equals(period.getPlanId())) {
            boolean anotherFloorPeriod = periodsOf(period.getSubscriptionId()).stream()
                    .filter(other -> selfId == null || !selfId.equals(other.getId()))
                    .anyMatch(other -> floor.getId().equals(other.getPlanId()));
            Assert.notTrue(anotherFloorPeriod,
                    "This tenant already has its {0} period — there is exactly one, created with the tenant.",
                    floor.getId());

            // The base period's start date is fixed at the tenant's creation day. It is the anchor for "this
            // tenant has had base access since it existed", so moving it forward opens a stretch that nothing
            // covers — and with the floor-plan fallback gone, uncovered means zero modules. Moving it back
            // would claim access before the tenant existed. Only the END date is the operator's to set; that
            // is the whole mechanism for time-boxing base access.
            if (selfId != null) {
                TenantSubscriptionPeriod stored = getById(selfId).orElse(null);
                if (stored != null && stored.getEffectiveStartDate() != null) {
                    Assert.isTrue(stored.getEffectiveStartDate().equals(period.getEffectiveStartDate()),
                            "The {0} period starts on the tenant's creation day and that cannot be changed. "
                                    + "Set its end date instead to time-box base access.", floor.getId());
                }
            }
        }

        // Overlap is NOT rejected. It used to be, on the reasoning that "the period covering today" had to be
        // unambiguous — but every tenant now owns an open-ended base period, so every sold period overlaps at
        // least that one, and rejecting overlap would make selling anything impossible. Ambiguity is resolved
        // instead of prevented: the projection picks the highest plan tier among the periods covering a date
        // (SubscriptionProjectionServiceImpl), which is deterministic and is what makes the base period
        // harmless underneath everything else.
        //
        // Gaps are likewise not rejected, and were not before — the frontend's period-gap notice surfaces them
        // as a warning, because a stretch nobody bought is a legitimate outcome to see, not an error to block.
    }


    /**
     * An update payload may carry only the changed fields, so the guards need the stored row underneath —
     * validating the patch alone would let a half-specified update slip past the overlap check.
     */
    private TenantSubscriptionPeriod merge(TenantSubscriptionPeriod patch) {
        TenantSubscriptionPeriod stored = getById(patch.getId())
                .orElseThrow(() -> new IllegalArgumentException("Period " + patch.getId() + " not found."));
        if (patch.getSubscriptionId() != null) {
            stored.setSubscriptionId(patch.getSubscriptionId());
        }
        if (patch.getEffectiveStartDate() != null) {
            stored.setEffectiveStartDate(patch.getEffectiveStartDate());
        }
        if (patch.getEffectiveEndDate() != null) {
            stored.setEffectiveEndDate(patch.getEffectiveEndDate());
        }
        if (patch.getPlanId() != null) {
            stored.setPlanId(patch.getPlanId());
        }
        if (patch.getPeriodType() != null) {
            stored.setPeriodType(patch.getPeriodType());
        }
        return stored;
    }

    /** Package-private so a test can observe that every write path ends up here. */
    void refreshOwner(Long subscriptionId) {
        TenantInfo tenant = ownerTenant(subscriptionId);
        if (tenant == null) {
            log.warn("Subscription {} has no owning tenant — projection not refreshed", subscriptionId);
            return;
        }
        // Unconditional: the projection is still marked current for today, but the periods under it just
        // moved, so the staleness gate would wrongly decline.
        projectionService.refreshNow(tenant);
    }

    private TenantInfo ownerTenant(Long subscriptionId) {
        if (subscriptionId == null) {
            return null;
        }
        List<TenantInfo> tenants = modelService.searchList("TenantInfo",
                new FlexQuery(Filters.of("subscriptionId", Operator.EQUAL, subscriptionId)),
                TenantInfo.class);
        return tenants.isEmpty() ? null : tenants.getFirst();
    }

    private Long ownerOf(Long periodId) {
        return periodId == null ? null
                : getById(periodId).map(TenantSubscriptionPeriod::getSubscriptionId).orElse(null);
    }

    private List<TenantSubscriptionPeriod> periodsOf(Long subscriptionId) {
        return searchList(new FlexQuery(
                Filters.of("subscriptionId", Operator.EQUAL, subscriptionId)));
    }

    private Plan floorPlan() {
        return PlanCatalog.floorPlan(modelService);
    }

    private Plan planById(String planId) {
        List<Plan> plans = modelService.searchList("Plan",
                new FlexQuery(Filters.of("id", Operator.EQUAL, planId)), Plan.class);
        return plans.isEmpty() ? null : plans.getFirst();
    }
}
