package io.softa.starter.metadata.scanner.annotation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.entity.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end parse of the 8 SYSTEM_MODEL entities.
 *
 * <p>Verifies that the {@code @Model} + {@code @Field} annotations on
 * {@code SysModel} / {@code SysModelTrans} / {@code SysField} / {@code SysFieldTrans} /
 * {@code SysOptionSet} / {@code SysOptionSetTrans} / {@code SysOptionItem} /
 * {@code SysOptionItemTrans} are syntactically valid and parse into Sys*
 * rows with the expected core attributes.
 *
 * <p>Self-describing-recursion guard: `SysModel` describing `SysModel` is
 * structurally fine because the parser is a pure function over reflection;
 * it never reads {@code ModelManager}. This test exercises exactly that.
 */
class SystemModelAnnotationTest {

    private static final List<Class<?>> SYSTEM_MODELS = List.of(
            SysModel.class,
            SysModelTrans.class,
            SysField.class,
            SysFieldTrans.class,
            SysOptionSet.class,
            SysOptionSetTrans.class,
            SysOptionItem.class,
            SysOptionItemTrans.class
    );

    private final AnnotationParser parser = new AnnotationParser();

    @Test
    void all8SystemModels_parseWithoutError() {
        AnnotationScanResult result = parser.parse(SYSTEM_MODELS, List.of());

        assertEquals(8, result.models().size());
        Set<String> modelNames = result.models().stream()
                .map(SysModel::getModelName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "SysModel", "SysModelTrans",
                "SysField", "SysFieldTrans",
                "SysOptionSet", "SysOptionSetTrans",
                "SysOptionItem", "SysOptionItemTrans"
        ), modelNames);

        for (SysModel m : result.models()) {
            assertEquals(IdStrategy.DB_AUTO_ID, m.getIdStrategy(),
                    "Model " + m.getModelName() + " must use DB_AUTO_ID (matches CREATE TABLE AUTO_INCREMENT)");
            assertEquals(Boolean.FALSE, m.getMultiTenant(),
                    "Model " + m.getModelName() + " must be multi-tenant=false (sys_* is global)");
        }
    }

    @Test
    void sysModel_tableNameDerivedFromSnakeCase() {
        AnnotationScanResult result = parser.parse(List.of(SysModel.class), List.of());
        SysModel m = result.models().getFirst();
        assertEquals("sys_model", m.getTableName());
        assertEquals(List.of("modelName"), m.getBusinessKey());
    }

    @Test
    void sysField_businessKeyIsCompound() {
        AnnotationScanResult result = parser.parse(List.of(SysField.class), List.of());
        SysModel m = result.models().getFirst();
        assertEquals("sys_field", m.getTableName());
        assertEquals(List.of("modelName", "fieldName"), m.getBusinessKey());
    }

    @Test
    void sysModel_idStrategyField_inferredAsOption() {
        AnnotationScanResult result = parser.parse(List.of(SysModel.class), List.of());
        SysField idStrategy = byFieldName(result.fields(), "idStrategy");
        assertEquals(FieldType.OPTION, idStrategy.getFieldType());
        assertEquals("IdStrategy", idStrategy.getOptionSetCode());
    }

    @Test
    void sysModel_displayNameField_inferredAsMultiString() {
        AnnotationScanResult result = parser.parse(List.of(SysModel.class), List.of());
        SysField displayName = byFieldName(result.fields(), "displayName");
        assertEquals(FieldType.MULTI_STRING, displayName.getFieldType());
    }

    @Test
    void sysModel_defaultOrderField_inferredAsOrders() {
        AnnotationScanResult result = parser.parse(List.of(SysModel.class), List.of());
        SysField defaultOrder = byFieldName(result.fields(), "defaultOrder");
        assertEquals(FieldType.ORDERS, defaultOrder.getFieldType());
    }

    @Test
    void sysModel_modelFieldsRelation_isAnnotatedOneToMany() {
        // SysModel.modelFields is annotated ONE_TO_MANY → emitted as a sys_field row
        // (joins on the surrogate FK modelId). It has NO sys_model column —
        // SysCatalog and the DDL builder both exclude X-to-many — so the loader never SELECTs it.
        AnnotationScanResult result = parser.parse(List.of(SysModel.class), List.of());
        SysField modelFields = byFieldName(result.fields(), "modelFields");
        assertEquals(FieldType.ONE_TO_MANY, modelFields.getFieldType());
        assertEquals("SysField", modelFields.getRelatedModel());
        assertEquals("modelId", modelFields.getRelatedField());
    }

    @Test
    void sysOptionSet_optionItemsRelation_isAnnotatedOneToMany() {
        AnnotationScanResult result = parser.parse(List.of(SysOptionSet.class), List.of());
        SysField optionItems = byFieldName(result.fields(), "optionItems");
        assertEquals(FieldType.ONE_TO_MANY, optionItems.getFieldType());
        assertEquals("SysOptionItem", optionItems.getRelatedModel());
        assertEquals("optionSetId", optionItems.getRelatedField());
    }

    private static SysField byFieldName(List<SysField> fields, String name) {
        return fields.stream()
                .filter(f -> name.equals(f.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }
}
