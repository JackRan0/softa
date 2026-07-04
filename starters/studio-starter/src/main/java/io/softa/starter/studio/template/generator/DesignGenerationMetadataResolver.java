package io.softa.starter.studio.template.generator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;
import io.softa.starter.metadata.ddl.spi.DdlTemplateBundle;
import io.softa.starter.metadata.ddl.spi.FieldDdlDefault;
import io.softa.starter.studio.template.entity.*;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * Resolve generation templates and field mappings from studio metadata storage.
 *
 * <p>Extends {@link DdlMetadataResolver}: implementations of this interface
 * automatically expose the framework-level SPI via default-method adapters
 * below, so DDL dialects in {@code framework/softa-orm/ddl/} can depend on
 * the simple SPI types rather than studio's design-time entities.
 */
public interface DesignGenerationMetadataResolver extends DdlMetadataResolver {

    Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType);

    Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType);

    Map<FieldType, DesignFieldCodeMapping> getFieldCodeMappings(DesignCodeLang codeLang);

    List<DesignCodeTemplate> getCodeTemplates(DesignCodeLang codeLang);

    List<DesignCodeLang> getAvailableCodeLangs();

    // ----- DdlMetadataResolver adapters -----

    @Override
    default Map<FieldType, String> getColumnTypes(DatabaseType databaseType) {
        return getFieldDbMappings(databaseType).entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().getColumnType() != null)
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().getColumnType()));
    }

    @Override
    default Map<FieldType, FieldDdlDefault> getFieldDefaults() {
        // Per-FieldType length/scale defaults are builtin Code (the single source across
        // both lanes), no longer a design_* table — so an annotation field and a no-code field of the
        // same FieldType get the identical type-default width. Named, field-referenceable domains
        // (DesignFieldDomain) replace the old per-FieldType design table.
        return BuiltinDdlMetadataResolver.builtinFieldDefaults();
    }

    @Override
    default Optional<DdlTemplateBundle> getDdlTemplates(DatabaseType databaseType) {
        return getSqlTemplate(databaseType).map(t -> new DdlTemplateBundle(
                t.getCreateTableTemplate(),
                t.getAlterTableTemplate(),
                t.getDropTableTemplate(),
                t.getAlterIndexTemplate()));
    }
}
