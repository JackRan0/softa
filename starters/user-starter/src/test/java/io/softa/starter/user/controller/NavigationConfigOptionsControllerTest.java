package io.softa.starter.user.controller;

import java.util.EnumSet;
import java.util.LinkedHashSet;
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

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.user.dto.NavConfigOptions.SfsRef;
import io.softa.starter.user.dto.RoleModelConfigOption;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.filter.SensitiveFieldSetCache;
import io.softa.starter.user.scope.ScopeApplicabilityResolver;
import io.softa.starter.user.service.NavigationModelResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NavigationConfigOptionsController#getModelOptions} — the
 * role-wizard data step's per-model option endpoint. Focus is the related-model
 * derivation (primary + L1/L2 lookup fan-out following ONLY ManyToOne/ManyToMany,
 * with dedup vs primary) and the per-model scope/SFS assembly.
 *
 * <p>Mirrors the mockStatic(ModelManager)+MetaField pattern from
 * {@link io.softa.starter.user.service.EndpointIndexStandardDerivationTest}.
 */
class NavigationConfigOptionsControllerTest {

    private NavigationModelResolver navResolver;
    private ScopeApplicabilityResolver scopeApplicability;
    private SensitiveFieldSetCache sfsCache;
    private NavigationConfigOptionsController controller;

    @BeforeEach
    void setUp() {
        navResolver = mock(NavigationModelResolver.class);
        scopeApplicability = mock(ScopeApplicabilityResolver.class);
        sfsCache = mock(SensitiveFieldSetCache.class);
        controller = new NavigationConfigOptionsController(navResolver, scopeApplicability, sfsCache);

        // Safe defaults so buildModelOption never dereferences null (it iterates
        // these for EVERY model in the output). Specific-arg stubs in individual
        // tests take precedence (Mockito: last matching stub wins).
        when(scopeApplicability.applicableFor(anyString())).thenReturn(EnumSet.of(ScopeType.ALL));
        when(sfsCache.setIdsOwnedBy(anyString())).thenReturn(Set.of());
        when(sfsCache.setIdsAttachedTo(anyString())).thenReturn(Set.of());
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
        when(scopeApplicability.applicableFor("Employee")).thenReturn(
                EnumSet.of(ScopeType.ALL, ScopeType.SELF, ScopeType.DIRECT_REPORTS));
        // emp-comp appears in BOTH owned and attached → must dedup to one row.
        when(sfsCache.setIdsOwnedBy("Employee"))
                .thenReturn(new LinkedHashSet<>(List.of("emp-comp", "emp-bank")));
        when(sfsCache.setIdsAttachedTo("Employee"))
                .thenReturn(new LinkedHashSet<>(List.of("emp-comp")));
        when(sfsCache.nameOf("emp-comp")).thenReturn("Compensation");
        when(sfsCache.nameOf("emp-bank")).thenReturn(null);  // name fallback → id

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
}
