package io.softa.starter.tenant.service;

import java.util.Comparator;
import java.util.List;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;

/**
 * Plan-catalog reads that more than one caller needs, in one place.
 *
 * <p>{@link #floorPlan} in particular: "the plan a tenant starts on" was being recomputed
 * independently by the entitlement resolver and by the period write guards, and provisioning now needs
 * it a third time to write the base period at tenant creation. Three copies of a rule that decides
 * which modules a tenant may reach is three chances to disagree, and a disagreement here is invisible —
 * each caller looks correct on its own and the tenant simply gets the wrong plan.
 *
 * <p>Static, taking the {@code ModelService} as a parameter, rather than an injected component: the
 * callers all hold one already, so this way they delegate without a constructor change, and adding the
 * shared rule costs no churn in the places that were already right.
 */
public final class PlanCatalog {

    private PlanCatalog() {
    }

    /**
     * The catalog's baseline plan — the one every tenant starts on. Defined as the <b>lowest
     * {@code tier}</b>, ties broken by id, and deliberately NOT a fixed id like {@code "plan.free"}: a
     * deployment names its own plans, so a hard-coded name would leave every other deployment silently
     * resolving nothing.
     *
     * @return the floor plan, or {@code null} when no plan in the catalog declares a tier
     */
    public static Plan floorPlan(ModelService<?> modelService) {
        List<Plan> plans = modelService.searchList("Plan", new FlexQuery(new Filters()), Plan.class);
        return plans.stream()
                .filter(p -> p.getTier() != null)
                .min(Comparator.comparingInt(Plan::getTier).thenComparing(Plan::getId))
                .orElse(null);
    }

    /** One plan by id, or {@code null} when absent — a dangling planId must not throw. */
    public static Plan planById(ModelService<?> modelService, String planId) {
        if (planId == null || planId.isBlank()) {
            return null;
        }
        List<Plan> plans = modelService.searchList("Plan",
                new FlexQuery(Filters.of("id", Operator.EQUAL, planId)), Plan.class);
        return plans.isEmpty() ? null : plans.getFirst();
    }
}
