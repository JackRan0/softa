package io.softa.starter.tenant.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;

/**
 * Which of a subscription's periods applies on a given date.
 *
 * <p>Overlapping periods are normal: every tenant owns an open-ended period on the floor plan from the day it
 * was created, so anything sold on top of it overlaps by construction. The winner is the <b>highest plan
 * tier</b> — that is what makes the base period harmless, letting a tenant on Pro be on Pro even though its
 * base period covers the same day.
 *
 * <p>Extracted because two callers need the identical answer and would otherwise each carry their own copy:
 * the projection, which writes the tenant's current plan, and the expiry reminder, which has to know what will
 * apply the day <i>after</i> a period ends. Those two disagreeing is the specific failure this prevents —
 * a reminder telling a customer their access lapses while the projection quietly moves them to the floor plan
 * is not a wording problem, it is two different beliefs about the same subscription.
 *
 * <p>Static, taking the {@code ModelService}, for the same reason as {@link PlanCatalog}: the callers already
 * hold one, so sharing the rule costs them no constructor change.
 */
public final class PeriodSelection {

    private PeriodSelection() {
    }

    /**
     * planId → tier for the whole catalog. Read once and passed into the per-period calls: a lookup per row
     * would issue one query per period, and a tenant with a long history has many.
     */
    public static Map<String, Integer> tierByPlan(ModelService<?> modelService) {
        return modelService.searchList("Plan", new FlexQuery(new Filters()), Plan.class).stream()
                .filter(plan -> plan.getId() != null && plan.getTier() != null)
                .collect(Collectors.toMap(Plan::getId, Plan::getTier, (a, b) -> a));
    }

    /**
     * A period's plan tier. An unknown or absent plan sorts LOWEST rather than throwing: a dangling planId is
     * bad data, and letting it win would hand the tenant an unresolvable plan; letting it lose means the tenant
     * keeps whatever else covers the date, which for every tenant includes at least its base period.
     */
    public static int tierOf(Map<String, Integer> tierByPlan, String planId) {
        Integer tier = planId == null ? null : tierByPlan.get(planId);
        return tier == null ? Integer.MIN_VALUE : tier;
    }

    /** A period covers a date when it has started and has not ended; a null end date means open-ended. */
    public static boolean covers(TenantSubscriptionPeriod period, LocalDate date) {
        if (period == null || period.getEffectiveStartDate() == null
                || period.getEffectiveStartDate().isAfter(date)) {
            return false;
        }
        return period.getEffectiveEndDate() == null || !period.getEffectiveEndDate().isBefore(date);
    }

    /**
     * The period that applies on {@code date}, or {@code null} when none covers it.
     *
     * <p>Highest tier wins. Ties are broken by latest start then highest id, purely for determinism:
     * authorization reads the result of this, so an arbitrary pick could flip the granted plan between
     * refreshes with nothing having changed.
     *
     * @param periods    all periods of one subscription — the caller has them grouped already
     * @param date       the date to resolve, in the tenant's own timezone
     * @param tierByPlan the catalog map from {@link #tierByPlan}
     */
    public static TenantSubscriptionPeriod winnerOn(List<TenantSubscriptionPeriod> periods, LocalDate date,
                                                    Map<String, Integer> tierByPlan) {
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        return periods.stream()
                .filter(p -> covers(p, date))
                .max(Comparator
                        .<TenantSubscriptionPeriod>comparingInt(p -> tierOf(tierByPlan, p.getPlanId()))
                        .thenComparing(TenantSubscriptionPeriod::getEffectiveStartDate)
                        .thenComparing(TenantSubscriptionPeriod::getId))
                .orElse(null);
    }
}
