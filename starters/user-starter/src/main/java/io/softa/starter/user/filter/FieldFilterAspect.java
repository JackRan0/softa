package io.softa.starter.user.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.service.PermissionInfoEnricher;

/**
 * Layer C — service-side sensitive field gate. Replaces the earlier
 * response-side {@code FieldFilter} {@link
 * org.springframework.web.bind.annotation.RestControllerAdvice} by hooking
 * AOP directly at the {@link io.softa.framework.orm.service.ModelService}
 * read methods.
 *
 * <h3>Two-phase per call</h3>
 * <ol>
 *   <li><b>PRE</b> (search* only) — strip blocked fields from {@code
 *       flexQuery.fields} before the SQL is built. DB never SELECTs the
 *       masked columns; bandwidth / Java heap / JSON serialisation all
 *       avoid them.</li>
 *   <li><b>POST</b> (all read methods) — walk the returned {@code
 *       Map / List / Page / ApiResponse / Optional} tree and overwrite
 *       any blocked field with {@code null}. Catches:
 *       <ul>
 *         <li>Cascade-inlined related-model rows (e.g. Employee response
 *             carrying an inlined EmpBankAccount Map) — those columns
 *             aren't in {@code flexQuery.fields}, so PRE can't filter
 *             them.</li>
 *         <li>{@code getById} / {@code getByIds} signatures that don't
 *             expose a {@code Collection<String> fields} arg (e.g. the
 *             2-arg and {@code SubQueries} overloads) — PRE can't run,
 *             POST cleans the result.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Short-circuit conditions (mirror {@code ScopeFilterAspect})</h3>
 * <ul>
 *   <li>{@code Context.skipPermissionCheck=true} → proceed as-is.</li>
 *   <li>Anonymous request (no userId) → proceed as-is.</li>
 *   <li>SUPER_ADMIN → proceed as-is.</li>
 * </ul>
 *
 * <h3>Failure mode</h3>
 * Aspect failure → log + return the original result (fail-OPEN). The
 * real fail-CLOSED defence is at grant time — admins must explicitly
 * grant a sensitive field set for those fields to be visible. Aspect
 * is the enforcement layer; loud failure logs let ops detect bugs.
 */
@Slf4j
@Aspect
@Component
public class FieldFilterAspect {

    private final PermissionInfoEnricher permissionInfoEnricher;
    private final SensitiveFieldSetCache sfsCache;

    /**
     * Both deps are {@code @Lazy} to break two distinct construction-time
     * cycles:
     * <ul>
     *   <li>{@code PermissionInfoEnricher} — same chain as
     *       {@code ScopeFilterAspect}: enricher → NavigationModelResolver →
     *       ModelService → aspect.</li>
     *   <li>{@code SensitiveFieldSetCache} — the cache's
     *       {@code @PostConstruct} calls {@code modelService.searchList(
     *       "SensitiveFieldSet", ...)} to load all sets. That search triggers
     *       this aspect via AOP, which needs the cache to compute blocked
     *       fields → cycle. {@code @Lazy} hands the aspect a proxy at
     *       construction time, so the actual cache method call happens
     *       AFTER both beans are fully initialized.</li>
     * </ul>
     */
    public FieldFilterAspect(
            @Lazy PermissionInfoEnricher permissionInfoEnricher,
            @Lazy SensitiveFieldSetCache sfsCache) {
        this.permissionInfoEnricher = permissionInfoEnricher;
        this.sfsCache = sfsCache;
    }

    /* ------------------------------------------------------------------ */
    /* search* — PRE + POST                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Catches every {@code search*} overload taking {@code (String
     * modelName, FlexQuery flexQuery, ...)}. PRE strips fields from the
     * query; POST walks the returned rows + nested related Maps.
     */
    @Around(
            "execution(* io.softa.framework.orm.service.ModelService.search*(..)) "
                    + "&& args(modelName, flexQuery, ..)"
    )
    public Object onSearch(ProceedingJoinPoint pjp, String modelName, FlexQuery flexQuery)
            throws Throwable {
        Resolved r = resolve(modelName);
        if (r == null) return pjp.proceed();    // short-circuited
        try {
            preFilterFields(modelName, flexQuery, r.blockedTopLevel);
            Object result = pjp.proceed();
            postMask(result, modelName, r.context);
            return result;
        } catch (Throwable t) {
            log.warn("FieldFilterAspect[search] failure (model={}), proceeding without filter",
                    modelName, t);
            return pjp.proceed();
        }
    }

    /* ------------------------------------------------------------------ */
    /* getById / getByIds — POST only (no FlexQuery to strip)              */
    /* ------------------------------------------------------------------ */

    @Around(
            "execution(* io.softa.framework.orm.service.ModelService.getById(..)) "
                    + "&& args(modelName, ..)"
    )
    public Object onGetById(ProceedingJoinPoint pjp, String modelName) throws Throwable {
        Resolved r = resolve(modelName);
        if (r == null) return pjp.proceed();
        try {
            Object result = pjp.proceed();
            postMask(result, modelName, r.context);
            return result;
        } catch (Throwable t) {
            log.warn("FieldFilterAspect[getById] failure (model={}), proceeding without filter",
                    modelName, t);
            return pjp.proceed();
        }
    }

    @Around(
            "execution(* io.softa.framework.orm.service.ModelService.getByIds(..)) "
                    + "&& args(modelName, ..)"
    )
    public Object onGetByIds(ProceedingJoinPoint pjp, String modelName) throws Throwable {
        Resolved r = resolve(modelName);
        if (r == null) return pjp.proceed();
        try {
            Object result = pjp.proceed();
            postMask(result, modelName, r.context);
            return result;
        } catch (Throwable t) {
            log.warn("FieldFilterAspect[getByIds] failure (model={}), proceeding without filter",
                    modelName, t);
            return pjp.proceed();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Short-circuit + resolve the per-request mask context once. Returns
     * null when the caller is anonymous / super-admin / skip-permission
     * is set / context is missing.
     */
    private Resolved resolve(String modelName) {
        Context ctx = ContextHolder.getContext();
        if (ctx == null || ctx.isSkipPermissionCheck()) return null;
        if (ctx.getUserId() == null) return null;
        PermissionInfo pi;
        try {
            pi = permissionInfoEnricher.enrich(ctx.getTenantId(), ctx.getUserId());
        } catch (Throwable t) {
            log.warn("FieldFilterAspect — enrich failed for user={}", ctx.getUserId(), t);
            return null;
        }
        if (PermissionInfo.isSuperAdmin(pi)) return null;
        Set<String> blocked = computeBlocked(modelName, pi);
        return new Resolved(blocked, new MaskContext(pi));
    }

    /**
     * Modify {@code flexQuery.fields} to drop blocked columns. When the
     * caller supplied no field list, expand to {@code (stored − blocked)}
     * so the framework's default "SELECT all stored" path doesn't pull in
     * sensitive columns either.
     */
    private void preFilterFields(String modelName, FlexQuery flexQuery, Set<String> blocked) {
        if (blocked.isEmpty() || flexQuery == null) return;
        List<String> requested = flexQuery.getFields();
        if (requested == null || requested.isEmpty()) {
            List<String> stored;
            try {
                stored = ModelManager.getModelStoredFields(modelName);
            } catch (Throwable t) {
                log.warn("FieldFilterAspect — getModelStoredFields failed (model={})", modelName, t);
                return;
            }
            List<String> filtered = new ArrayList<>(stored.size());
            for (String f : stored) if (!blocked.contains(f)) filtered.add(f);
            flexQuery.setFields(filtered);
        } else {
            List<String> filtered = new ArrayList<>(requested.size());
            for (String f : requested) if (!blocked.contains(f)) filtered.add(f);
            flexQuery.setFields(filtered);
        }
    }

    /**
     * Walk the result tree applying per-model blocked-field sets. Handles
     * the shapes the read methods return: {@code Optional}, {@code Page},
     * {@code List}, {@code ApiResponse}, {@code Map}. Recurses into
     * cascade-inlined related Maps so a sensitive field on a nested model
     * doesn't leak through (even though PRE only touches the top model).
     */
    @SuppressWarnings("unchecked")
    private void postMask(Object body, String modelName, MaskContext ctx) {
        if (body == null || modelName == null) return;
        if (body instanceof Optional<?> opt) {
            opt.ifPresent(v -> postMask(v, modelName, ctx));
            return;
        }
        if (body instanceof ApiResponse<?>) {
            postMask(((ApiResponse<?>) body).getData(), modelName, ctx);
            return;
        }
        if (body instanceof Page<?> page) {
            postMask(page.getRows(), modelName, ctx);
            return;
        }
        if (body instanceof Collection<?> col) {
            for (Object el : col) postMask(el, modelName, ctx);
            return;
        }
        if (body instanceof Map<?, ?>) {
            Map<String, Object> row = (Map<String, Object>) body;
            Set<String> blocked = ctx.fieldsToMaskFor(modelName);
            if (!blocked.isEmpty()) {
                for (String f : blocked) {
                    if (row.containsKey(f)) row.put(f, null);
                }
            }
            // Recurse into TO_ONE cascaded child rows. Inlined related-
            // model objects on a parent row (e.g. Employee.empBankAccount
            // as a full Map) need masking under THEIR own model's rules,
            // not the parent's. Resolve via metadata and switch context.
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                if (value == null) continue;
                if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
                    String relatedModel = ModelManager.resolveRelatedModel(modelName, entry.getKey());
                    if (relatedModel != null) postMask(value, relatedModel, ctx);
                }
            }
        }
    }

    private Set<String> computeBlocked(String modelName, PermissionInfo pi) {
        Set<String> grantedSetIds = pi.getModelSensitiveFieldSetsMap() == null
                ? Set.of()
                : pi.getModelSensitiveFieldSetsMap().getOrDefault(modelName, Set.of());
        return sfsCache.computeForbiddenFields(modelName, grantedSetIds);
    }

    /** Resolved short-circuit gate + per-request mask context. */
    private record Resolved(Set<String> blockedTopLevel, MaskContext context) {}

    /** Per-request memoization of (modelName → fields-to-mask). Same
     *  shape as the old advice's MaskContext: a Page of N rows on the
     *  same model computes the mask set once, nested model mask sets
     *  also cached across rows in the same response. */
    private final class MaskContext {
        private final PermissionInfo pi;
        private final Map<String, Set<String>> byModel = new HashMap<>();

        MaskContext(PermissionInfo pi) {
            this.pi = pi;
        }

        Set<String> fieldsToMaskFor(String model) {
            Set<String> cached = byModel.get(model);
            if (cached != null) return cached;
            Set<String> mask = computeBlocked(model, pi);
            // Wrap in HashSet to avoid mutating the cache's referenced set
            // via the row.put(f, null) loop above — defensive.
            byModel.put(model, new HashSet<>(mask));
            return byModel.get(model);
        }
    }
}
