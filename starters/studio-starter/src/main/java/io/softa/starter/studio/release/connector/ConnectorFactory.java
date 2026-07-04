package io.softa.starter.studio.release.connector;

import java.util.List;

import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.metadata.ddl.BuiltinDdlDialects;
import io.softa.starter.metadata.ddl.dialect.DdlDialectRegistry;
import io.softa.starter.metadata.ddl.dialect.MySqlDdlDialect;
import io.softa.starter.metadata.ddl.dialect.PostgreSqlDdlDialect;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.enums.ConnectorType;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;
import io.softa.starter.studio.template.generator.DesignGenerationMetadataResolver;

/**
 * Builds the {@link Connector} for a {@link DesignAppEnv}. Selection is data-driven on the
 * env's {@code connectorType} (null ⇒ {@link ConnectorType#SOFTA}).
 *
 * <p>The two connectors render DDL on different dialects (by-connector resolver):
 * <ul>
 *   <li><b>Softa</b> → the shared {@link BuiltinDdlDialects#registry() builtin registry} (compile-time
 *       annotation knowledge, identical to the boot scanner), binding the env + the shared
 *       {@link RemoteApiClient} so the connector owns all signed runtime calls.</li>
 *   <li><b>JDBC</b> → a {@code design_*}-backed registry built here from the
 *       {@link DesignGenerationMetadataResolver} (forward FieldType→SQL via {@code DesignFieldDbMapping}),
 *       plus the {@link JdbcDdlExecutor} that runs the rendered DDL against the external database. Built
 *       explicitly (not the ambiguous Spring {@code DdlDialectRegistry} bean — both the builtin and the
 *       design resolver are beans) so the resolver is unambiguous.</li>
 * </ul>
 */
@Component
public class ConnectorFactory {

    private final DdlDialectRegistry builtinRegistry = BuiltinDdlDialects.registry();
    /** design_*-backed registry (forward FieldType→SQL via DesignFieldDbMapping) — for JDBC targets. */
    private final DdlDialectRegistry designRegistry;
    private final RemoteApiClient remoteApiClient;
    private final JdbcDdlExecutor jdbcDdlExecutor;
    private final JdbcSchemaReader jdbcSchemaReader;

    public ConnectorFactory(RemoteApiClient remoteApiClient,
                            DesignGenerationMetadataResolver designResolver,
                            JdbcDdlExecutor jdbcDdlExecutor,
                            JdbcSchemaReader jdbcSchemaReader) {
        this.remoteApiClient = remoteApiClient;
        this.jdbcDdlExecutor = jdbcDdlExecutor;
        this.jdbcSchemaReader = jdbcSchemaReader;
        this.designRegistry = new DdlDialectRegistry(List.of(
                new MySqlDdlDialect(designResolver),
                new PostgreSqlDdlDialect(designResolver)));
    }

    public Connector forEnv(DesignAppEnv env) {
        Assert.notNull(env, "env must not be null");
        ConnectorType type = env.getConnectorType() == null ? ConnectorType.SOFTA : env.getConnectorType();
        return switch (type) {
            case SOFTA -> softaConnector(env);
            case JDBC -> jdbcConnector(env);
        };
    }

    private Connector softaConnector(DesignAppEnv env) {
        // databaseType is a required field on the env; the notNull guard is defensive.
        DatabaseType dbType = env.getDatabaseType();
        Assert.notNull(dbType,
                "Env {0} has no databaseType — cannot render DDL for its runtime.", env.getId());
        // Per-connectorType validation: a SOFTA env addresses its runtime through the signed upgrade API,
        // so the endpoint is mandatory here — it is no longer @Field(required), since a JDBC env has none.
        Assert.notBlank(env.getUpgradeEndpoint(),
                "SOFTA env {0} has no upgradeEndpoint — cannot address its runtime.", env.getId());
        return new SoftaRuntimeConnector(builtinRegistry.getDialect(dbType), remoteApiClient, env);
    }

    private Connector jdbcConnector(DesignAppEnv env) {
        // A raw JDBC target: flavor + connection come from the env's embedded jdbc* fields
        // (no separate DesignConnector entity). The dialect renders on the design-backed mapping;
        // apply executes the rendered DDL against the external DB.
        DatabaseType dbType = env.getDatabaseType();
        Assert.notNull(dbType,
                "JDBC env {0} has no databaseType — cannot select its DDL dialect.", env.getId());
        Assert.notBlank(env.getJdbcUrl(),
                "JDBC env {0} has no jdbcUrl — cannot connect to its database.", env.getId());
        return new JdbcSchemaConnector(designRegistry.getDialect(dbType),
                jdbcDdlExecutor, jdbcSchemaReader, env);
    }
}
