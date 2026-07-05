package io.softa.starter.studio.template.generator;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.template.entity.*;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * Reads generation templates and field mappings from studio metadata storage.
 *
 * <p>This is the studio catalog API used by {@link CodeGenerator}. DDL rendering
 * adapts it through {@link DesignDdlMetadataResolver} only at the connector
 * boundary, so studio metadata does not become a global DDL resolver bean.
 */
public interface DesignGenerationMetadataResolver {

    Map<FieldType, DesignFieldDbMapping> getFieldDbMappings(DatabaseType databaseType);

    Optional<DesignSqlTemplate> getSqlTemplate(DatabaseType databaseType);

    Map<FieldType, DesignFieldCodeMapping> getFieldCodeMappings(DesignCodeLang codeLang);

    List<DesignCodeTemplate> getCodeTemplates(DesignCodeLang codeLang);

    List<DesignCodeLang> getAvailableCodeLangs();
}
