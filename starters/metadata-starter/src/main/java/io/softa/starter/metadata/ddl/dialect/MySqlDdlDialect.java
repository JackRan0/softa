package io.softa.starter.metadata.ddl.dialect;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.context.FieldDdlCtx;
import io.softa.starter.metadata.ddl.mapping.MySqlDataType;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;

/**
 * MySQL DDL dialect using Pebble templates.
 *
 * <p>Depends on {@link DdlMetadataResolver}.
 */
@Component
public class MySqlDdlDialect extends AbstractTemplateDdlDialect {

    private static final String TEMPLATE_DIR = "templates/sql/mysql/";

    /**
     * Max VARCHAR length in characters under utf8mb4 (65535-byte row limit /
     * 4 bytes per char). A declared {@code @Field(length)} above this cannot
     * be a VARCHAR — render TEXT instead (e.g. {@code SysField.expression}
     * with {@code length = 20000} → {@code TEXT}).
     */
    private static final int MAX_VARCHAR_LENGTH = 16383;

    public MySqlDdlDialect(DdlMetadataResolver metadataResolver) {
        super(metadataResolver);
    }

    @Override
    protected String buildTypeDeclaration(FieldDdlCtx field) {
        if ("VARCHAR".equals(field.getDbType())
                && field.getLength() != null && field.getLength() > MAX_VARCHAR_LENGTH) {
            // Switch dbType too so template guards keyed on VARCHAR (e.g. the
            // required → DEFAULT '' clause, illegal on TEXT) don't apply.
            field.setDbType("TEXT");
            return "TEXT";
        }
        return super.buildTypeDeclaration(field);
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.MYSQL;
    }

    @Override
    protected String getTemplateDir() {
        return TEMPLATE_DIR;
    }

    @Override
    protected String getDefaultDbType(FieldType fieldType) {
        return MySqlDataType.getDbType(fieldType);
    }
}
