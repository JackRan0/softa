package io.softa.starter.studio.release.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;

/**
 * {@link JdbcTypeReverse} maps a physical column's JDBC type → its canonical
 * primitive {@link FieldType} + length/scale, lossily (logical refinements are not inferable from physical
 * schema) and seed-independently (no {@code DesignFieldDbMapping} rows needed).
 */
class JdbcTypeReverseTest {

    @Test
    @DisplayName("VARCHAR carries its width as STRING length")
    void varcharToStringWithWidth() {
        ReversedColumn c = JdbcTypeReverse.reverse(Types.VARCHAR, 100, null);
        assertEquals(FieldType.STRING, c.fieldType());
        assertEquals(100, c.length());
        assertNull(c.scale());
    }

    @Test
    @DisplayName("DECIMAL carries precision + scale as BigDecimal")
    void decimalToBigDecimal() {
        ReversedColumn c = JdbcTypeReverse.reverse(Types.DECIMAL, 10, 2);
        assertEquals(FieldType.BIG_DECIMAL, c.fieldType());
        assertEquals(10, c.length());
        assertEquals(2, c.scale());
    }

    @Test
    @DisplayName("DECIMAL(n,0) preserves scale 0 rather than dropping to the render-side default")
    void decimalScaleZeroPreserved() {
        ReversedColumn c = JdbcTypeReverse.reverse(Types.DECIMAL, 18, 0);
        assertEquals(FieldType.BIG_DECIMAL, c.fieldType());
        assertEquals(18, c.length());
        assertEquals(0, c.scale(), "scale 0 (an exact-integer DECIMAL) is legitimate and must be carried");
    }

    @Test
    @DisplayName("DECIMAL with an absent (null) scale defers to the render-side default")
    void decimalNullScaleDefers() {
        ReversedColumn c = JdbcTypeReverse.reverse(Types.NUMERIC, 12, null);
        assertEquals(FieldType.BIG_DECIMAL, c.fieldType());
        assertEquals(12, c.length());
        assertNull(c.scale(), "a missing scale is left null so the render side applies its default");
    }

    @Test
    @DisplayName("integer family → INTEGER / LONG without a carried width")
    void integerFamily() {
        assertEquals(FieldType.INTEGER, JdbcTypeReverse.reverse(Types.INTEGER, 10, null).fieldType());
        assertEquals(FieldType.INTEGER, JdbcTypeReverse.reverse(Types.SMALLINT, 5, null).fieldType());
        assertEquals(FieldType.INTEGER, JdbcTypeReverse.reverse(Types.TINYINT, 3, null).fieldType());
        ReversedColumn bigint = JdbcTypeReverse.reverse(Types.BIGINT, 19, null);
        assertEquals(FieldType.LONG, bigint.fieldType());
        assertNull(bigint.length(), "integer width is a display hint, not carried");
    }

    @Test
    @DisplayName("boolean and temporal families")
    void booleanAndTemporal() {
        assertEquals(FieldType.BOOLEAN, JdbcTypeReverse.reverse(Types.BOOLEAN, null, null).fieldType());
        assertEquals(FieldType.BOOLEAN, JdbcTypeReverse.reverse(Types.BIT, null, null).fieldType());
        assertEquals(FieldType.DATE, JdbcTypeReverse.reverse(Types.DATE, null, null).fieldType());
        assertEquals(FieldType.DATE_TIME, JdbcTypeReverse.reverse(Types.TIMESTAMP, null, null).fieldType());
        assertEquals(FieldType.TIME, JdbcTypeReverse.reverse(Types.TIME, null, null).fieldType());
    }

    @Test
    @DisplayName("large objects map to STRING (wide width → renders TEXT on the render side)")
    void clobToString() {
        ReversedColumn c = JdbcTypeReverse.reverse(Types.LONGVARCHAR, 65535, null);
        assertEquals(FieldType.STRING, c.fieldType());
        assertEquals(65535, c.length());
    }

    @Test
    @DisplayName("an unmapped physical type → STRING placeholder for human normalization")
    void unrecognizedFallsBackToString() {
        ReversedColumn c = JdbcTypeReverse.reverse(Types.OTHER, 0, null);
        assertEquals(FieldType.STRING, c.fieldType(), "placeholder so the model stays usable");
        assertNull(c.length());
    }

    @Test
    @DisplayName("reverse agrees with FieldType's declared java.sql.Types (forward/reverse consistency)")
    void agreesWithFieldTypeDeclaredSqlType() {
        // For each primitive FieldType, the JDBC type it DECLARES must reverse back to it — so the forward
        // (FieldType→SQL) and this reverse cannot silently disagree.
        for (FieldType ft : new FieldType[]{FieldType.STRING, FieldType.INTEGER, FieldType.LONG,
                FieldType.DOUBLE, FieldType.BIG_DECIMAL, FieldType.BOOLEAN,
                FieldType.DATE, FieldType.DATE_TIME, FieldType.TIME}) {
            ReversedColumn c = JdbcTypeReverse.reverse(ft.getSqlType(), null, null);
            assertEquals(ft, c.fieldType(),
                    "JDBC type declared by " + ft + " must reverse back to it");
        }
    }
}
