package io.softa.starter.metadata.ddl;

import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.ddl.dialect.PostgreSqlDdlDialect;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;

/**
 * {@link DdlOrchestrator} end-to-end tests against H2 in PostgreSQL compatibility mode,
 * driving the {@link PostgreSqlDdlDialect}.
 *
 * <p>The production-style URL ({@code jdbc:postgresql://...}) is what
 * {@code DBUtil.parseDatabaseType} reads to pick the dialect; the actual SQL
 * still executes on the H2 in-memory engine.
 */
class PostgreSqlDdlOrchestratorTest extends AbstractDdlOrchestratorTest {

    @Override
    protected String h2JdbcUrl() {
        return "jdbc:h2:mem:ddl_pg_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    @Override
    protected String productionJdbcUrl() {
        return "jdbc:postgresql://localhost/test";
    }

    @Override
    protected DdlDialect createDialect(BuiltinDdlMetadataResolver resolver) {
        return new PostgreSqlDdlDialect(resolver);
    }
}
