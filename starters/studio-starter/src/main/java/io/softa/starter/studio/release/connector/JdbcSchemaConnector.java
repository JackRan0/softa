package io.softa.starter.studio.release.connector;

import java.util.Map;

import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.studio.release.desired.AggregateChecksumDiff;
import io.softa.starter.studio.release.desired.AggregateSelection;
import io.softa.starter.studio.release.desired.DesignRows;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * {@link Connector} for a <b>raw JDBC database</b> target — the runtime-agnostic connector
 * that lets studio model / forward-engineer / reverse-engineer any JDBC database, not just a Softa
 * runtime. Bound to a {@link DesignAppEnv} whose {@code connectorType = JDBC} (jdbcUrl + credentials).
 *
 * <p><b>Forward (P3.2)</b>: {@link #apply} is <b>DDL-only</b> — only the rendered DDL is
 * executed against the external DB (via {@link JdbcDdlExecutor}); the per-row {@code changes()} are
 * dropped (a raw DB has no {@code sys_*} rows). No {@code autoExecuteDDL} gate — the connector IS the
 * executor.
 *
 * <p><b>Reverse</b>: {@link #readSchema} / {@link #readChecksums} read the physical schema via
 * {@link JdbcSchemaReader} (model/field only — no optionSet). There is no cheap checksum
 * gate: {@code readChecksums} hashes the full read. So the physical read is <b>memoized</b> on
 * this instance — and because the connector is built per-converge by {@code ConnectorFactory.forEnv}, the
 * gate ({@code readChecksums}) and the row read ({@code readSchema}) share exactly one physical read per
 * converge. {@code appCode} is ignored (a raw DB has no app scoping — the {@code jdbcUrl} is the target).
 *
 * <p>Stateful (the memo) — unlike the stateless {@code SoftaRuntimeConnector} record; hence a class.
 */
public final class JdbcSchemaConnector implements Connector {

    private final DdlDialect dialect;
    private final JdbcDdlExecutor ddlExecutor;
    private final JdbcSchemaReader schemaReader;
    private final DesignAppEnv env;

    /** Memoized physical read — one per connector instance, i.e. one per converge. */
    private DesignRows cachedSchema;

    public JdbcSchemaConnector(DdlDialect dialect, JdbcDdlExecutor ddlExecutor,
                               JdbcSchemaReader schemaReader, DesignAppEnv env) {
        this.dialect = dialect;
        this.ddlExecutor = ddlExecutor;
        this.schemaReader = schemaReader;
        this.env = env;
    }

    @Override
    public DdlDialect dialect() {
        return dialect;
    }

    @Override
    public RuntimeChecksumsDTO readChecksums(String appCode) {
        DesignRows schema = schema();
        // Hash the model aggregates exactly as the design side does, so the gate's classification is
        // comparable. No option-set checksums — a physical DB has none.
        return new RuntimeChecksumsDTO(
                AggregateChecksumDiff.modelChecksums(schema.models(), schema.fields(), schema.indexes()),
                Map.of());
    }

    @Override
    public DesignRows readSchema(String appCode) {
        return schema();
    }

    @Override
    public DesignRows readSchema(String appCode, AggregateSelection selection) {
        return schema().select(selection.modelNames(), selection.optionSetCodes());
    }

    @Override
    public void apply(String appCode, MetadataChangeSet changeSet) {
        // DDL-only: a raw DB has no sys_* rows, so the per-row changes are dropped; only the
        // rendered DDL is executed against the external database.
        ddlExecutor.execute(env.getJdbcUrl(), env.getJdbcUsername(), env.getJdbcPassword(), changeSet.ddl());
    }

    /** The physical schema, read once and memoized for this connector's (one-converge) lifetime. */
    private DesignRows schema() {
        if (cachedSchema == null) {
            cachedSchema = schemaReader.read(env.getJdbcUrl(), env.getJdbcUsername(), env.getJdbcPassword());
        }
        return cachedSchema;
    }
}
