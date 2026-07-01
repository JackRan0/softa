package io.softa.framework.orm.meta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.enums.FieldType;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class ModelManagerTest {

    @BeforeAll
    static void ensureSystemConfig() {
        // Constructing IllegalArgumentException reaches I18n.get → ContextHolder.getContext,
        // which dereferences SystemConfig.env. In a raw unit test context env is null;
        // the framework's own auto-config is what populates it in production.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    private static MetaField field(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName(fieldName);
        metaField.setFieldType(type);
        metaField.setRelatedModel(relatedModel);
        return metaField;
    }

    private static MetaField storedField(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField metaField = field(modelName, fieldName, type, relatedModel);
        metaField.setDynamic(false);
        return metaField;
    }

    private static MetaField dynamicField(String modelName, String fieldName, FieldType type) {
        MetaField metaField = field(modelName, fieldName, type, null);
        metaField.setDynamic(true);
        return metaField;
    }

    @Test
    void initComputedFields() {
        String formula = "if seq != \"6\" { \"17\" } else { \"99\" }";
        List<String> dependentFields = ComputeUtils.getVariables(formula);
        Assertions.assertNotNull(dependentFields);
        Map<String, Object> env = new HashMap<>();
        env.put("seq", "5");
        Object result = ComputeUtils.execute(formula, env);
        log.info(result.toString());
    }

    @Test
    void getLastFieldOfCascadedReturnsStoredLeaf() {
        MetaField rel = storedField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField leaf = storedField("DesignDeployment", "deployStatus", FieldType.OPTION, null);
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mock.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            mock.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mock.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(leaf);

            MetaField result = ModelManager.getLastFieldOfCascaded("AppEnv", "lastDeploymentId.deployStatus");

            assertSame(leaf, result);
        }
    }

    @Test
    void getLastFieldOfCascadedReturnsDynamicLeaf() {
        // getLastFieldOfCascaded is a policy-neutral path resolver (delegates to CascadeFieldWalker,
        // which validates structure only). A dynamic (non-stored) leaf — e.g. a computed field — is
        // resolved and RETURNED, not rejected, so export-header / projection callers can read its
        // type/label. The "leaf must be stored" policy lives only where it matters, at the SQL filter
        // call site (WhereBuilder), not here. (The check was relocated there in commit b06867d.)
        MetaField rel = storedField("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField leaf = dynamicField("DesignDeployment", "computedThing", FieldType.STRING);
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            mock.when(() -> ModelManager.existField("DesignDeployment", "computedThing")).thenReturn(true);
            mock.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            mock.when(() -> ModelManager.getModelField("DesignDeployment", "computedThing")).thenReturn(leaf);

            MetaField result = ModelManager.getLastFieldOfCascaded("AppEnv", "lastDeploymentId.computedThing");

            assertSame(leaf, result);
        }
    }

    @Test
    void getLastFieldOfCascadedRejectsTraverseThroughOneToMany() {
        MetaField team = storedField("AppEnv", "team", FieldType.ONE_TO_MANY, "Member");
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "team")).thenReturn(true);
            mock.when(() -> ModelManager.getModelField("AppEnv", "team")).thenReturn(team);

            assertThrows(IllegalArgumentException.class,
                    () -> ModelManager.getLastFieldOfCascaded("AppEnv", "team.assigneeId"));
        }
    }

    @Test
    void getLastFieldOfCascadedRejectsMissingField() {
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            mock.when(() -> ModelManager.existField("AppEnv", "ghost")).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> ModelManager.getLastFieldOfCascaded("AppEnv", "ghost.something"));
        }
    }

    @Test
    void getLastFieldOfCascadedRejectsExcessiveDepth() {
        // CASCADE_LEVEL = 4, so a path with 6 segments (5 hops) is too deep.
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class, Mockito.CALLS_REAL_METHODS)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ModelManager.getLastFieldOfCascaded("AppEnv", "a.b.c.d.e.f"));
            // No metadata calls should have happened — depth check fires first.
            mock.verify(() -> ModelManager.existField(Mockito.anyString(), Mockito.anyString()), Mockito.never());
        }
    }

    // --- boot-time CASCADE acyclicity (replaces the runtime cycle guard) ---

    @Test
    void cascadeSelfLoop_rejectedAtBoot() {
        // self-referential CASCADE (e.g. OrgUnit.parentId → OrgUnit) would recurse forever
        assertThrows(IllegalArgumentException.class,
                () -> ModelManager.assertCascadeAcyclic(Map.of("OrgUnit", List.of("OrgUnit"))));
    }

    @Test
    void cascadeCycle_rejectedAtBoot() {
        // A → B → A
        assertThrows(IllegalArgumentException.class,
                () -> ModelManager.assertCascadeAcyclic(Map.of("A", List.of("B"), "B", List.of("A"))));
    }

    @Test
    void cascadeDiamond_allowed() {
        // A → B, A → C, B → D, C → D : a DAG (re-convergence), NOT a cycle → allowed (no throw)
        ModelManager.assertCascadeAcyclic(Map.of("A", List.of("B", "C"), "B", List.of("D"), "C", List.of("D")));
    }

    @Test
    void cascadeChain_allowed() {
        ModelManager.assertCascadeAcyclic(Map.of("A", List.of("B"), "B", List.of("C")));
    }

    @Test
    void cascadeChain_atMaxDepth_allowed() {
        // A→B→C→D is exactly MAX_CASCADE_DEPTH (4) models → allowed (no throw).
        ModelManager.assertCascadeAcyclic(Map.of("A", List.of("B"), "B", List.of("C"), "C", List.of("D")));
    }

    @Test
    void cascadeChain_overMaxDepth_rejectedWithFullChain() {
        // A→B→C→D→E is 5 models deep (> MAX_CASCADE_DEPTH = 4) → rejected; the message names the full chain.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelManager.assertCascadeAcyclic(Map.of(
                        "A", List.of("B"), "B", List.of("C"), "C", List.of("D"), "D", List.of("E"))));
        assertTrue(ex.getMessage().contains("A -> B -> C -> D -> E"), ex.getMessage());
        assertTrue(ex.getMessage().contains("MAX_CASCADE_DEPTH"), ex.getMessage());
    }

    @Test
    void cascadeDiamondDeep_measuresLongestPath() {
        // Diamond A→B→D, A→C→D→E: the longest path A→B/C→D→E = 4 models = MAX → allowed (re-convergence
        // at D is memoized, not double-counted). Adding one more level would tip it over.
        ModelManager.assertCascadeAcyclic(Map.of(
                "A", List.of("B", "C"), "B", List.of("D"), "C", List.of("D"), "D", List.of("E")));
    }

    private static MetaIndex index(String indexName, String modelName) {
        MetaIndex idx = new MetaIndex();
        idx.setIndexName(indexName);
        idx.setModelName(modelName);
        return idx;
    }

    @Test
    void duplicateIndexName_acrossModels_failsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelManager.assertIndexNamesGloballyUnique(List.of(
                        index("idx_dup", "ModelA"),
                        index("idx_dup", "ModelB"))));
        assertTrue(ex.getMessage().contains("idx_dup"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ModelA") && ex.getMessage().contains("ModelB"), ex.getMessage());
    }

    @Test
    void uniqueIndexNames_acrossModels_pass() {
        ModelManager.assertIndexNamesGloballyUnique(List.of(
                index("idx_a", "ModelA"),
                index("idx_b", "ModelB")));
    }
}
