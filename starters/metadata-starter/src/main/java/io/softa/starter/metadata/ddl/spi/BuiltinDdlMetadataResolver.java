package io.softa.starter.metadata.ddl.spi;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.mapping.MySqlDataType;
import io.softa.starter.metadata.ddl.mapping.PostgreSqlDataType;

/**
 * Built-in fallback {@link DdlMetadataResolver} for apps that don't include a
 * richer resolver (e.g. studio-starter's {@code DesignGenerationMetadataResolverImpl}
 * which sources column types from the {@code DesignFieldDbMapping} table). The per-FieldType
 * length/scale defaults here are the single cross-lane source: the studio resolver
 * delegates to {@link #builtinFieldDefaults()} rather than a {@code design_*} table.
 *
 * <p>{@code @ConditionalOnMissingBean(DdlMetadataResolver.class)} ensures this
 * fallback only kicks in when no other implementation is present. Studio's
 * impl wins when both are on classpath.
 *
 * <p>Provides hardcoded defaults for the common {@link FieldType}s that need
 * column length/scale (STRING / BIG_DECIMAL / multi-*). Other types
 * (INTEGER / LONG / BOOLEAN / DATE / DATE_TIME / TIME / JSON) don't need
 * declared lengths under the framework's column-type map.
 *
 * <p>Without this fallback, {@code MySqlDdlDialect} / {@code PostgreSqlDdlDialect}
 * Spring constructors fail with "No qualifying bean of type 'DdlMetadataResolver'
 * available" in apps that include {@code softa-orm} but not {@code studio-starter}.
 */
@Component
@ConditionalOnMissingBean(DdlMetadataResolver.class)
public class BuiltinDdlMetadataResolver implements DdlMetadataResolver {

    private static final Map<FieldType, FieldDdlDefault> DEFAULTS = new EnumMap<>(FieldType.class);

    static {
        // Length defaults — sized to typical ERP / SaaS data.
        // Repo convention: 64 is the canonical "undeclared" length (STRING /
        // OPTION); fields needing anything else declare @Field(length=...)
        // explicitly. MULTI_STRING and ORDERS standardize on 256.
        DEFAULTS.put(FieldType.STRING, new FieldDdlDefault(64, null, null));
        DEFAULTS.put(FieldType.MULTI_STRING, new FieldDdlDefault(256, null, null));
        DEFAULTS.put(FieldType.OPTION, new FieldDdlDefault(64, null, null));
        DEFAULTS.put(FieldType.MULTI_OPTION, new FieldDdlDefault(255, null, null));
        DEFAULTS.put(FieldType.MULTI_FILE, new FieldDdlDefault(1024, null, null));
        DEFAULTS.put(FieldType.FILTERS, new FieldDdlDefault(512, null, null));
        DEFAULTS.put(FieldType.ORDERS, new FieldDdlDefault(256, null, null));

        // Precision / scale defaults for DECIMAL-backed types, sized to the
        // repo's two semantic clusters: DOUBLE = measurements / durations
        // (24,2); BIG_DECIMAL = money / prices (32,8). Declare explicitly for
        // anything else (e.g. millisecond durations: length = 24, scale = 3).
        DEFAULTS.put(FieldType.BIG_DECIMAL, new FieldDdlDefault(32, 8, null));
        DEFAULTS.put(FieldType.DOUBLE, new FieldDdlDefault(24, 2, null));

        // Types that DON'T need explicit defaults (their column type doesn't
        // take length/scale): INTEGER, LONG, BOOLEAN, DATE, DATE_TIME, TIME,
        // JSON, MANY_TO_ONE, ONE_TO_ONE, DTO, FILE (BIGINT FileRecord id).
        // Omitted from map → resolver
        // returns null defaults → dialect renders without (N) suffix.
    }

    @Override
    public Map<FieldType, String> getColumnTypes(DatabaseType databaseType) {
        return switch (databaseType) {
            case MYSQL -> MySqlDataType.FIELD_TYPE_MAP;
            case POSTGRESQL -> PostgreSqlDataType.FIELD_TYPE_MAP;
            default -> Map.of();
        };
    }

    @Override
    public Map<FieldType, FieldDdlDefault> getFieldDefaults() {
        return DEFAULTS;
    }

    /**
     * Static access to the builtin type-default for one {@link FieldType}, or
     * {@code null} when the type takes no length/scale.
     *
     * <p>Used by {@code AnnotationParser} to resolve the default at the
     * <b>metadata layer</b> (parse time) so {@code sys_field.length/scale} is
     * authoritative rather than left null for the DDL layer to fill. This is
     * sound for the annotation lane specifically: the scanner / platform apply
     * always render DDL through this builtin resolver (never the pluggable
     * studio resolver — see {@code BuiltinDdlDialects}), so the builtin default
     * <i>is</i> the effective width for annotation-sourced fields. The studio
     * (no-code) lane keeps sourcing its own per-flavor defaults at render time.
     */
    public static FieldDdlDefault builtinDefaultFor(FieldType fieldType) {
        return DEFAULTS.get(fieldType);
    }

    /**
     * The full builtin per-{@link FieldType} default map (unmodifiable). The single source of per-FieldType
     * length/scale defaults across both lanes: the studio resolver delegates here rather than
     * sourcing per-FieldType defaults from a {@code design_*} table, so a no-code field's type-default width
     * matches an annotation field's exactly.
     */
    public static Map<FieldType, FieldDdlDefault> builtinFieldDefaults() {
        return Collections.unmodifiableMap(DEFAULTS);
    }

    @Override
    public Optional<DdlTemplateBundle> getDdlTemplates(DatabaseType databaseType) {
        // Empty → dialect falls back to classpath:templates/sql/<db>/*.peb
        return Optional.empty();
    }
}
