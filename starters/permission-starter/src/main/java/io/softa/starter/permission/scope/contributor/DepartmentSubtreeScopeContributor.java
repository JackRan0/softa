package io.softa.starter.permission.scope.contributor;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;
import io.softa.starter.permission.scope.DepartmentCascadePathResolver;
import io.softa.starter.permission.scope.DepartmentIdPathResolver;
import io.softa.starter.permission.scope.IdPath;
import io.softa.starter.permission.spi.ScopeContributor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ScopeType#DEPT_SUBTREE} — rows under a department root and every
 * descendant. The root is the admin-fixed {@code scopeExpr.deptId} when
 * present, otherwise the caller's OWN department from
 * {@code ContextHolder.getContext().getEmpInfo().getDeptId()} ("Own department
 * subtree"). Same static-then-dynamic fallback as {@link ManagedDepartmentsScopeContributor}.
 *
 * <p>Resolves {@code deptId → idPath} in the application layer via
 * {@link DepartmentIdPathResolver}, then emits an OR of an equality on the
 * root itself and a {@code CHILD_OF} (LIKE prefix) on its descendants —
 * the split matters because idPath segments have no trailing separator, so
 * {@code LIKE 'idPath%'} alone would also match unrelated siblings whose
 * path shares the same numeric prefix (e.g. {@code 1/12} matching
 * {@code 1/120}). A model that holds a department id directly instead of relating
 * to Department ({@link DepartmentCascadePathResolver#resolveIdColumn}) is filtered
 * by the ids of the subtree's departments instead.
 */
@Component
public class DepartmentSubtreeScopeContributor implements ScopeContributor {

    private static final String DEPT_FIELD = "departmentId";
    private static final String SCOPE_EXPR_DEPT_ID = "deptId";

    private final DepartmentCascadePathResolver cascadePath;
    private final DepartmentIdPathResolver idPathResolver;

    public DepartmentSubtreeScopeContributor(
            DepartmentCascadePathResolver cascadePath,
            DepartmentIdPathResolver idPathResolver) {
        this.cascadePath = cascadePath;
        this.idPathResolver = idPathResolver;
    }

    @Override
    public ScopeType scopeType() {
        return ScopeType.DEPT_SUBTREE;
    }

    @Override
    public Filters compile(ScopeRule rule, String modelName) {
        // Root department: the admin-fixed scopeExpr.deptId when present, else
        // the caller's OWN department from the request context ("Own department
        // subtree"). Same static-then-dynamic fallback as MANAGED_DEPARTMENTS.
        Long rootId = staticRootId(rule);
        if (rootId == null) {
            EmpInfo info = ContextHolder.getContext().getEmpInfo();
            rootId = info == null ? null : info.getDeptId();
        }
        if (rootId == null) return new Filters();

        Optional<String> path = cascadePath.resolve(modelName);
        Optional<String> idColumn = path.isPresent() ? Optional.empty() : cascadePath.resolveIdColumn(modelName);
        if (path.isEmpty() && idColumn.isEmpty()) {
            throw new IllegalStateException(
                    "DEPT_SUBTREE on model '" + modelName + "' but DepartmentCascadePathResolver "
                            + "returned no path. Declare the model's dept anchor as a "
                            + "ToOne to Department in metadata (or, on a model that cannot "
                            + "relate to Department, a plain department-id column), or remove "
                            + "DEPT_SUBTREE from this model's scope rules.");
        }

        // Resolve deptId → idPath from the cached department tree. Empty
        // Optional = unknown / soft-deleted / cross-tenant dept → fail-closed.
        Optional<String> rootPathOpt = idPathResolver.idPathOf(rootId);
        if (rootPathOpt.isEmpty()) return new Filters();
        String rootPath = rootPathOpt.get();
        if (idColumn.isPresent()) {
            return idsIn(idColumn.get(), idPathResolver.subtreeIdsOf(List.of(rootPath)));
        }
        String field = DepartmentCascadePathResolver.idPathField(path.get());

        return IdPath.subtreeOf(field, rootPath);
    }

    /**
     * A department-id column restricted to these departments; empty — the enclosing rule fail-closed — when
     * there are none.
     */
    static Filters idsIn(String idColumn, Set<Long> deptIds) {
        return deptIds.isEmpty() ? new Filters() : new Filters().in(idColumn, List.copyOf(deptIds));
    }

    /** Admin-fixed root from {@code scopeExpr.deptId}, or null when absent —
     *  the caller then falls back to the principal's own department. */
    private static Long staticRootId(ScopeRule rule) {
        JsonNode deptNode = rule.scopeExprField(SCOPE_EXPR_DEPT_ID);
        if (deptNode == null || !deptNode.isString()) return null;
        return parseDeptId(deptNode.asString());
    }

    /** Parse Department's numeric id, fail-closed on non-numeric input. */
    static Long parseDeptId(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try { return Long.valueOf(raw); } catch (NumberFormatException ex) { return null; }
    }
}
