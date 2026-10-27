package io.softa.starter.tenant.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;

/**
 * Typed CRUD for subscription periods — <b>the single write entry point</b> for the record layer.
 *
 * <p>Two things happen on every write here and nowhere else: the four guards (§ below) and the projection
 * refresh of the owning subscription row. Anything that reaches the table without passing through this
 * interface skips both, which is why the generic model endpoints for this model are shadowed rather than
 * left open — see {@code TenantSubscriptionPeriodController}.
 *
 * <h3>Guards</h3>
 * <ol>
 *   <li><b>End date must not precede start date.</b> An empty end date means open-ended.</li>
 *   <li><b>At most one period on the floor plan.</b> That row is the tenant's base access, written
 *       by provisioning; a second one would put the same entitlement in two places, and whichever was
 *       edited the other would still be granting access.</li>
 *   <li><b>The floor period's start date cannot be changed.</b> It is the tenant's creation day — the date
 *       base access began, which is history rather than a setting. Its <i>end</i> date is settable, and that
 *       is the whole mechanism for time-boxing base access.</li>
 *   <li><b>The floor period cannot be deleted.</b> The entitlement resolver reads a missing floor row as
 *       "this tenant predates the migration" and falls back to granting the floor plan's modules, so
 *       deleting the row would silently restore the very access an operator had just revoked by ending it.</li>
 * </ol>
 *
 * <p><b>Overlap is deliberately not guarded.</b> It used to be, on the reasoning that "the period covering
 * today" had to be unambiguous. It no longer can be: provisioning gives every tenant an open-ended floor
 * period, so every sale overlaps at least that one. Ambiguity is resolved by <b>plan tier</b> instead —
 * highest tier covering the date wins — which is what makes "sell Pro on top of base" mean the tenant gets
 * Pro. Back-filling historical periods and editing an ended period's dates are therefore both plain edits.
 *
 * <p>Guards 2–4 apply to updates as much as inserts, and the delete paths carry guard 4 as well, so there is
 * no write route that can leave a subscription without exactly one floor period.
 */
public interface TenantSubscriptionPeriodService extends EntityService<TenantSubscriptionPeriod, Long> {

    /**
     * Move a tenant to another plan effective today, keeping the paid-through date.
     *
     * <p>Two writes in one transaction: the covering period is closed off the day before, and a new period
     * inherits its original end date. Split across transactions there would be a moment with a gap in the
     * record, and an authorization landing in it would resolve to the floor plan.
     *
     * @param subscriptionId owning subscription
     * @param planId         plan to move to (must not be the floor plan)
     * @param periodType     trial or paid for the new period
     * @return the newly created period's id
     */
    Long changePlanNow(Long subscriptionId, String planId,
                       io.softa.starter.tenant.enums.SubscriptionPeriodType periodType);

    /**
     * Replay a UI relation patch through this service, one operation at a time.
     *
     * <p>Exists so the periods of a tenant can be edited as an inline relation table — the same table on the
     * create form and on the detail form — without the framework's nested-relation pipeline persisting them.
     * That pipeline goes through the generic {@code ModelService}, which runs none of the guards below and
     * leaves the projection stale; replaying the operations here keeps one form shape and one write path.
     *
     * <p>Order is <b>delete, then update, then create</b>, and it matters: freeing an interval before
     * anything claims it lets ops replace a period in a single submit. Creating first would have the new row
     * collide with the very row being deleted, and the overlap guard would reject a legitimate edit.
     *
     * <p>One transaction: a patch that fails half-way would otherwise leave the record with a gap, and an
     * authorization landing in it would resolve to the floor plan.
     *
     * @param subscriptionId owning subscription — taken from the tenant, never from the payload
     * @param patch          operations to apply; null / empty is a no-op
     */
    void applyPatch(Long subscriptionId, SubscriptionPeriodPatch patch);
}
