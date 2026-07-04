package io.softa.starter.metadata.ddl;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.entity.SysField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reconciliation-time stamp of a TO_ONE FK's physical type onto {@code relatedFieldType}
 * (+ mirrored {@code length}/{@code scale}); the logical {@code fieldType} stays the relation type.
 */
class ReferenceColumnResolverTest {

    @Test
    void stampsIdFkToReferencedPkType() {
        SysField custId = scalar("Customer", "id", FieldType.LONG, null);
        SysField fk = fk("Order", "customer", FieldType.MANY_TO_ONE, "Customer", null);

        ReferenceColumnResolver.stampSysFields(List.of(custId, fk));

        assertEquals(FieldType.LONG, fk.getRelatedFieldType());
        assertNull(fk.getLength());
        assertEquals(FieldType.MANY_TO_ONE, fk.getFieldType(), "logical relation type is preserved");
    }

    @Test
    void stampsCodeFkToReferencedColumnTypeAndWidth() {
        SysField custCode = scalar("Customer", "code", FieldType.STRING, 32);
        SysField fk = fk("Order", "customerCode", FieldType.MANY_TO_ONE, "Customer", "code");

        ReferenceColumnResolver.stampSysFields(List.of(custCode, fk));

        assertEquals(FieldType.STRING, fk.getRelatedFieldType());
        assertEquals(32, fk.getLength());
        assertEquals(FieldType.MANY_TO_ONE, fk.getFieldType());
    }

    @Test
    void appliesToOneToOneToo() {
        SysField custId = scalar("Customer", "id", FieldType.STRING, 24);
        SysField fk = fk("Order", "customer", FieldType.ONE_TO_ONE, "Customer", "id");

        ReferenceColumnResolver.stampSysFields(List.of(custId, fk));

        assertEquals(FieldType.STRING, fk.getRelatedFieldType());
        assertEquals(24, fk.getLength());
    }

    @Test
    void leavesRelatedFieldTypeNullWhenReferenceUnresolved() {
        // Customer.code not present → code-intent miss: relatedFieldType stays null (renders BIGINT),
        // logical fieldType unchanged.
        SysField fk = fk("Order", "customerCode", FieldType.MANY_TO_ONE, "Customer", "code");

        ReferenceColumnResolver.stampSysFields(List.of(fk));

        assertNull(fk.getRelatedFieldType());
        assertEquals(FieldType.MANY_TO_ONE, fk.getFieldType());
    }

    @Test
    void resolvesAgainstWiderReferenceUniverse() {
        // Partial scanner-scope: the FK is in-scope (stamped) but its referenced model is only in the
        // wider universe (the full platform catalog). It must still resolve, not reset to null.
        SysField fk = fk("Order", "customer", FieldType.MANY_TO_ONE, "Customer", null);
        SysField outOfScopeCustomerId = scalar("Customer", "id", FieldType.STRING, 24);

        ReferenceColumnResolver.stampSysFields(List.of(fk), List.of(fk, outOfScopeCustomerId));

        assertEquals(FieldType.STRING, fk.getRelatedFieldType());
        assertEquals(24, fk.getLength());
    }

    @Test
    void ignoresNonRelationFields() {
        SysField plain = scalar("Order", "name", FieldType.STRING, 64);

        ReferenceColumnResolver.stampSysFields(List.of(plain));

        assertNull(plain.getRelatedFieldType());
        assertEquals(FieldType.STRING, plain.getFieldType());
        assertEquals(64, plain.getLength());
    }

    private static SysField scalar(String model, String name, FieldType type, Integer length) {
        SysField field = new SysField();
        field.setModelName(model);
        field.setFieldName(name);
        field.setFieldType(type);
        field.setLength(length);
        return field;
    }

    private static SysField fk(String model, String name, FieldType type, String relatedModel, String relatedField) {
        SysField field = scalar(model, name, type, null);
        field.setRelatedModel(relatedModel);
        field.setRelatedField(relatedField);
        return field;
    }
}
