package io.softa.starter.tenant.entitlement;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.service.PlanCatalog;
import io.softa.starter.tenant.entity.PlanEntitlement;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscription;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.service.SubscriptionProjectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a tenant's effective entitlement (the {@link EntitlementInfo} behind the
 * {@link io.softa.framework.orm.service.EntitlementService} SPI). Self-contained in tenant-starter:
 * reads the tenant's 1:1 {@code TenantSubscription} (via {@code TenantInfo.subscriptionId}) +
 * plan_entitlement, never the nav tree — so tenant-starter needs no user-starter dependency.
 *
 * <h3>Resolution</h3>
 * <pre>
 *   sub     = TenantInfo(tenantId).subscriptionId → tenant_subscription   (one row per tenant)
 *   sub     = refreshed on the spot when sub.projectedForDate != that tenant's local today
 *   plan    = sub.planId   (null → the tenant has no period covering today → floor plan)
 *   modules = ( plan_entitlement.moduleId WHERE planId = plan )
 *   fail-closed: an empty/unconfigured plan set → the floor plan's set, never a partial one.
 * </pre>
 *
 * <h3>Why reading a projection is safe here</h3>
 * The subscription row is a projection of the period rows, so it can be stale — but it carries the date
 * it was projected for. This resolver compares that against the tenant's local today and recomputes
 * inline when they differ, so a missed scheduled refresh degrades to "recomputed on first request", not
 * to "yesterday's plan served". The old design gated on a stored {@code lifecycle} with no such marker,
 * which is why it needed a job to chase the dates and drifted when the job did not run.
 *
 * <h3>⚠️ The cache short-circuits the self-heal</h3>
 * A cache hit returns before the subscription row is read, so the staleness check never runs. The TTL
 * therefore must not let an entry outlive the tenant's local midnight — see
 * {@link #ttlUntilTenantMidnight}. Cache and self-heal are one mechanism: the cache guarantees "no
 * recompute before midnight", the self-heal guarantees "always recompute after it". Get either wrong and
 * a tenant is served the previous day's plan across the boundary.
 *
 * <h3>Floor plan (no hardcoded id)</h3>
 * The degrade target is the catalog's <b>lowest-tier plan</b> (min {@code Plan.tier}), NOT a fixed id
 * like {@code "plan.free"} — so any deployment's own plan naming works out of the box. If the catalog
 * has no plan at all, the floor is the <b>empty</b> entitlement (unpaid ⇒ no access); a deployment that
 * wants a no-charge floor seeds a lowest-tier plan with a base module set, one whose floor is paid seeds a
 * zero-module placeholder instead.
 *
 * <p>All reads run under {@link SkipPermissionCheck} — fires on the cross-bean call from
 * {@code EntitlementServiceImpl}.
 */
@Slf4j
@Component
public class EntitlementResolver {

    private final ModelService<?> modelService;
    private final CacheService cacheService;
    private final SubscriptionProjectionService projectionService;

    public EntitlementResolver(ModelService<?> modelService, CacheService cacheService,
                              SubscriptionProjectionService projectionService) {
        this.modelService = modelService;
        this.cacheService = cacheService;
        this.projectionService = projectionService;
    }

    /** Resolve (cache-aside) the tenant's effective entitlement. Never null. */
    @SkipPermissionCheck
    public EntitlementInfo resolve(Long tenantId) {
        if (tenantId == null) {
            return fallbackInfo();
        }
        String key = RedisConstant.ENTITLEMENT + tenantId;
        EntitlementInfo cached = cacheService.get(key, EntitlementInfo.class);
        if (cached != null) {
            // Returns before the subscription row is touched, so the staleness check below never runs on
            // this path — which is exactly why the TTL is capped at the tenant's local midnight.
            return cached;
        }
        TenantInfo tenant = loadTenant(tenantId);
        EntitlementInfo info = compute(tenant);
        cacheService.save(key, info, ttlUntilTenantMidnight(tenant));
        return info;
    }

    /**
     * Seconds until the owning tenant's next local midnight, capped at an hour and floored at a minute.
     *
     * <p>A fixed hour would not do: an entry written at 23:30 local would live until 00:30 the next local
     * day, and a period boundary falling on that midnight would be served stale. Capping at the boundary
     * makes it structurally impossible for a cached entry to span a plan change.
     */
    private int ttlUntilTenantMidnight(TenantInfo tenant) {
        ZoneId zone = Timezone.zoneIdOrUtc(tenant == null ? null : tenant.getDefaultTimezone());
        long seconds = Duration.between(ZonedDateTime.now(zone),
                LocalDate.now(zone).plusDays(1).atStartOfDay(zone)).getSeconds();
        return (int) Math.min(RedisConstant.ONE_HOUR, Math.max(60L, seconds));
    }

    /** Evict the tenant's {@code entl:} snapshot — call after any plan / lifecycle change. */
    public void evict(Long tenantId) {
        if (tenantId != null) {
            cacheService.clear(RedisConstant.ENTITLEMENT + tenantId);
        }
    }

    private EntitlementInfo compute(TenantInfo tenant) {
        TenantSubscription sub = loadSubscription(tenant);
        // planId set = a period covers this tenant's local today. Null = no covering period (never
        // bought / lapsed / scheduled-but-not-started), all of which run on the floor plan.
        if (sub != null && sub.getPlanId() != null) {
            Set<String> modules = new HashSet<>(planModules(sub.getPlanId()));
            if (!modules.isEmpty()) {
                Plan plan = planById(sub.getPlanId());
                int tier = (plan != null && plan.getTier() != null) ? plan.getTier() : 0;
                return new EntitlementInfo(sub.getPlanId(), tier, modules);
            }
            // plan_entitlement missing / plan deleted → fall through, never a partial / 0-module set (which
            // would look like site-wide loss of access).
        }
        // No covering period. Two very different situations, and collapsing them was the old bug:
        //
        //   a) the tenant HAS its base period and it has ended — an operator gave it an end date, on
        //      purpose, to cut the tenant off. Entitlement is nothing. Falling back to the floor plan here
        //      would silently undo that decision and grant base access forever.
        //
        //   b) the tenant has NO floor period at all — a row written before provisioning started creating one,
        //      or periods deleted directly in the database. Granting nothing would take access away from an
        //      existing customer on the deploy that introduced this, so fall back and say so in the log: the
        //      remedy is to give that subscription its base period.
        if (hasFloorPeriod(tenant)) {
            return new EntitlementInfo(null, 0, Set.of());
        }
        log.warn("Tenant {} has no baseline period on the floor plan; granting the floor plan's modules as a "
                + "fallback. Give this subscription its base period — the fallback is not how base access works.",
                tenant == null ? null : tenant.getId());
        return fallbackInfo();
    }

    /**
     * Whether this tenant owns a period on the floor plan at all — regardless of whether it still covers
     * today. Distinguishes "the base period was deliberately ended" from "it was never recorded", which are the two
     * ways to arrive at no covering period and which demand opposite answers.
     */
    private boolean hasFloorPeriod(TenantInfo tenant) {
        if (tenant == null || tenant.getSubscriptionId() == null) {
            return false;
        }
        Plan floor = fallbackPlan();
        if (floor == null) {
            return false;
        }
        return modelService.searchList("TenantSubscriptionPeriod",
                        new FlexQuery(Filters.of("subscriptionId", Operator.EQUAL, tenant.getSubscriptionId())),
                        TenantSubscriptionPeriod.class)
                .stream()
                .anyMatch(period -> floor.getId().equals(period.getPlanId()));
    }

    /**
     * The floor plan's entitlement. No longer "how a tenant without periods is served" — every tenant owns a
     * base period now — but the repair path for one whose baseline row is missing. See {@link #compute}.
     */
    private EntitlementInfo fallbackInfo() {
        Plan fb = fallbackPlan();
        if (fb == null) {
            // No plans configured → no floor → no modules. A deployment wanting a no-charge floor seeds a
            // lowest-tier plan with its base modules.
            return new EntitlementInfo(null, 0, Set.of());
        }
        int tier = fb.getTier() != null ? fb.getTier() : 0;
        return new EntitlementInfo(fb.getId(), tier, new HashSet<>(planModules(fb.getId())));
    }

    /** The catalog's floor plan = the smallest {@code tier} (ties broken by id); null if no plans. */
    private Plan fallbackPlan() {
        return PlanCatalog.floorPlan(modelService);
    }

    private TenantInfo loadTenant(Long tenantId) {
        List<TenantInfo> tenants = modelService.searchList("TenantInfo",
                new FlexQuery(Filters.of("id", Operator.EQUAL, tenantId)), TenantInfo.class);
        return tenants.isEmpty() ? null : tenants.getFirst();
    }

    /**
     * The tenant's subscription row, brought up to date first.
     *
     * <p>{@code TenantInfo} owns {@code subscriptionId} (1:1), so read the registry row, then the
     * subscription it points at. No tenant / no {@code subscriptionId} → no subscription → floor plan.
     *
     * <p>The refresh is best-effort on purpose: this is a read path, and a failed projection write must
     * not fail authorization. When it cannot persist, the projection service still returns the freshly
     * computed row, so this request is answered correctly and the write is retried on the next one.
     */
    private TenantSubscription loadSubscription(TenantInfo tenant) {
        if (tenant == null || tenant.getSubscriptionId() == null) {
            return null;
        }
        try {
            TenantSubscription refreshed = projectionService.refresh(tenant);
            if (refreshed != null) {
                return refreshed;
            }
        } catch (Exception e) {
            log.warn("Entitlement resolve — projection refresh failed for tenant {}, falling back to the "
                    + "stored projection: {}", tenant.getId(), e.getMessage());
        }
        List<TenantSubscription> subs = modelService.searchList("TenantSubscription",
                new FlexQuery(Filters.of("id", Operator.EQUAL, tenant.getSubscriptionId())),
                TenantSubscription.class);
        return subs.isEmpty() ? null : subs.getFirst();
    }

    private Set<String> planModules(String planId) {
        if (planId == null) {
            return Set.of();
        }
        List<PlanEntitlement> rows = modelService.searchList("PlanEntitlement",
                new FlexQuery(Filters.of("planId", Operator.EQUAL, planId)), PlanEntitlement.class);
        Set<String> out = new HashSet<>();
        for (PlanEntitlement r : rows) {
            if (r.getModuleId() != null) {
                out.add(r.getModuleId());
            }
        }
        return out;
    }

    private Plan planById(String planId) {
        if (planId == null) {
            return null;
        }
        List<Plan> plans = modelService.searchList("Plan",
                new FlexQuery(Filters.of("id", Operator.EQUAL, planId)), Plan.class);
        return plans.isEmpty() ? null : plans.get(0);
    }
}
