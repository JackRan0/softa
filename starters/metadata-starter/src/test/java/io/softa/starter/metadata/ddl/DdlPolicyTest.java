package io.softa.starter.metadata.ddl;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;
import io.softa.starter.metadata.scanner.diff.SchemaDiff.EntityDiff;
import io.softa.starter.metadata.scanner.diff.SchemaDiff.Modification;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DdlPolicy}'s DDL-relevant projection: row-only attribute
 * changes must not become DDL work, physical deltas route to the right bucket.
 */
class DdlPolicyTest {

    private static SysField field(String fieldName) {
        SysField f = new SysField();
        f.setModelName("Customer");
        f.setFieldName(fieldName);
        f.setColumnName(fieldName);
        f.setFieldType(FieldType.STRING);
        f.setLength(64);
        f.setLabel(fieldName);
        return f;
    }

    private static SysModelIndex index(List<String> fields, boolean unique, String message) {
        SysModelIndex i = new SysModelIndex();
        i.setModelName("Customer");
        i.setIndexName("idx_customer_x");
        i.setIndexFields(new java.util.ArrayList<>(fields));
        i.setUniqueIndex(unique);
        i.setMessage(message);
        return i;
    }

    private static SchemaDiff fieldModification(SysField code, SysField db) {
        return new SchemaDiff(
                EntityDiff.<SysModel>empty(),
                new EntityDiff<>(List.of(), List.of(), List.of(new Modification<>(code, db))),
                EntityDiff.empty(), EntityDiff.empty(), EntityDiff.empty());
    }

    private static SchemaDiff indexModification(SysModelIndex code, SysModelIndex db) {
        return new SchemaDiff(
                EntityDiff.<SysModel>empty(), EntityDiff.<SysField>empty(),
                EntityDiff.empty(), EntityDiff.empty(),
                new EntityDiff<>(List.of(), List.of(), List.of(new Modification<>(code, db))));
    }

    @Test
    void labelOnlyFieldChangeProducesNoDdl() {
        SysField db = field("email");
        SysField code = field("email");
        code.setLabel("Email Address");   // row-only attribute

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(fieldModification(code, db), Map.of());

        assertTrue(ops.isEmpty(), "label-only change must not render DDL");
    }

    @Test
    void lengthChangeRoutesToModify() {
        SysField db = field("email");
        SysField code = field("email");
        code.setLength(256);

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(fieldModification(code, db), Map.of());

        assertEquals(1, ops.size());
        assertEquals(DdlPolicy.Operation.ALTER_TABLE, ops.get(0).operation());
        assertEquals(List.of(code), ops.get(0).fields().updated());
    }

    @Test
    void columnNameChangeRoutesToChangeColumn() {
        SysField db = field("email");
        SysField code = field("email");
        code.setColumnName("email_address");

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(fieldModification(code, db), Map.of());

        assertEquals(1, ops.size());
        List<DdlPolicy.FieldRename> renamed = ops.get(0).fields().renamed();
        assertEquals(1, renamed.size());
        assertEquals("email", renamed.get(0).oldColumnName());
        assertSame(code, renamed.get(0).field());
        assertTrue(ops.get(0).fields().updated().isEmpty(), "a physical rename must not also MODIFY");
    }

    @Test
    void storedToDynamicRoutesToDropWarning() {
        SysField db = field("email");
        SysField code = field("email");
        code.setDynamic(true);

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(fieldModification(code, db), Map.of());

        assertEquals(1, ops.size());
        assertEquals(DdlPolicy.Operation.ALTER_TABLE_WITH_DROP_WARNING, ops.get(0).operation());
        assertEquals(List.of(db), ops.get(0).fields().deleted());
    }

    @Test
    void dynamicToStoredRoutesToAddColumn() {
        SysField db = field("email");
        db.setDynamic(true);
        SysField code = field("email");

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(fieldModification(code, db), Map.of());

        assertEquals(1, ops.size());
        assertEquals(DdlPolicy.Operation.ALTER_TABLE, ops.get(0).operation());
        assertEquals(List.of(code), ops.get(0).fields().added());
    }

    @Test
    void messageOnlyIndexChangeProducesNoDdl() {
        SysModelIndex db = index(List.of("email"), true, null);
        SysModelIndex code = index(List.of("email"), true, "This email is already registered.");

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(indexModification(code, db), Map.of());

        assertTrue(ops.isEmpty(), "a violation-message change must not rebuild the index");
    }

    @Test
    void indexColumnsChangeRoutesToRebuild() {
        SysModelIndex db = index(List.of("email"), true, null);
        SysModelIndex code = index(List.of("email", "tenant"), true, null);

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(indexModification(code, db), Map.of());

        assertEquals(1, ops.size());
        assertEquals(List.of(code), ops.get(0).indexes().updated());
    }

    @Test
    void relatedFieldTypeChangeIsDdlRelevant() {
        SysField db = field("currency");
        db.setFieldType(FieldType.MANY_TO_ONE);
        db.setRelatedFieldType(FieldType.LONG);
        db.setLength(null);
        SysField code = field("currency");
        code.setFieldType(FieldType.MANY_TO_ONE);
        code.setRelatedFieldType(FieldType.STRING);   // referenced master went code-as-id
        code.setLength(3);

        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(fieldModification(code, db), Map.of());

        assertEquals(1, ops.size());
        assertEquals(List.of(code), ops.get(0).fields().updated());
    }
}
