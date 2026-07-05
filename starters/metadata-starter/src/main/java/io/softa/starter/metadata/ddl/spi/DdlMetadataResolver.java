package io.softa.starter.metadata.ddl.spi;

import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;

/**
 * SPI used by DDL dialects to resolve database type / default-value / template
 * overrides per database flavor.
 *
 * <p>Decouples DDL rendering from the storage of mappings and templates
 * (builtin code, studio's design-time DB, config file, in-memory, etc.).
 * Callers pass the appropriate resolver when creating a dialect; there is no
 * process-wide "current" resolver.
 *
 * <p>This clean SPI lets the entire {@code ddl/} render layer (context /
 * dialect / mapping) live in {@code metadata-starter} and be reused by
 * both {@code studio-starter}'s {@code MetadataChangeDdlRendererImpl} and
 * {@code metadata-starter}'s scanner-side {@code DdlOrchestrator}.
 */
public interface DdlMetadataResolver {

    /**
     * Per-database mapping from {@link FieldType} to SQL column type literal
     * (e.g. {@code VARCHAR} / {@code BIGINT} / {@code NUMERIC}).
     */
    Map<FieldType, String> getColumnTypes(DatabaseType databaseType);

    /**
     * Per-{@link FieldType} default length / scale / defaultValue used when
     * a {@code FieldDdlCtx} doesn't specify them.
     */
    Map<FieldType, FieldDdlDefault> getFieldDefaults();

    /**
     * Optional override of the built-in {@code .peb} DDL templates per
     * database. Empty = use built-in templates from
     * {@code classpath:templates/sql/<db>/}.
     */
    Optional<DdlTemplateBundle> getDdlTemplates(DatabaseType databaseType);
}
