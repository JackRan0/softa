package io.softa.starter.metadata.ddl.dialect;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.util.StringUtils;

import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.DefaultValueLiterals;
import io.softa.starter.metadata.ddl.context.FieldDdlCtx;
import io.softa.starter.metadata.ddl.context.ModelDdlCtx;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;
import io.softa.starter.metadata.ddl.spi.DdlTemplateBundle;
import io.softa.starter.metadata.ddl.spi.FieldDdlDefault;

/**
 * Shared template-backed DDL dialect support.
 *
 * <p>Depends on the framework-level {@link DdlMetadataResolver} SPI. Callers
 * construct dialects with the resolver for the current lane; dialects are not
 * Spring beans and do not pick a metadata source implicitly.
 */
public abstract class AbstractTemplateDdlDialect implements DdlDialect {

    private final DdlMetadataResolver metadataResolver;

    protected AbstractTemplateDdlDialect(DdlMetadataResolver metadataResolver) {
        Assert.notNull(metadataResolver, "DdlMetadataResolver must not be null");
        this.metadataResolver = metadataResolver;
    }

    protected abstract String getTemplateDir();

    protected abstract String getDefaultDbType(FieldType fieldType);

    protected String buildTypeDeclaration(FieldDdlCtx field) {
        if (!StringUtils.hasText(field.getDbType())) {
            return null;
        }
        StringBuilder declaration = new StringBuilder(field.getDbType());
        if (field.getLength() != null && field.getLength() > 0) {
            declaration.append("(").append(field.getLength());
            if (field.getScale() != null && field.getScale() > 0) {
                declaration.append(",").append(field.getScale());
            }
            declaration.append(")");
        }
        return declaration.toString();
    }

    @Override
    public StringBuilder createTableDDL(ModelDdlCtx model) {
        Assert.notEmpty(model.getCreatedFields(),
                "The fields of the model {0} to be published cannot be empty!", model.getModelName());
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DdlTemplateBundle::createTableTemplate,
                getTemplateDir() + "CreateTable.peb", context));
    }

    @Override
    public StringBuilder alterTableDDL(ModelDdlCtx model) {
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DdlTemplateBundle::alterTableTemplate,
                getTemplateDir() + "AlterTable.peb", context));
    }

    @Override
    public StringBuilder dropTableDDL(ModelDdlCtx model) {
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DdlTemplateBundle::dropTableTemplate,
                getTemplateDir() + "DropTable.peb", context));
    }

    @Override
    public StringBuilder alterIndexDDL(ModelDdlCtx model) {
        Map<String, Object> context = buildContext(model);
        return new StringBuilder(renderSqlTemplate(DdlTemplateBundle::alterIndexTemplate,
                getTemplateDir() + "AlterIndex.peb", context));
    }

    private Map<String, Object> buildContext(ModelDdlCtx model) {
        prepareModel(model);
        return Map.of("model", model);
    }

    private void prepareModel(ModelDdlCtx model) {
        Map<FieldType, String> columnTypes = metadataResolver.getColumnTypes(getDatabaseType());
        Map<FieldType, FieldDdlDefault> defaults = metadataResolver.getFieldDefaults();
        prepareFields(model.getCreatedFields(), columnTypes, defaults);
        prepareFields(model.getDeletedFields(), columnTypes, defaults);
        prepareFields(model.getUpdatedFields(), columnTypes, defaults);
        prepareFields(model.getRenamedFields(), columnTypes, defaults);
    }

    private void prepareFields(List<FieldDdlCtx> fields,
                               Map<FieldType, String> columnTypes,
                               Map<FieldType, FieldDdlDefault> defaults) {
        for (FieldDdlCtx field : fields) {
            FieldType fieldType = field.getFieldType();
            if (fieldType == null) {
                continue;
            }
            applyFieldDefaults(field, defaults.get(fieldType));
            if (!StringUtils.hasText(field.getDbType())) {
                field.setDbType(resolveDbType(fieldType, columnTypes));
            }
            field.setTypeDeclaration(buildTypeDeclaration(field));
            field.setDefaultValueLiteral(DefaultValueLiterals.render(
                    fieldType, field.getDefaultValue(), field.getColumnName()));
        }
    }

    private void applyFieldDefaults(FieldDdlCtx field, FieldDdlDefault fieldDefault) {
        if (fieldDefault == null) {
            return;
        }
        if (field.getLength() == null) {
            field.setLength(fieldDefault.length());
        }
        if (field.getScale() == null) {
            field.setScale(fieldDefault.scale());
        }
        if (!StringUtils.hasText(field.getDefaultValue())) {
            field.setDefaultValue(fieldDefault.defaultValue());
        }
    }

    private String resolveDbType(FieldType fieldType, Map<FieldType, String> columnTypes) {
        String columnType = columnTypes.get(fieldType);
        if (StringUtils.hasText(columnType)) {
            return columnType;
        }
        return getDefaultDbType(fieldType);
    }

    private String renderSqlTemplate(Function<DdlTemplateBundle, String> templateGetter,
                                     String fallbackPath,
                                     Map<String, Object> context) {
        String databaseTemplate = metadataResolver.getDdlTemplates(getDatabaseType())
                .map(templateGetter)
                .orElse(null);
        if (StringUtils.hasText(databaseTemplate)) {
            return TemplateEngine.render(databaseTemplate, context);
        }
        return TemplateEngine.renderFilePath(fallbackPath, context);
    }
}
