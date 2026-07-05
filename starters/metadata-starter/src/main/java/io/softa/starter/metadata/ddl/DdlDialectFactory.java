package io.softa.starter.metadata.ddl;

import io.softa.framework.base.exception.ConfigurationException;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.ddl.dialect.MySqlDdlDialect;
import io.softa.starter.metadata.ddl.dialect.PostgreSqlDdlDialect;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;

/**
 * Creates DDL dialects for a specific metadata source.
 *
 * <p>The caller chooses the resolver explicitly: annotation/runtime paths use
 * {@link BuiltinDdlMetadataResolver}, while studio JDBC paths pass a
 * design-backed resolver. Keeping that choice at the call site avoids global
 * Spring bean ordering and "which resolver won?" ambiguity.
 */
public final class DdlDialectFactory {

    private DdlDialectFactory() {
    }

    public static DdlDialect builtin(DatabaseType databaseType) {
        return create(databaseType, BuiltinDdlMetadataResolver.INSTANCE);
    }

    public static DdlDialect create(DatabaseType databaseType, DdlMetadataResolver metadataResolver) {
        if (databaseType == null) {
            throw new ConfigurationException("Database type must not be null");
        }
        if (metadataResolver == null) {
            throw new ConfigurationException("DdlMetadataResolver must not be null");
        }
        return switch (databaseType) {
            case MYSQL -> new MySqlDdlDialect(metadataResolver);
            case POSTGRESQL -> new PostgreSqlDdlDialect(metadataResolver);
            default -> throw new ConfigurationException("DDL dialect of database {0} is not currently supported!",
                    databaseType);
        };
    }
}
