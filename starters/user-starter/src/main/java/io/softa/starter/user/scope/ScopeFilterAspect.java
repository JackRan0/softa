package io.softa.starter.user.scope;

import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.service.PermissionInfoEnricher;

/**
 * Data-scope enforcement (Layer B of the v4 three-layer filter chain).
 *
 * <p>Aspects every call into ModelService's search methods. For each call:
 * <ol>
 *   <li>Resolve the current PermissionInfo (cached per request via
 *       PermissionInfoEnricher; super-admin bypass already baked in).</li>
 *   <li>SUPER_ADMIN → proceed as-is (no scope filter appended).</li>
 *   <li>Context.skipPermissionCheck=true → proceed as-is (internal queries
 *       under {@code @SkipPermissionCheck} / {@code @SwitchUser} bypass).</li>
 *   <li>Compile {@code pi.modelScopeMap[model]} via {@link ScopeRuleCompiler}.
 *       Result is one of:
 *       <ul>
 *         <li>{@code null} — at least one ALL rule → no scope filter</li>
 *         <li>Filters.EMPTY — no rules OR every rule degraded → return zero rows</li>
 *         <li>actual Filters — AND-merge into FlexQuery.filters</li>
 *       </ul>
 *   </li>
 *   <li>Proceed with the mutated query.</li>
 * </ol>
 *
 * <p>Pointcut targets {@code ModelService.searchList(String, FlexQuery, ...)}
 * and {@code searchPage(String, FlexQuery, ...)} variants. Methods that take
 * an already-bypassed FlexQuery (subQuery processors do this) carry
 * {@code FilterControl.bypassAll()} on the FlexQuery and are skipped
 * implicitly by the framework's existing bypass machinery — we don't need to
 * second-guess here.
 *
 * <p>Failure mode: any exception inside this aspect falls through to the
 * original query (fail-OPEN at the aspect level). The real fail-CLOSED
 * defence is at row-data return — if the compiler degrades to EMPTY the
 * query returns no rows. We log loudly on aspect errors so ops can detect
 * misconfiguration.
 */
@Slf4j
@Aspect
@Component
public class ScopeFilterAspect {

    private final PermissionInfoEnricher permissionInfoEnricher;
    private final ScopeRuleCompiler scopeCompiler;

    /**
     * Explicit constructor (not Lombok's {@code @RequiredArgsConstructor})
     * so we can put {@code @Lazy} on the {@link PermissionInfoEnricher}
     * parameter — without it, Spring reports a circular dependency:
     * <pre>
     *   ScopeFilterAspect → PermissionInfoEnricher → NavigationModelResolver
     *                    → (ModelService AOP proxy) → ScopeFilterAspect
     * </pre>
     * NavigationModelResolverImpl uses ModelService, and because this aspect
     * wraps ModelService methods, every ModelService consumer transitively
     * needs the aspect bean. {@code @Lazy} on the enricher dependency breaks
     * the cycle — the aspect gets a JDK proxy that resolves the real
     * enricher only when the aspect actually fires (i.e. on the first
     * request), by which time every bean is wired.
     */
    public ScopeFilterAspect(
            @Lazy PermissionInfoEnricher permissionInfoEnricher,
            ScopeRuleCompiler scopeCompiler) {
        this.permissionInfoEnricher = permissionInfoEnricher;
        this.scopeCompiler = scopeCompiler;
    }

    /**
     * {@code searchList(String, FlexQuery, ...)} and
     * {@code searchPage(String, FlexQuery, ...)} both share the
     * {@code (String modelName, FlexQuery flexQuery, ...)} prefix in argument
     * shape, so a single pointcut covers both.
     */
    @Around(
            "execution(* io.softa.framework.orm.service.ModelService.search*(..)) "
                    + "&& args(modelName, flexQuery, ..)"
    )
    public Object applyScope(ProceedingJoinPoint pjp, String modelName, FlexQuery flexQuery)
            throws Throwable {
        try {
            Context ctx = ContextHolder.getContext();
            if (ctx == null || ctx.isSkipPermissionCheck()) {
                return pjp.proceed();
            }
            if (ctx.getUserId() == null) {
                // Anonymous (public endpoint or unauthenticated) — let upstream
                // PermissionInterceptor decide; we don't apply scope.
                return pjp.proceed();
            }

            // PermissionInfoEnricher always returns a non-null PermissionInfo
            // (worst case: emptyGrantsSnapshot). If a future change makes it
            // nullable, the NPE here is the correct behaviour — fail-closed by
            // surfacing the contract break rather than silently fail-OPEN.
            PermissionInfo pi = permissionInfoEnricher.enrich(ctx.getTenantId(), ctx.getUserId());

            // SUPER_ADMIN bypass — short-circuit before doing any work.
            if (pi.isSuperAdmin()) {
                return pjp.proceed();
            }

            List<ScopeRule> rules = pi.getModelScopeMap() == null
                    ? null
                    : pi.getModelScopeMap().get(modelName);

            // No rules configured for this model → fail-closed: NO ROWS.
            // The compiler returns EMPTY; we still AND-merge so the
            // resulting query yields no rows. This is intentional: a model
            // that no role has scope on means "no one can see this list".
            // If a model should be open to everyone (lookup tables etc.)
            // it'll be granted at the role_navigation level with ALL scope.
            Filters compiled = scopeCompiler.compile(rules, pi, modelName);

            // null sentinel = ALL → no scope filter
            if (compiled == null) {
                return pjp.proceed();
            }

            // Merge with the caller's filters (AND).
            Filters existing = flexQuery.getFilters();
            if (existing == null || Filters.isEmpty(existing)) {
                flexQuery.setFilters(compiled);
            } else if (!Filters.isEmpty(compiled)) {
                flexQuery.setFilters(existing.and(compiled));
            } else {
                // compiled == EMPTY — fail-closed.
                flexQuery.setFilters(compiled);
            }
            return pjp.proceed();
        } catch (Throwable t) {
            // Aspect logic blew up — log loudly but proceed with the original
            // query. We DO NOT want a bug in scope compilation to take down
            // the whole API; the trade-off is "scope might leak for the
            // duration of the bug" → caller should monitor these warnings.
            log.warn("ScopeFilterAspect failure for model={}; proceeding without scope filter",
                    modelName, t);
            return pjp.proceed();
        }
    }
}
