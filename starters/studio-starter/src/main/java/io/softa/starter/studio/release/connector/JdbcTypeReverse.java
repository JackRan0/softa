package io.softa.starter.studio.release.connector;

import java.sql.Types;
import java.util.Map;

import io.softa.framework.orm.enums.FieldType;

/**
 * Reverse type mapping for JDBC reverse-engineering: a physical column's JDBC
 * type ({@link java.sql.Types}, as {@code DatabaseMetaData.getColumns} reports it, already parsed — no
 * type-string parsing) → its canonical <b>primitive</b> {@link FieldType} + length / scale.
 *
 * <p>Keyed on the JDBC type code rather than inverting the {@code DesignFieldDbMapping} table because:
 * (1) {@link FieldType} already declares its {@code java.sql.Types} (so the forward FieldType→SQL and this
 * reverse agree by construction — pinned by the test); (2) it is seed-independent (works before any
 * {@code DesignFieldDbMapping} rows exist); (3) it covers the JDBC types a driver actually reports
 * (CHAR / SMALLINT / NUMERIC / CLOB / …), more than {@code FieldType} itself enumerates.
 *
 * <p><b>Lossy by design</b> (multi-to-one): many logical types share one physical type (all the
 * VARCHAR-backed {@code OPTION} / {@code MULTI_*} / {@code FILE} / relation types), and those logical
 * refinements are NOT inferable from physical schema — so a reversed column is always a <i>primitive</i>
 * (a reversed {@code STRING} the operator later refines into an {@code OPTION} in studio). An unmapped
 * type falls back to a {@code STRING} placeholder for human normalization. DB-specific type names
 * (e.g. MySQL {@code TINYINT(1)} as boolean) are a future
 * {@code DesignFieldDbMapping} reverse-override layer (P3.6).
 */
public final class JdbcTypeReverse {

    private JdbcTypeReverse() {
    }

    /** Curated {@code java.sql.Types} → primitive {@link FieldType}. */
    private static final Map<Integer, FieldType> PRIMITIVE = Map.ofEntries(
            // character / large-object → STRING (width carried; very wide → renders TEXT on render side)
            Map.entry(Types.CHAR, FieldType.STRING),
            Map.entry(Types.VARCHAR, FieldType.STRING),
            Map.entry(Types.NCHAR, FieldType.STRING),
            Map.entry(Types.NVARCHAR, FieldType.STRING),
            Map.entry(Types.LONGVARCHAR, FieldType.STRING),
            Map.entry(Types.LONGNVARCHAR, FieldType.STRING),
            Map.entry(Types.CLOB, FieldType.STRING),
            Map.entry(Types.NCLOB, FieldType.STRING),
            // integers (small ints widen to INTEGER; DB-specific TINYINT(1)=bool is a P3.6 override)
            Map.entry(Types.TINYINT, FieldType.INTEGER),
            Map.entry(Types.SMALLINT, FieldType.INTEGER),
            Map.entry(Types.INTEGER, FieldType.INTEGER),
            Map.entry(Types.BIGINT, FieldType.LONG),
            // exact numeric → BigDecimal (precision + scale carried)
            Map.entry(Types.DECIMAL, FieldType.BIG_DECIMAL),
            Map.entry(Types.NUMERIC, FieldType.BIG_DECIMAL),
            // approximate numeric → Double
            Map.entry(Types.REAL, FieldType.DOUBLE),
            Map.entry(Types.FLOAT, FieldType.DOUBLE),
            Map.entry(Types.DOUBLE, FieldType.DOUBLE),
            // boolean
            Map.entry(Types.BOOLEAN, FieldType.BOOLEAN),
            Map.entry(Types.BIT, FieldType.BOOLEAN),
            // temporal
            Map.entry(Types.DATE, FieldType.DATE),
            Map.entry(Types.TIME, FieldType.TIME),
            Map.entry(Types.TIME_WITH_TIMEZONE, FieldType.TIME),
            Map.entry(Types.TIMESTAMP, FieldType.DATE_TIME),
            Map.entry(Types.TIMESTAMP_WITH_TIMEZONE, FieldType.DATE_TIME));

    /**
     * Reverse a physical column to its logical {@link ReversedColumn}.
     *
     * @param jdbcType the {@code java.sql.Types} code ({@code DatabaseMetaData.getColumns} DATA_TYPE)
     * @param size     COLUMN_SIZE (VARCHAR width / DECIMAL precision), or {@code null}
     * @param scale    DECIMAL_DIGITS, or {@code null}
     */
    public static ReversedColumn reverse(int jdbcType, Integer size, Integer scale) {
        FieldType fieldType = PRIMITIVE.get(jdbcType);
        if (fieldType == null) {
            // Unrecognized physical type — placeholder STRING for human normalization.
            return new ReversedColumn(FieldType.STRING, null, null);
        }
        Integer length = null;
        Integer resultScale = null;
        if (fieldType == FieldType.STRING) {
            length = positive(size);                     // carry the actual VARCHAR/CLOB width
        } else if (fieldType == FieldType.BIG_DECIMAL) {
            length = positive(size);                     // precision
            resultScale = nonNegative(scale);            // scale 0 (DECIMAL(n,0)) is legitimate — preserve it
        }
        return new ReversedColumn(fieldType, length, resultScale);
    }

    /** Precision / width must be strictly positive to carry meaning; else defer to the render-side default. */
    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    /** Scale is meaningful at 0 (an exact integer DECIMAL); only a missing/negative value defers to default. */
    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }
}
