package io.softa.starter.metadata.scanner.diff;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.annotation.RenameDeclarations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code DiffEngine} rename pre-pass pairs a declared
 * {@code @RenamedFrom} across the removed-old / added-new split into a single
 * {@code Modification(kind=RENAME)} — and a model rename cascades onto its fields.
 */
class DiffEngineRenameTest {

    private final DiffEngine engine = new DiffEngine();

    private static SysModel model(String name) {
        SysModel m = new SysModel();
        m.setModelName(name);
        m.setTableName(name.toLowerCase());
        return m;
    }

    private static SysField field(String modelName, String fieldName) {
        SysField f = new SysField();
        f.setModelName(modelName);
        f.setFieldName(fieldName);
        f.setColumnName(fieldName);
        f.setFieldType(FieldType.STRING);
        return f;
    }

    private static AnnotationScanResult code(List<SysModel> models, List<SysField> fields,
                                             RenameDeclarations renames) {
        return new AnnotationScanResult(models, fields, List.of(), List.of(), List.of(), renames);
    }

    private static AnnotationScanResult db(List<SysModel> models, List<SysField> fields) {
        return new AnnotationScanResult(models, fields, List.of(), List.of());
    }

    @Test
    void pairsDeclaredFieldRenameInsteadOfDropAdd() {
        AnnotationScanResult fromCode = code(
                List.of(model("M")), List.of(field("M", "custName")),
                new RenameDeclarations(Map.of(), Map.of("M.custName", "customerName")));
        AnnotationScanResult fromDb = db(List.of(model("M")), List.of(field("M", "customerName")));

        SchemaDiff diff = engine.diff(fromCode, fromDb);

        assertTrue(diff.fields().added().isEmpty(), "renamed field must not be a fresh add");
        assertTrue(diff.fields().removed().isEmpty(), "renamed field must not be a drop");
        assertEquals(1, diff.fields().modified().size());
        SchemaDiff.Modification<SysField> rename = diff.fields().modified().getFirst();
        assertEquals(SchemaDiff.Kind.RENAME, rename.kind());
        assertEquals("custName", rename.fromCode().getFieldName());
        assertEquals("customerName", rename.fromDb().getFieldName());
    }

    @Test
    void modelRenameCascadesSoFieldsAreNotChurned() {
        AnnotationScanResult fromCode = code(
                List.of(model("Customer")), List.of(field("Customer", "code")),
                new RenameDeclarations(Map.of("Customer", "OldCustomer"), Map.of()));
        AnnotationScanResult fromDb = db(
                List.of(model("OldCustomer")), List.of(field("OldCustomer", "code")));

        SchemaDiff diff = engine.diff(fromCode, fromDb);

        // Model is a RENAME, not add+drop.
        assertTrue(diff.models().added().isEmpty());
        assertTrue(diff.models().removed().isEmpty());
        assertEquals(1, diff.models().modified().size());
        SchemaDiff.Modification<SysModel> mr = diff.models().modified().getFirst();
        assertEquals(SchemaDiff.Kind.RENAME, mr.kind());
        assertEquals("Customer", mr.fromCode().getModelName());
        assertEquals("OldCustomer", mr.fromDb().getModelName());
        // The field under the renamed model is NOT divorced (re-keyed via the cascade).
        assertTrue(diff.fields().added().isEmpty(), "model rename must not re-add its fields");
        assertTrue(diff.fields().removed().isEmpty(), "model rename must not drop its fields");
    }

    @Test
    void modelRenameReKeysRemovedFieldsOntoNewModelName() {
        // Model renamed AND a stale field removed in the same boot. The cascade must
        // MUTATE the removed db row onto the new modelName: the writer deletes after
        // its own row-side cascade has re-pointed all child rows to the new name, so
        // a DELETE keyed on the old name would miss and orphan the row for a boot.
        AnnotationScanResult fromCode = code(
                List.of(model("Customer")), List.of(field("Customer", "code")),
                new RenameDeclarations(Map.of("Customer", "OldCustomer"), Map.of()));
        AnnotationScanResult fromDb = db(
                List.of(model("OldCustomer")),
                List.of(field("OldCustomer", "code"), field("OldCustomer", "legacy")));

        SchemaDiff diff = engine.diff(fromCode, fromDb);

        assertEquals(1, diff.fields().removed().size());
        SysField removed = diff.fields().removed().getFirst();
        assertEquals("legacy", removed.getFieldName());
        assertEquals("Customer", removed.getModelName(),
                "removed row must carry the post-rename model name");
    }

    @Test
    void bothOldAndNewPresentFailsFast() {
        AnnotationScanResult fromCode = code(
                List.of(model("M")), List.of(field("M", "custName")),
                new RenameDeclarations(Map.of(), Map.of("M.custName", "customerName")));
        // Half-applied: db has BOTH the new and the prior name.
        AnnotationScanResult fromDb = db(
                List.of(model("M")), List.of(field("M", "custName"), field("M", "customerName")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> engine.diff(fromCode, fromDb));
        assertTrue(ex.getMessage().contains("both the new name and prior name"), ex.getMessage());
    }

    @Test
    void renameAlreadyAppliedIsIdempotentNoOp() {
        // New name already present in db, prior name gone → nothing to do.
        AnnotationScanResult fromCode = code(
                List.of(model("M")), List.of(field("M", "custName")),
                new RenameDeclarations(Map.of(), Map.of("M.custName", "customerName")));
        AnnotationScanResult fromDb = db(List.of(model("M")), List.of(field("M", "custName")));

        SchemaDiff diff = engine.diff(fromCode, fromDb);

        assertTrue(diff.fields().added().isEmpty());
        assertTrue(diff.fields().removed().isEmpty());
        assertTrue(diff.fields().modified().isEmpty(), "identical field is a no-op");
    }

    @Test
    void undeclaredRenameStillFallsThroughAsDropAdd() {
        // No @RenamedFrom → the engine cannot link them; preserves legacy add+remove.
        AnnotationScanResult fromCode = code(
                List.of(model("M")), List.of(field("M", "custName")), RenameDeclarations.empty());
        AnnotationScanResult fromDb = db(List.of(model("M")), List.of(field("M", "customerName")));

        SchemaDiff diff = engine.diff(fromCode, fromDb);

        assertEquals(1, diff.fields().added().size());
        assertEquals("custName", diff.fields().added().getFirst().getFieldName());
        assertEquals(1, diff.fields().removed().size());
        assertEquals("customerName", diff.fields().removed().getFirst().getFieldName());
    }
}
