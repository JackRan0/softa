package io.softa.starter.metadata.ddl;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import io.softa.framework.orm.enums.FieldType;

/**
 * Renders a declared {@code defaultValue} (the raw <em>value</em>, as written on
 * {@code @Field} / a design field) into a SQL literal for the {@code DEFAULT}
 * clause, by the field's type.
 *
 * <p>The templates interpolate {@code DEFAULT {{ field.defaultValue }}} verbatim,
 * so without this step a string default like {@code ACTIVE} lands unquoted in the
 * DDL and fails the boot with a syntax error. Rules (deterministic, no guessing):
 * <ul>
 *   <li>blank → {@code null} (template omits the DEFAULT clause);</li>
 *   <li>a whitelisted SQL expression keyword ({@code CURRENT_TIMESTAMP},
 *       {@code CURRENT_DATE}, {@code CURRENT_TIME}, {@code NOW()},
 *       {@code LOCALTIME}, {@code LOCALTIMESTAMP}) passes through unquoted;</li>
 *   <li>numeric types must parse as a number — anything else fails fast with the
 *       column named (a typo here must not reach the database);</li>
 *   <li>boolean accepts {@code true/false/1/0} and normalizes to the
 *       {@code TRUE}/{@code FALSE} keywords (valid for MySQL TINYINT, PostgreSQL
 *       BOOLEAN and H2 alike);</li>
 *   <li>everything else (string / option / date families, CSV-backed multi-values)
 *       renders as a single-quoted literal with {@code '} doubled — the same
 *       escaping the {@code sqlLiteral} template filter applies to COMMENTs.</li>
 * </ul>
 *
 * <p>TO_ONE FKs reach the dialect with {@code fieldType} already folded to the
 * referenced column's physical type by the context builders, so they classify
 * like the scalar they mirror; an unresolved relation falls into the quoted
 * branch, which is safe for the VARCHAR it would render as.
 */
public final class DefaultValueLiterals {

    private DefaultValueLiterals() {}

    private static final Set<String> EXPRESSION_KEYWORDS = Set.of(
            "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
            "NOW()", "LOCALTIME", "LOCALTIMESTAMP");

    private static final Set<FieldType> NUMERIC_TYPES = Set.of(
            FieldType.INTEGER, FieldType.LONG, FieldType.DOUBLE,
            FieldType.BIG_DECIMAL, FieldType.FILE);

    /**
     * @param fieldType  the rendered field type (FK mirror already folded in)
     * @param rawValue   the declared default value, verbatim
     * @param columnName for the fail-fast message
     * @return the SQL literal to interpolate after {@code DEFAULT}, or null when blank
     * @throws IllegalStateException when a numeric / boolean default doesn't parse
     */
    public static String render(FieldType fieldType, String rawValue, String columnName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String value = rawValue.trim();
        if (EXPRESSION_KEYWORDS.contains(value.toUpperCase(Locale.ROOT))) {
            return value.toUpperCase(Locale.ROOT);
        }
        if (fieldType != null && NUMERIC_TYPES.contains(fieldType)) {
            try {
                new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "defaultValue '" + rawValue + "' on column " + columnName
                                + " is not a valid " + fieldType + " literal.");
            }
            return value;
        }
        if (fieldType == FieldType.BOOLEAN) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "true", "1" -> "TRUE";
                case "false", "0" -> "FALSE";
                default -> throw new IllegalStateException(
                        "defaultValue '" + rawValue + "' on column " + columnName
                                + " is not a valid BOOLEAN literal (true/false/1/0).");
            };
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
