package io.softa.starter.metadata.scanner.diff;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DiffEngine}.
 */
class DiffEngineTest {

    private final DiffEngine engine = new DiffEngine();

    // ------- helpers -----------------------------------------------------

    private static SysModel model(String name, String tableName) {
        SysModel m = new SysModel();
        m.setModelName(name);
        m.setTableName(tableName);
        return m;
    }

    private static SysField field(String modelName, String fieldName, FieldType type) {
        SysField f = new SysField();
        f.setModelName(modelName);
        f.setFieldName(fieldName);
        f.setFieldType(type);
        return f;
    }

    private static SysOptionSet optionSet(String code, String label) {
        SysOptionSet os = new SysOptionSet();
        os.setOptionSetCode(code);
        os.setLabel(label);
        return os;
    }

    private static SysOptionItem optionItem(String setCode, String code, String name) {
        SysOptionItem i = new SysOptionItem();
        i.setOptionSetCode(setCode);
        i.setItemCode(code);
        i.setLabel(name);
        return i;
    }

    // ------- empty / identity -------------------------------------------

    @Test
    void empty_vs_empty_isEmptyDiff() {
        SchemaDiff diff = engine.diff(AnnotationScanResult.empty(), AnnotationScanResult.empty());
        assertTrue(diff.isEmpty());
    }

    @Test
    void identical_inputs_produceNoModifications() {
        SysModel m = model("Customer", "biz_customer");
        AnnotationScanResult code = new AnnotationScanResult(List.of(m), List.of(), List.of(), List.of());
        AnnotationScanResult db = new AnnotationScanResult(List.of(m), List.of(), List.of(), List.of());
        SchemaDiff diff = engine.diff(code, db);
        assertTrue(diff.isEmpty());
    }

    // ------- added / removed --------------------------------------------

    @Test
    void modelInCodeButNotInDb_isAdded() {
        SysModel customer = model("Customer", "biz_customer");
        AnnotationScanResult code = new AnnotationScanResult(
                List.of(customer), List.of(), List.of(), List.of());
        AnnotationScanResult db = AnnotationScanResult.empty();
        SchemaDiff diff = engine.diff(code, db);

        assertEquals(1, diff.models().added().size());
        assertSame(customer, diff.models().added().get(0));
        assertTrue(diff.models().removed().isEmpty());
        assertTrue(diff.models().modified().isEmpty());
    }

    @Test
    void modelInDbButNotInCode_isRemoved() {
        SysModel ghost = model("Ghost", "biz_ghost");
        AnnotationScanResult code = AnnotationScanResult.empty();
        AnnotationScanResult db = new AnnotationScanResult(
                List.of(ghost), List.of(), List.of(), List.of());
        SchemaDiff diff = engine.diff(code, db);

        assertEquals(1, diff.models().removed().size());
        assertSame(ghost, diff.models().removed().getFirst());
        assertTrue(diff.models().added().isEmpty());
    }

    // ------- modified ----------------------------------------------------

    @Test
    void modelWithDifferentTableName_isModified() {
        SysModel codeModel = model("Customer", "biz_customer_v2");
        SysModel dbModel = model("Customer", "biz_customer");
        AnnotationScanResult code = new AnnotationScanResult(
                List.of(codeModel), List.of(), List.of(), List.of());
        AnnotationScanResult db = new AnnotationScanResult(
                List.of(dbModel), List.of(), List.of(), List.of());
        SchemaDiff diff = engine.diff(code, db);

        assertEquals(1, diff.models().modified().size());
        SchemaDiff.Modification<SysModel> mod = diff.models().modified().get(0);
        assertSame(codeModel, mod.fromCode());
        assertSame(dbModel, mod.fromDb());
    }

    @Test
    void fieldWithDifferentType_isModified_andKeyedByModelDotField() {
        SysField codeField = field("Customer", "name", FieldType.STRING);
        codeField.setLength(64);
        SysField dbField = field("Customer", "name", FieldType.STRING);
        dbField.setLength(32);
        AnnotationScanResult code = new AnnotationScanResult(
                List.of(), List.of(codeField), List.of(), List.of());
        AnnotationScanResult db = new AnnotationScanResult(
                List.of(), List.of(dbField), List.of(), List.of());
        SchemaDiff diff = engine.diff(code, db);

        assertEquals(1, diff.fields().modified().size());
    }

    @Test
    void fieldOnDifferentModel_isTreatedAsDistinct() {
        SysField customerName = field("Customer", "name", FieldType.STRING);
        SysField productName = field("Product", "name", FieldType.STRING);
        AnnotationScanResult code = new AnnotationScanResult(
                List.of(), List.of(customerName, productName), List.of(), List.of());
        AnnotationScanResult db = new AnnotationScanResult(
                List.of(), List.of(customerName), List.of(), List.of());
        SchemaDiff diff = engine.diff(code, db);

        assertEquals(1, diff.fields().added().size());
        assertSame(productName, diff.fields().added().get(0));
    }

    // ------- option sets / items ---------------------------------------

    @Test
    void optionSetItemsDiffSeparately() {
        SysOptionSet tier = optionSet("Tier", "Customer Tier");
        SysOptionItem gold = optionItem("Tier", "g", "Gold");
        SysOptionItem silver = optionItem("Tier", "s", "Silver");
        AnnotationScanResult code = new AnnotationScanResult(
                List.of(), List.of(), List.of(tier), List.of(gold, silver));
        AnnotationScanResult db = new AnnotationScanResult(
                List.of(), List.of(), List.of(tier), List.of(gold));
        SchemaDiff diff = engine.diff(code, db);

        assertTrue(diff.optionSets().isEmpty());
        assertEquals(1, diff.optionItems().added().size());
        assertSame(silver, diff.optionItems().added().get(0));
    }

    @Test
    void optionItemRename_isModification() {
        SysOptionItem codeItem = optionItem("Tier", "g", "Gold Tier");
        SysOptionItem dbItem = optionItem("Tier", "g", "Gold");
        AnnotationScanResult code = new AnnotationScanResult(
                List.of(), List.of(), List.of(), List.of(codeItem));
        AnnotationScanResult db = new AnnotationScanResult(
                List.of(), List.of(), List.of(), List.of(dbItem));
        SchemaDiff diff = engine.diff(code, db);

        assertEquals(1, diff.optionItems().modified().size());
    }

    @Test
    void totalCount_sumsAllBuckets() {
        SysModel m1 = model("A", "a");
        SysModel m2 = model("B", "b");
        SysField f = field("A", "x", FieldType.STRING);
        AnnotationScanResult code = new AnnotationScanResult(
                List.of(m1, m2), List.of(f), List.of(), List.of());
        AnnotationScanResult db = AnnotationScanResult.empty();
        SchemaDiff diff = engine.diff(code, db);

        // 2 models added + 1 field added = 3
        assertEquals(3, diff.totalCount());
    }
}
