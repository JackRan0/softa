package io.softa.starter.permission.spi.support;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.RoleGrant;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The snapshot keeps each role's grant pair apart, beside the unions the menu plane reads — and a
 * condition rides with the role and model it was written for.
 */
class RoleGrantSnapshotTest {

    private static final Long TENANT = 7L;
    private static final Long USER = 42L;

    private static <T> T view(Class<T> type, Map<String, Object> values) {
        return JsonUtils.stringToObject(JsonUtils.objectToString(values), type);
    }

    private static JsonNode json(String text) {
        return JsonUtils.stringToObject(text, JsonNode.class);
    }

    @SuppressWarnings("unchecked")
    private static PermissionInfo build() {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 1)),
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 2))));
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleView.class,
                                Map.of("id", 1, "name", "Directory", "active", true)),
                        view(DefaultPermissionSnapshotProvider.RoleView.class,
                                Map.of("id", 2, "name", "Department HR", "active", true))));
        when(modelService.searchList(eq("RoleNavigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 1, "navigationId", "nav.employee",
                                "permissionIds", List.of("employee.view"))),
                        view(DefaultPermissionSnapshotProvider.RoleNavigationView.class, Map.of(
                                "roleId", 2, "navigationId", "nav.employee",
                                "permissionIds", List.of("employee.view", "employee.update")))));
        when(modelService.searchList(eq("RoleDataScope"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleDataScopeView.class, Map.of(
                                "roleId", 1, "model", "Employee",
                                "dataScopes", List.of(Map.of("scopeType", "ALL")))),
                        view(DefaultPermissionSnapshotProvider.RoleDataScopeView.class, Map.of(
                                "roleId", 2, "model", "Employee",
                                "dataScopes", List.of(Map.of("scopeType", "MANAGED_DEPARTMENTS")),
                                "scopeCondition", List.of(List.of(
                                        "employeeProfileId.residenceStatus", "!=", "SG_EmploymentPass"))))));
        when(modelService.searchList(eq("RoleSensitiveFieldSet"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(view(DefaultPermissionSnapshotProvider.RoleSfsView.class,
                        Map.of("roleId", 2, "sensitiveFieldSetId", "ipa-details"))));
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        SensitiveFieldSetCache sets = mock(SensitiveFieldSetCache.class);
        when(sets.modelOf("ipa-details")).thenReturn("Employee");
        return new DefaultPermissionSnapshotProvider(null, modelService, sets, () -> null, List.of(), List.of())
                .doLoadFromDb(TENANT, USER);
    }

    @Test
    void eachRoleKeepsItsOwnActionsScopeAndSensitiveSets() {
        PermissionInfo info = build();

        assertThat(info.getRoleGrants()).hasSize(2);
        RoleGrant directory = info.getRoleGrants().get(0);
        RoleGrant departmentHr = info.getRoleGrants().get(1);

        assertThat(directory.getRoleName()).isEqualTo("Directory");
        assertThat(directory.getPermissions()).containsExactly("employee.view");
        assertThat(directory.getModelScopeMap().get("Employee"))
                .extracting(r -> r.getScopeType()).containsExactly(ScopeType.ALL);
        assertThat(directory.getModelSensitiveFieldSetsMap()).isEmpty();

        assertThat(departmentHr.getPermissions()).containsExactlyInAnyOrder("employee.view", "employee.update");
        assertThat(departmentHr.getModelScopeMap().get("Employee"))
                .extracting(r -> r.getScopeType()).containsExactly(ScopeType.MANAGED_DEPARTMENTS);
        assertThat(departmentHr.getModelSensitiveFieldSetsMap().get("Employee")).containsExactly("ipa-details");
    }

    @Test
    void theConditionRidesWithItsRoleAndModel() {
        PermissionInfo info = build();

        assertThat(info.getRoleGrants().get(0).getModelScopeConditions()).isEmpty();
        assertThat(info.getRoleGrants().get(1).getModelScopeConditions().get("Employee"))
                .isEqualTo(json("[[\"employeeProfileId.residenceStatus\",\"!=\",\"SG_EmploymentPass\"]]"));
    }

    @Test
    void theUnionsStayForTheMenuPlane() {
        PermissionInfo info = build();

        assertThat(info.getPermissions()).containsExactlyInAnyOrder("employee.view", "employee.update");
        assertThat(info.getModelScopeMap().get("Employee"))
                .extracting(r -> r.getScopeType())
                .containsExactlyInAnyOrder(ScopeType.ALL, ScopeType.MANAGED_DEPARTMENTS);
        assertThat(info.getModelSensitiveFieldSetsMap().get("Employee")).containsExactly("ipa-details");
    }

    // ---- "my companies" across roles ------------------------------------

    /** Role 1 and role 2 with the given company rules (null = no rule on the company model). */
    @SuppressWarnings("unchecked")
    private static PermissionInfo buildWithCompanies(List<Integer> role1Companies, List<Integer> role2Companies) {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("UserAccount"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("UserRoleRel"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 1)),
                        view(DefaultPermissionSnapshotProvider.UserRoleRelView.class, Map.of("roleId", 2))));
        when(modelService.searchList(eq("Role"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of(
                        view(DefaultPermissionSnapshotProvider.RoleView.class, Map.of("id", 1, "active", true)),
                        view(DefaultPermissionSnapshotProvider.RoleView.class, Map.of("id", 2, "active", true))));
        when(modelService.searchList(eq("RoleNavigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        List<DefaultPermissionSnapshotProvider.RoleDataScopeView> scopes = new java.util.ArrayList<>();
        if (role1Companies != null) scopes.add(companyScope(1, role1Companies));
        if (role2Companies != null) scopes.add(companyScope(2, role2Companies));
        when(modelService.searchList(eq("RoleDataScope"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(scopes);
        when(modelService.searchList(eq("RoleSensitiveFieldSet"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        when(modelService.searchList(eq("Navigation"), any(FlexQuery.class), any(Class.class)))
                .thenReturn(List.of());
        // Companies 101 (SG), 202 (MY) and 303 (ID); a query is answered by the ids its filter names.
        Map<Long, String> companies = Map.of(101L, "SG", 202L, "MY", 303L, "ID");
        when(modelService.searchList(eq(ModelConstant.COMPANY_MODEL), any(FlexQuery.class))).thenAnswer(inv -> {
            FlexQuery query = inv.getArgument(1);
            String filter = query.getFilters() == null ? "" : query.getFilters().toString();
            boolean all = query.getFilters() == null || Filters.isEmpty(query.getFilters());
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            companies.forEach((id, country) -> {
                if (all || filter.contains(id.toString())) {
                    rows.add(Map.of(ModelConstant.ID, id, ModelConstant.COUNTRY_FIELD, country));
                }
            });
            return rows;
        });
        ScopeRuleCompiler compiler = mock(ScopeRuleCompiler.class);
        when(compiler.compile(anyList(), eq(ModelConstant.COMPANY_MODEL))).thenAnswer(inv -> {
            List<ScopeRule> rules = inv.getArgument(0);
            List<Long> ids = new java.util.ArrayList<>();
            rules.forEach(r -> r.getScopeExpr().forEach(n -> ids.add(n.asLong())));
            return Filters.of(ModelConstant.ID, Operator.IN, ids);
        });
        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.existModel(ModelConstant.COMPANY_MODEL)).thenReturn(true);
            return new DefaultPermissionSnapshotProvider(null, modelService, mock(SensitiveFieldSetCache.class),
                    () -> compiler, List.of(), List.of()).doLoadFromDb(TENANT, USER);
        }
    }

    private static DefaultPermissionSnapshotProvider.RoleDataScopeView companyScope(int roleId, List<Integer> ids) {
        return view(DefaultPermissionSnapshotProvider.RoleDataScopeView.class, Map.of(
                "roleId", roleId, "model", ModelConstant.COMPANY_MODEL,
                "dataScopes", List.of(Map.of("scopeType", "CUSTOM", "scopeExpr", ids))));
    }

    @Test
    void aRoleWithoutACompanyRuleMakesMyCompaniesUnrestricted() {
        PermissionInfo info = buildWithCompanies(List.of(101), null);

        assertThat(info.getRoleGrants().get(0).getGrantedCompanyIds()).containsExactly(101L);
        assertThat(info.getRoleGrants().get(1).getGrantedCompanyIds()).isNull();
        // Role 2 reaches every company on the data plane, so "my companies" is not just company 101 —
        // and "my countries" is every country the tenant has a company in.
        assertThat(info.getGrantedCompanyIds()).isNull();
        assertThat(info.getGrantedCountries()).containsExactlyInAnyOrder("SG", "MY", "ID");
    }

    @Test
    void twoRestrictedRolesGiveTheUnionOfTheirCompanies() {
        PermissionInfo info = buildWithCompanies(List.of(101), List.of(202));

        assertThat(info.getRoleGrants().get(0).getGrantedCompanyIds()).containsExactly(101L);
        assertThat(info.getRoleGrants().get(1).getGrantedCompanyIds()).containsExactly(202L);
        assertThat(info.getGrantedCompanyIds()).containsExactlyInAnyOrder(101L, 202L);
        assertThat(info.getGrantedCountries()).containsExactlyInAnyOrder("SG", "MY");
    }

    @Test
    void aRoleConfiguredToReachNoCompanyAddsNothing() {
        PermissionInfo info = buildWithCompanies(List.of(101), List.of());

        assertThat(info.getRoleGrants().get(1).getGrantedCompanyIds()).isEmpty();
        assertThat(info.getGrantedCompanyIds()).containsExactly(101L);
        assertThat(info.getGrantedCountries()).containsExactly("SG");
    }
}
