package io.softa.starter.metadata.ddl;

/**
 * {@link DdlOrchestrator} end-to-end tests against H2 in MySQL compatibility mode,
 * driving the MySQL DDL dialect.
 *
 * <p>The production-style URL ({@code jdbc:mysql://...}) is what
 * {@code DBUtil.parseDatabaseType} reads to pick the dialect; the actual SQL
 * still executes on the H2 in-memory engine.
 */
class MySqlDdlOrchestratorTest extends AbstractDdlOrchestratorTest {

    @Override
    protected String h2JdbcUrl() {
        return "jdbc:h2:mem:ddl_mysql_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    @Override
    protected String productionJdbcUrl() {
        return "jdbc:mysql://localhost/test";
    }
}
