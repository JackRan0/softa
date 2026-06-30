package io.softa.starter.user.filter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.service.PermissionInfoEnricher;

/**
 * Layer D — write-side sensitive field guard.
 *
 * <p>Complements {@link FieldFilter} (Layer C, response-side masking) by
 * rejecting writes that touch sensitive fields the caller hasn't been
 * granted. Without this, an admin with {@code Employee.update} could
 * curl {@code POST /Employee/updateOne} with a body containing
 * {@code empBankAccountId: { accountNumber: "..." }} and silently rewrite
 * banking data they're forbidden from even reading. Layer C only masks
 * the response — payload acceptance is the gap this aspect closes.
 *
 * <p>Pointcut wraps every {@code ModelService} write entry point:
 * {@code createOne / createList / updateOne / updateList} (plus the
 * {@code *AndFetch} variants — they go through the same first arg shape).
 *
 * <h3>Nested FK traversal</h3>
 * Payloads frequently inline related entities through a ManyToOne or
 * OneToOne FK field — e.g. {@code Employee.empBankAccountId} carries the
 * full {@code EmpBankAccount} object on create. The aspect resolves each
 * payload key against the model's {@link MetaField} list; when the key
 * is a {@code TO_ONE_TYPES} field and the value is a Map, recurse with
 * {@code relatedModel} as the new target. This is the same nested-shape
 * problem documented as a TODO on FieldFilter's read path; here we have
 * the writer's payload up front so traversal is straightforward.
 *
 * <h3>Bypass conditions (proceed without checks)</h3>
 * <ul>
 *   <li>{@code ctx.skipPermissionCheck=true} — internal service-to-service
 *       calls under {@code @SkipPermissionCheck} (e.g. PermissionInfoEnricher's
 *       own probes).</li>
 *   <li>{@code ctx.userId == null} — anonymous (PermissionInterceptor
 *       would have rejected before this point; defensive bypass).</li>
 *   <li>SUPER_ADMIN — same short-circuit pattern as Layers A/B/C.</li>
 *   <li>Model has zero sensitive fields registered — fast-path so models
 *       without any SFS pay no cost.</li>
 * </ul>
 *
 * <h3>Failure mode</h3>
 * Fail LOUDLY: throws {@link PermissionException} with a clear message
 * naming the offending field path. Strip-silently was considered but
 * rejected — silent reject masks FE bugs and gives a misleading "update
 * succeeded" signal when the sensitive field stayed unchanged. The FE
 * already gates these flows (Banking section hidden when no SFS grant),
 * so this exception path only triggers for direct API access or genuine
 * UI bugs — both should be surfaced.
 *
 * <h3>Aspect order</h3>
 * Runs INSIDE {@link io.softa.starter.user.scope.ScopeFilterAspect}'s
 * pointcut (different target methods — scope is on {@code search*},
 * write-guard is on {@code create*}/{@code update*}). No ordering
 * conflict; PermissionInterceptor already gated the endpoint upstream.
 */
@Slf4j
@Aspect
@Component
public class FieldWriteGuardAspect {

    private final PermissionInfoEnricher permissionInfoEnricher;
    private final SensitiveFieldSetCache sfsCache;

    /**
     * Explicit constructor — both deps are {@code @Lazy}:
     * <ul>
     *   <li>{@code PermissionInfoEnricher} — same chain as
     *       {@code ScopeFilterAspect}: enricher uses ModelService
     *       transitively, and ModelService is aspect-wrapped, so without
     *       {@code @Lazy} we'd cycle at startup.</li>
     *   <li>{@code SensitiveFieldSetCache} — mirrors {@link FieldFilterAspect}'s
     *       {@code @Lazy} on the same dep. This aspect <em>currently</em>
     *       intercepts only write methods (create/update/delete), so the
     *       cache's {@code @PostConstruct} {@code searchList} call doesn't
     *       trigger this aspect today — no real cycle. But the lazy proxy
     *       is cheap and guards against (a) future changes that extend
     *       this aspect's pointcut to cover reads, (b) any future caller
     *       that invokes {@code SensitiveFieldSetCache.reload()} at
     *       runtime from a code path that has write-aspect interceptors.</li>
     * </ul>
     */
    public FieldWriteGuardAspect(
            @Lazy PermissionInfoEnricher permissionInfoEnricher,
            @Lazy SensitiveFieldSetCache sfsCache) {
        this.permissionInfoEnricher = permissionInfoEnricher;
        this.sfsCache = sfsCache;
    }

    /**
     * Pointcut #1 — covers write methods whose payload is the SECOND
     * positional argument:
     *
     *   create{One,List}{,AndFetch} (String, Map | List<Map>, ...)
     *   update{One,List}{,AndFetch} (String, Map | List<Map>, ...)
     *   createOrUpdate              (String, List<Map>, ...)
     *   updateByBusinessKey         (String, Map)
     *   updateByExternalId          (String, List<Map>)
     *
     * All share the {@code (String modelName, <payload>, ...)} arg shape,
     * so a single {@code args(modelName, payload, ..)} binding catches
     * them. Per-method execution() OR-chain is explicit (rather than
     * {@code execution(* ModelService.*(..))} with a name filter) to
     * keep search/read methods out of scope.
     */
    @Around(
            "(execution(* io.softa.framework.orm.service.ModelService.createOne(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.createOneAndFetch(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.createList(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.createListAndFetch(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.createOrUpdate(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.updateOne(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.updateOneAndFetch(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.updateList(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.updateListAndFetch(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.updateByBusinessKey(..)) "
                    + "|| execution(* io.softa.framework.orm.service.ModelService.updateByExternalId(..))) "
                    + "&& args(modelName, payload, ..)"
    )
    public Object guardWrites(ProceedingJoinPoint pjp, String modelName, Object payload)
            throws Throwable {
        return runGuarded(pjp, modelName, payload);
    }

    /**
     * Pointcut #2 — covers {@code updateByFilter(String modelName,
     * Filters filters, Map<String, Object> value)} whose payload Map is
     * the THIRD positional argument. Needs its own binding because the
     * second arg here is the filter expression, not the field map.
     *
     * <p>{@code updateByFilter} is the most attack-surface-heavy write:
     * a single call rewrites the named fields on EVERY row matching the
     * filter (no row-level scope check, because Layer B only wraps
     * {@code search*}). Layer D's job here is at least to make sure the
     * <i>fields being written</i> are ones the caller is allowed to write.
     * Row-level write authorization remains a P1 gap noted in the PRD.
     */
    @Around(
            "execution(* io.softa.framework.orm.service.ModelService.updateByFilter(..)) "
                    + "&& args(modelName, filters, value)"
    )
    public Object guardUpdateByFilter(ProceedingJoinPoint pjp,
                                       String modelName,
                                       Object filters,
                                       Object value) throws Throwable {
        return runGuarded(pjp, modelName, value);
    }

    /** Common skip / SUPER_ADMIN / try-catch wrapper. */
    private Object runGuarded(ProceedingJoinPoint pjp, String modelName, Object payload)
            throws Throwable {
        Context ctx = ContextHolder.getContext();
        // Internal bypass for @SkipPermissionCheck-marked code paths and
        // anonymous flows — let them through unchanged.
        if (ctx == null || ctx.isSkipPermissionCheck() || ctx.getUserId() == null) {
            return pjp.proceed();
        }
        // SUPER_ADMIN bypass — same as every other layer.
        PermissionInfo pi = permissionInfoEnricher.enrich(ctx.getTenantId(), ctx.getUserId());
        if (PermissionInfo.isSuperAdmin(pi)) {
            return pjp.proceed();
        }

        try {
            checkPayload(modelName, payload, pi, "");
        } catch (PermissionException e) {
            throw e;  // pass through
        } catch (Throwable t) {
            // Fail-OPEN on aspect-internal errors (meta lookup failures,
            // unexpected payload shapes). Log loudly so ops can detect
            // misconfiguration. The real fail-CLOSED defence is the
            // grant-time check + Layer C; if those are correctly set
            // up, this aspect is the polish layer, not the only barrier.
            log.warn("FieldWriteGuardAspect — internal error inspecting payload on model={}; allowing the write through", modelName, t);
        }
        return pjp.proceed();
    }

    /** Dispatch on payload shape — Map (single row) or List<Map> (batch). */
    @SuppressWarnings("unchecked")
    private void checkPayload(String modelName, Object payload, PermissionInfo pi, String pathPrefix) {
        if (payload == null) return;
        if (payload instanceof Map<?, ?> row) {
            checkRow(modelName, (Map<String, Object>) row, pi, pathPrefix);
        } else if (payload instanceof List<?> rows) {
            int idx = 0;
            for (Object element : rows) {
                if (element instanceof Map<?, ?> r) {
                    checkRow(modelName, (Map<String, Object>) r, pi,
                            pathPrefix + "[" + idx + "]");
                }
                idx++;
            }
        }
        // Other payload shapes (POJOs, raw values) — ModelService doesn't
        // accept them on these signatures, so we don't have to handle.
    }

    /**
     * For one row of {@code modelName}, check that every key the caller
     * wrote either (a) isn't sensitive on this model, (b) is sensitive
     * AND covered by a granted set, or (c) is a TO_ONE FK whose nested
     * object recurses with the related model's rules.
     */
    private void checkRow(String modelName, Map<String, Object> row, PermissionInfo pi,
                          String pathPrefix) {
        if (row == null || row.isEmpty()) return;

        // Compute the "fields the user CANNOT write" set once per row.
        // Empty when the model has no SFS, or when the user holds every
        // applicable set — most legitimate calls land in one of those
        // and the per-key loop below short-circuits without lookups.
        Set<String> forbiddenFields = computeForbiddenFields(modelName, pi);

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();
            String path = pathPrefix.isEmpty() ? fieldName : pathPrefix + "." + fieldName;

            // Step 1: forbidden on THIS model? Reject before recursing,
            // so a write like `{ accountNumber: null }` to clear a field
            // is also blocked (clearing is a write).
            if (forbiddenFields.contains(fieldName)) {
                throw new PermissionException(String.format(
                        "Write blocked on sensitive field '%s' (model=%s, path=%s). "
                                + "User lacks the sensitive_field_set grant covering this field.",
                        fieldName, modelName, path));
            }

            // Step 2: if the field is a TO_ONE FK and the value is a
            // nested Map, recurse with the related model. Skips when
            // value is a scalar id (no nested object to check) or null.
            if (value instanceof Map<?, ?> nested) {
                String relatedModel = ModelManager.resolveRelatedModel(modelName, fieldName);
                if (relatedModel != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedRow = (Map<String, Object>) nested;
                    checkRow(relatedModel, nestedRow, pi, path);
                }
            }
            // Lists of nested rows (ManyToMany / OneToMany batch payloads)
            // — recurse per element. Each element gets the related model's
            // own forbidden-field check.
            else if (value instanceof List<?> nestedList) {
                String relatedModel = ModelManager.resolveRelatedModel(modelName, fieldName);
                if (relatedModel != null) {
                    int idx = 0;
                    for (Object element : nestedList) {
                        if (element instanceof Map<?, ?> nm) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> nestedRow = (Map<String, Object>) nm;
                            checkRow(relatedModel, nestedRow, pi, path + "[" + idx + "]");
                        }
                        idx++;
                    }
                }
            }
        }
    }

    /** Fields the user is NOT allowed to write on this model = all sensitive
     *  fields − granted fields. Same arithmetic as FieldFilter's mask diff;
     *  both layers share the same source of truth on
     *  {@link SensitiveFieldSetCache#computeForbiddenFields}. */
    private Set<String> computeForbiddenFields(String modelName, PermissionInfo pi) {
        Set<String> grantedSetIds = pi == null || pi.getModelSensitiveFieldSetsMap() == null
                ? null
                : pi.getModelSensitiveFieldSetsMap().get(modelName);
        return sfsCache.computeForbiddenFields(modelName, grantedSetIds);
    }

}
