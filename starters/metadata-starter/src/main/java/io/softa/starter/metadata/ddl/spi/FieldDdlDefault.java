package io.softa.starter.metadata.ddl.spi;

/**
 * Default DDL attributes for a {@link io.softa.framework.orm.enums.FieldType}
 * when not explicitly specified on the field's {@code FieldDdlCtx}.
 *
 * <p>Returned by {@link DdlMetadataResolver#getFieldDefaults()}.
 *
 * @param length       default column length (nullable)
 * @param scale        default decimal scale (nullable; for BIG_DECIMAL et al.)
 * @param defaultValue default value SQL expression (nullable)
 */
public record FieldDdlDefault(Integer length, Integer scale, String defaultValue) {}
