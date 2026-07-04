package io.softa.starter.studio.release.connector;

import io.softa.framework.orm.enums.FieldType;

/**
 * The logical type a physical database column reverse-engineers to — the unit
 * {@link JdbcTypeReverse} produces and {@code JdbcSchemaConnector.readSchema} (P3.4) turns into a design
 * field row.
 *
 * @param fieldType the (primitive) logical field type the column maps to; {@code STRING} as the
 *                  placeholder for an unrecognized JDBC type
 * @param length    the physical width to carry (VARCHAR width / DECIMAL precision), or {@code null} to
 *                  take the type-default
 * @param scale     the DECIMAL scale, or {@code null}
 */
public record ReversedColumn(FieldType fieldType, Integer length, Integer scale) {
}
