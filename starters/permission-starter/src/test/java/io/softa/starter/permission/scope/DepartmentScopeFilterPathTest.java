package io.softa.starter.permission.scope;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.scope.contributor.DepartmentSubtreeScopeContributor;
import io.softa.starter.permission.scope.contributor.ManagedDepartmentsScopeContributor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression lock for the OvertimeRequest bug: the department-scope contributors
 * must compile their filter on the resolved cascade path
 * ({@code employeeId.departmentId.idPath}), NOT a bare {@code departmentId.idPath}
 * — the latter renders SQL against the non-existent overtime_request.department_id
 * column. Pairs with {@link DefaultDepartmentCascadePathResolverTest} (which
 * proves the resolver returns the cascade path for a dynamic departmentId).
 */
class DepartmentScopeFilterPathTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String EXPECTED_FIELD = "employeeId.departmentId.idPath";

    private final DepartmentCascadePathResolver cascade = mock(DepartmentCascadePathResolver.class);
    private final DepartmentIdPathResolver idPath = mock(DepartmentIdPathResolver.class);

    @Test
    void deptSubtree_usesResolvedCascadePathInFilter() {
        when(cascade.resolve("OvertimeRequest")).thenReturn(Optional.of("employeeId.departmentId"));
        when(idPath.idPathOf(5L)).thenReturn(Optional.of("1/5"));

        ObjectNode expr = JSON.objectNode();
        expr.put("deptId", "5"); // static root → no ContextHolder needed
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(expr);

        Filters filters = new DepartmentSubtreeScopeContributor(cascade, idPath)
                .compile(rule, "OvertimeRequest");

        assertThat(filters.toString()).contains(EXPECTED_FIELD);
    }

    @Test
    void managedDepartments_usesResolvedCascadePathInFilter() {
        when(cascade.resolve("OvertimeRequest")).thenReturn(Optional.of("employeeId.departmentId"));
        when(idPath.idPathsOf(any())).thenReturn(List.of("1/5"));

        ArrayNode deptIds = JSON.arrayNode();
        deptIds.add("5");
        ObjectNode expr = JSON.objectNode();
        expr.set("deptIds", deptIds); // static roots → no ContextHolder needed
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(expr);

        Filters filters = new ManagedDepartmentsScopeContributor(cascade, idPath)
                .compile(rule, "OvertimeRequest");

        assertThat(filters.toString()).contains(EXPECTED_FIELD);
    }

    /**
     * Department scoped against itself: the field is a bare {@code idPath}, never {@code .idPath}.
     * A leading dot reads as a well-formed cascade path and matches nothing, so the failure would
     * be an empty department list rather than an error.
     */
    @Test
    void departmentItself_filtersOnItsOwnIdPath() {
        when(cascade.resolve("Department"))
                .thenReturn(Optional.of(DepartmentCascadePathResolver.SELF_PATH));
        when(idPath.idPathOf(5L)).thenReturn(Optional.of("1/5"));
        when(idPath.idPathsOf(any())).thenReturn(List.of("1/5"));

        ObjectNode subtreeExpr = JSON.objectNode();
        subtreeExpr.put("deptId", "5");
        ScopeRule subtreeRule = new ScopeRule();
        subtreeRule.setScopeExpr(subtreeExpr);

        ArrayNode deptIds = JSON.arrayNode();
        deptIds.add("5");
        ObjectNode managedExpr = JSON.objectNode();
        managedExpr.set("deptIds", deptIds);
        ScopeRule managedRule = new ScopeRule();
        managedRule.setScopeExpr(managedExpr);

        for (Filters filters : List.of(
                new DepartmentSubtreeScopeContributor(cascade, idPath).compile(subtreeRule, "Department"),
                new ManagedDepartmentsScopeContributor(cascade, idPath).compile(managedRule, "Department"))) {
            assertThat(filters.toString()).contains("idPath").doesNotContain(".idPath");
        }
    }

    private static ScopeRule subtreeRule(String deptId) {
        ObjectNode expr = JSON.objectNode();
        expr.put("deptId", deptId);
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(expr);
        return rule;
    }

    private static ScopeRule managedRule(String deptId) {
        ArrayNode deptIds = JSON.arrayNode();
        deptIds.add(deptId);
        ObjectNode expr = JSON.objectNode();
        expr.set("deptIds", deptIds);
        ScopeRule rule = new ScopeRule();
        rule.setScopeExpr(expr);
        return rule;
    }

    /**
     * A model that holds a department id instead of relating to Department (a framework model such as
     * FlowInstance) is filtered by the ids of the departments in range — no join to idPath it has no way to make.
     */
    @Test
    void aDepartmentIdColumnIsFilteredByTheIdsOfTheDepartmentsInRange() {
        when(cascade.resolve("FlowInstance")).thenReturn(Optional.empty());
        when(cascade.resolveIdColumn("FlowInstance")).thenReturn(Optional.of("departmentId"));
        when(idPath.idPathOf(5L)).thenReturn(Optional.of("1/5"));
        when(idPath.idPathsOf(any())).thenReturn(List.of("1/5"));
        when(idPath.subtreeIdsOf(List.of("1/5"))).thenReturn(Set.of(5L, 7L));

        for (Filters filters : List.of(
                new DepartmentSubtreeScopeContributor(cascade, idPath).compile(subtreeRule("5"), "FlowInstance"),
                new ManagedDepartmentsScopeContributor(cascade, idPath).compile(managedRule("5"), "FlowInstance"))) {
            assertThat(filters.toString()).contains("departmentId").contains("IN").contains("5").contains("7")
                    .doesNotContain("idPath");
        }
    }

    @Test
    void noDepartmentInRangeLeavesTheRuleFailClosed() {
        when(cascade.resolve("FlowInstance")).thenReturn(Optional.empty());
        when(cascade.resolveIdColumn("FlowInstance")).thenReturn(Optional.of("departmentId"));
        when(idPath.idPathOf(5L)).thenReturn(Optional.of("1/5"));
        when(idPath.subtreeIdsOf(any())).thenReturn(Set.of());

        Filters filters = new DepartmentSubtreeScopeContributor(cascade, idPath).compile(subtreeRule("5"), "FlowInstance");

        assertThat(Filters.isEmpty(filters)).isTrue();
    }

    @Test
    void aModelWithNeitherAPathNorAnIdColumnIsAConfigurationError() {
        when(cascade.resolve("Orphan")).thenReturn(Optional.empty());
        when(cascade.resolveIdColumn("Orphan")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new DepartmentSubtreeScopeContributor(cascade, idPath).compile(subtreeRule("5"), "Orphan"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ManagedDepartmentsScopeContributor(cascade, idPath).compile(managedRule("5"), "Orphan"))
                .isInstanceOf(IllegalStateException.class);
    }
}
