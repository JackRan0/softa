package io.softa.starter.user.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.dto.NavConfigOptions.SfsRef;
import io.softa.starter.user.dto.RoleModelConfigOption;
import io.softa.starter.user.service.NavigationModelResolver;
import io.softa.starter.user.service.RoleGrantCeiling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NavigationConfigOptionsController#getModelOptions} — the
 * role-wizard data step's per-model option endpoint. Focus is the related-model
 * derivation (primary + L1/L2 lookup fan-out following ONLY ManyToOne/ManyToMany,
 * with dedup vs primary) and the per-model scope/SFS assembly.
 *
 * <p>Mirrors the {@code mockStatic(ModelManager)}+{@code MetaField} pattern used
 * by permission-starter's {@code EndpointIndexStandardDerivationTest}.
 */
class NavigationConfigOptionsControllerTest {

    private NavigationModelResolver navResolver;
    private ModelService<?> modelService;
    private RoleGrantCeiling roleGrantCeiling;
    private NavigationConfigOptionsController controller;

    @BeforeEach
    void setUp() {
        navResolver = mock(NavigationModelResolver.class);
        modelService = mock(ModelService.class);
        roleGrantCeiling = mock(RoleGrantCeiling.class);
        controller = new NavigationConfigOptionsController(navResolver, modelService, roleGrantCeiling);
        // The DataScopeType + SensitiveFieldSet reads default to empty (Mockito) →
        // only "ALL" scope and no SFS rows unless a test stubs the registry reads.
    }

    private static MetaField field(String name, FieldType type, String relatedModel) {
        MetaField mf = new MetaField();
        ReflectionTestUtils.setField(mf, "fieldName", name);
        ReflectionTestUtils.setField(mf, "fieldType", type);
        if (relatedModel != null) ReflectionTestUtils.setField(mf, "relatedModel", relatedModel);
        return mf;
    }

    private static Map<String, RoleModelConfigOption> byModel(List<RoleModelConfigOption> data) {
        return data.stream().collect(Collectors.toMap(RoleModelConfigOption::model, Function.identity()));
    }

    private static NavigationConfigOptionsController.DataScopeTypeView dst(String id) {
        var v = new NavigationConfigOptionsController.DataScopeTypeView();
        v.setId(id);
        v.setAppliesToAll(true);   // force applicable regardless of model field shape
        return v;
    }

    private static NavigationConfigOptionsController.DataScopeTypeView dstFilter(
            String id, JsonNode filter, String identityModel, JsonNode identityFilter) {
        var v = new NavigationConfigOptionsController.DataScopeTypeView();
        v.setId(id);
        v.setFilter(filter);
        v.setIdentityModel(identityModel);
        v.setIdentityFilter(identityFilter);
        return v;
    }

    private static NavigationConfigOptionsController.SensitiveFieldSetView sfsView(
            String id, String model, String name, JsonNode attachedTo) {
        var v = new NavigationConfigOptionsController.SensitiveFieldSetView();
        v.setId(id);
        v.setModel(model);
        v.setName(name);
        v.setAttachedTo(attachedTo);
        return v;
    }

    private static JsonNode arrayOf(String... vals) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        for (String v : vals) {
            a.add(v);
        }
        return a;
    }

    // ─────────────────────────── empty input ───────────────────────────

    @Test
    void nullOrEmptyNavIds_returnEmptyList() {
        assertThat(controller.getModelOptions(null).getData()).isEmpty();
        assertThat(controller.getModelOptions(List.of()).getData()).isEmpty();
    }

    // ─────────────────── relation-type filtering (L1) ───────────────────

    @Test
    void primaryPlusLookupRelated_onlyManyToOneAndManyToMany() {
        when(navResolver.resolvePrimaryModel("hr.employee")).thenReturn("Employee");

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Role")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("departmentId", FieldType.MANY_TO_ONE, "Department"),   // → related
                    field("roleIds", FieldType.MANY_TO_MANY, "Role"),             // → related
                    field("profileId", FieldType.ONE_TO_ONE, "EmpProfile"),       // excluded
                    field("addresses", FieldType.ONE_TO_MANY, "EmpAddress"),      // excluded
                    field("firstName", FieldType.STRING, null)));                 // excluded
            // L2 walk over Department/Role finds nothing further.
            mm.when(() -> ModelManager.getModelFields("Department")).thenReturn(List.of());
            mm.when(() -> ModelManager.getModelFields("Role")).thenReturn(List.of());

            List<RoleModelConfigOption> data = controller.getModelOptions(List.of("hr.employee")).getData();

            Map<String, RoleModelConfigOption> m = byModel(data);
            // Employee (primary) + Department + Role (ManyToOne/ManyToMany lookups).
            assertThat(m.keySet()).containsExactlyInAnyOrder("Employee", "Department", "Role");
            // OneToOne / OneToMany children NOT surfaced as their own models.
            assertThat(m).doesNotContainKeys("EmpProfile", "EmpAddress");
            // related flag: primary=false, derived lookups=true.
            assertThat(m.get("Employee").related()).isFalse();
            assertThat(m.get("Department").related()).isTrue();
            assertThat(m.get("Role").related()).isTrue();
        }
    }

    // ─────────────────────── L2 lookup derivation ───────────────────────

    @Test
    void relatedDerivation_followsL2LookupChain() {
        // Employee → Department (L1) → LegalEntity (L2).
        when(navResolver.resolvePrimaryModel("hr.employee")).thenReturn("Employee");

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("LegalEntity")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("departmentId", FieldType.MANY_TO_ONE, "Department")));
            mm.when(() -> ModelManager.getModelFields("Department")).thenReturn(List.of(
                    field("legalEntityId", FieldType.MANY_TO_ONE, "LegalEntity")));
            mm.when(() -> ModelManager.getModelFields("LegalEntity")).thenReturn(List.of());

            List<RoleModelConfigOption> data = controller.getModelOptions(List.of("hr.employee")).getData();

            Map<String, RoleModelConfigOption> m = byModel(data);
            assertThat(m.keySet()).containsExactlyInAnyOrder("Employee", "Department", "LegalEntity");
            assertThat(m.get("Employee").related()).isFalse();
            assertThat(m.get("Department").related()).isTrue();  // L1
            assertThat(m.get("LegalEntity").related()).isTrue(); // L2
        }
    }

    @Test
    void modelThatIsPrimaryOfAnotherNav_notDuplicatedAsRelated() {
        // Two navs → Employee, Department. Employee → Department (ManyToOne).
        // Department is already primary, so it must NOT reappear as a related row.
        when(navResolver.resolvePrimaryModel("hr.employee")).thenReturn("Employee");
        when(navResolver.resolvePrimaryModel("hr.department")).thenReturn("Department");

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.existModel("Department")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of(
                    field("departmentId", FieldType.MANY_TO_ONE, "Department")));
            mm.when(() -> ModelManager.getModelFields("Department")).thenReturn(List.of());

            List<RoleModelConfigOption> data =
                    controller.getModelOptions(List.of("hr.employee", "hr.department")).getData();

            Map<String, RoleModelConfigOption> m = byModel(data);
            // Exactly two rows, both primary — Department appears once, related=false.
            assertThat(data).hasSize(2);
            assertThat(m.keySet()).containsExactlyInAnyOrder("Employee", "Department");
            assertThat(m.get("Employee").related()).isFalse();
            assertThat(m.get("Department").related()).isFalse();
        }
    }

    @Test
    void unresolvedOrUnknownPrimaryModel_skipped() {
        when(navResolver.resolvePrimaryModel("hr.group")).thenReturn(null);            // container/group
        when(navResolver.resolvePrimaryModel("hr.ghost")).thenReturn("Ghost");         // not in metadata

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Ghost")).thenReturn(false);

            List<RoleModelConfigOption> data =
                    controller.getModelOptions(List.of("hr.group", "hr.ghost")).getData();

            assertThat(data).isEmpty();
        }
    }

    // ─────────────────── per-model scope + SFS assembly ───────────────────

    @Test
    void perModelOptions_sortedScopes_dedupedSfs_nameFallback() {
        when(navResolver.resolvePrimaryModel("hr.employee")).thenReturn("Employee");
        // DataScopeType views forcing ALL + SELF + DIRECT_REPORTS applicable
        // (appliesToAll so they apply regardless of Employee's empty field stub).
        when(modelService.searchList(eq("DataScopeType"), any(FlexQuery.class),
                eq(NavigationConfigOptionsController.DataScopeTypeView.class))).thenReturn(List.of(
                dst("ALL"), dst("SELF"), dst("DIRECT_REPORTS")));
        // SensitiveFieldSet views: emp-comp is owned by Employee AND attached to
        // Employee (must dedup to one row); emp-bank owned with no name (→ id fallback).
        when(modelService.searchList(eq("SensitiveFieldSet"), any(FlexQuery.class),
                eq(NavigationConfigOptionsController.SensitiveFieldSetView.class))).thenReturn(List.of(
                sfsView("emp-comp", "Employee", "Compensation", arrayOf("Employee")),
                sfsView("emp-bank", "Employee", null, null)));

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(List.of());

            RoleModelConfigOption opt = controller.getModelOptions(List.of("hr.employee")).getData().get(0);

            assertThat(opt.model()).isEqualTo("Employee");
            // getModel() unstubbed → null → label falls back to model name.
            assertThat(opt.label()).isEqualTo("Employee");
            // scope names sorted alphabetically.
            assertThat(opt.applicableScopes()).containsExactly("ALL", "DIRECT_REPORTS", "SELF");
            // SFS deduped by id; missing name falls back to the id.
            assertThat(opt.applicableSensitiveFieldSets())
                    .extracting(SfsRef::id, SfsRef::name)
                    .containsExactlyInAnyOrder(
                            tuple("emp-comp", "Compensation"),
                            tuple("emp-bank", "emp-bank"));
        }
    }

    @Test
    void perModelOptions_identityFilter_derivesApplicabilityFromTemplate() {
        // Wizard applicability replicates the engine: identity types derive their
        // anchor fields from the filter template (identityFilter on the identityModel).
        when(navResolver.resolvePrimaryModel("hr.employee")).thenReturn("Employee");
        when(modelService.searchList(eq("DataScopeType"), any(FlexQuery.class),
                eq(NavigationConfigOptionsController.DataScopeTypeView.class))).thenReturn(List.of(
                dstFilter("SELF", arrayOf("employeeId", "=", "USER_EMP_ID"),
                        "Employee", arrayOf("id", "=", "USER_EMP_ID")),
                dstFilter("LEGAL_ENTITY", arrayOf("legalEntityId", "=", "USER_COMP_ID"), null, null)));
        when(modelService.searchList(eq("SensitiveFieldSet"), any(FlexQuery.class),
                eq(NavigationConfigOptionsController.SensitiveFieldSetView.class))).thenReturn(List.of());

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            // Employee has id (SELF's identityFilter anchor via model-swap) but NOT
            // legalEntityId → SELF applies, LEGAL_ENTITY excluded.
            mm.when(() -> ModelManager.getModelFields("Employee"))
                    .thenReturn(List.of(field("id", FieldType.STRING, null)));

            RoleModelConfigOption opt = controller.getModelOptions(List.of("hr.employee")).getData().get(0);
            assertThat(opt.applicableScopes()).containsExactly("ALL", "SELF");
        }
    }

    @Test
    void anAdministratorMayGrantAnything() {
        when(roleGrantCeiling.callerGrants()).thenReturn(null);

        NavigationConfigOptionsController.GrantCeilingView view = controller.getGrantCeiling().getData();

        assertThat(view.unrestricted()).isTrue();
        assertThat(view.roles()).isEmpty();
    }

    @Test
    void anyoneElseMayGrantWhatTheirOwnRolesHoldRoleByRole() {
        RoleGrantCeiling.Grants hr = new RoleGrantCeiling.Grants(Set.of("nav.employee"), Set.of("employee.view"),
                Set.of("sfs.salary"), Map.of());
        RoleGrantCeiling.Grants self = new RoleGrantCeiling.Grants(Set.of("nav.home"), Set.of("home.view"),
                Set.of(), Map.of());
        when(roleGrantCeiling.callerGrants()).thenReturn(List.of(hr, self));

        NavigationConfigOptionsController.GrantCeilingView view = controller.getGrantCeiling().getData();

        assertThat(view.unrestricted()).isFalse();
        assertThat(view.roles()).containsExactly(hr, self);
    }
}
