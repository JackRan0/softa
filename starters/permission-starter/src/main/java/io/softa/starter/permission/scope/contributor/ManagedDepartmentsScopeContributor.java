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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ScopeType#MANAGED_DEPARTMENTS} — rows under any of a list of
 * department subtree roots, either static (rule's
 * {@code scopeExpr.deptIds}) or dynamic (current user's
 * {@code empInfo.managedDeptIds}).
 *
 * <p>Resolves each managed deptId → idPath in the application layer via
 * {@link DepartmentIdPathResolver}, then OR-merges the two-branch
 * (self + descendants) form of each subtree so the collision case
 * (e.g. {@code 1/12} vs. {@code 1/120}) is not conflated. A model that holds a
 * department id directly instead of relating to Department
 * ({@link DepartmentCascadePathResolver#resolveIdColumn}) is filtered by the ids
 * of the subtrees' departments instead.
 */
@Component
public class ManagedDepartmentsScopeContributor implements ScopeContributor {

    private static final String DEPT_FIELD = "departmentId";
    private static final String SCOPE_EXPR_DEPT_IDS = "deptIds";

    private final DepartmentCascadePathResolver cascadePath;
    private final DepartmentIdPathResolver idPathResolver;

    public ManagedDepartmentsScopeContributor(
            DepartmentCascadePathResolver cascadePath,
            DepartmentIdPathResolver idPathResolver) {
        this.cascadePath = cascadePath;
        this.idPathResolver = idPathResolver;
    }

    @Override
    public ScopeType scopeType() {
        return ScopeType.MANAGED_DEPARTMENTS;
    }

    @Override
    public Filters compile(ScopeRule rule, String modelName) {
        List<Long> rootIds = collectStaticRootIds(rule);
        if (rootIds.isEmpty()) {
            EmpInfo info = ContextHolder.getContext().getEmpInfo();
            Set<Long> dynamic = info == null ? null : info.getManagedDeptIds();
            if (dynamic == null || dynamic.isEmpty()) return new Filters();
            rootIds = new ArrayList<>(dynamic);
        }

        Optional<String> path = cascadePath.resolve(modelName);
        Optional<String> idColumn = path.isPresent() ? Optional.empty() : cascadePath.resolveIdColumn(modelName);
        if (path.isEmpty() && idColumn.isEmpty()) {
            throw new IllegalStateException(
                    "MANAGED_DEPARTMENTS on model '" + modelName + "' but "
                            + "DepartmentCascadePathResolver returned no path. Declare the "
                            + "model's dept anchor as a ToOne to Department in "
                            + "metadata (or, on a model that cannot relate to Department, a "
                            + "plain department-id column), or remove MANAGED_DEPARTMENTS "
                            + "from this model's scope rules.");
        }

        // Batch-resolve every managed deptId to its idPath. Unknown /
        // soft-deleted / cross-tenant ids drop out silently.
        List<String> resolvedPaths = idPathResolver.idPathsOf(rootIds);
        if (resolvedPaths.isEmpty()) return new Filters();
        if (idColumn.isPresent()) {
            return DepartmentSubtreeScopeContributor.idsIn(idColumn.get(), idPathResolver.subtreeIdsOf(resolvedPaths));
        }

        String field = DepartmentCascadePathResolver.idPathField(path.get());
        return IdPath.subtreesOf(field, resolvedPaths);
    }

    /** Static deptIds from {@code scopeExpr.deptIds} (rule-encoded string ids).
     *  Returns an empty list when the rule has no static deptIds — caller
     *  then falls back to the dynamic {@code empInfo.managedDeptIds} source. */
    private static List<Long> collectStaticRootIds(ScopeRule rule) {
        JsonNode arr = rule.scopeExprField(SCOPE_EXPR_DEPT_IDS);
        if (arr == null || !arr.isArray()) return Collections.emptyList();
        List<Long> ids = new ArrayList<>(arr.size());
        for (JsonNode el : arr) {
            if (!el.isString()) continue;
            Long parsed = DepartmentSubtreeScopeContributor.parseDeptId(el.asString());
            if (parsed != null) ids.add(parsed);
        }
        return ids;
    }
}
