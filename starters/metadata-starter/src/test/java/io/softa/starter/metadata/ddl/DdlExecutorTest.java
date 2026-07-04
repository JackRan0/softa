package io.softa.starter.metadata.ddl;

import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.starter.metadata.dto.DdlStatementResult;
import io.softa.starter.metadata.dto.DdlStatementStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2-backed coverage of {@link DdlExecutor} — verifies fail-fast, idempotent
 * skip recognition, and the actedAt timestamp population.
 */
class DdlExecutorTest {

    private DdlExecutor executor;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // DB_CLOSE_DELAY=-1 keeps the mem DB alive between the separate connections
        // JdbcTemplate opens for each execute(); without it, statement[0]'s CREATE
        // TABLE is dropped before statement[1] runs and the whole batch fails.
        ds.setURL("jdbc:h2:mem:ddl_executor_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        executor = new DdlExecutor(new JdbcTemplate(ds));
    }

    @Test
    void emptyStatementsReturnsEmptyList() {
        assertTrue(executor.executeAll(null).isEmpty());
        assertTrue(executor.executeAll(List.of()).isEmpty());
    }

    @Test
    void allStatementsSucceed() {
        List<DdlStatementResult> results = executor.executeAll(List.of(
                "CREATE TABLE foo (id BIGINT PRIMARY KEY, name VARCHAR(64))",
                "ALTER TABLE foo ADD COLUMN created_at TIMESTAMP",
                "CREATE INDEX idx_foo_name ON foo(name)"
        ));

        assertEquals(3, results.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, results.get(i).sequence());
            assertEquals(DdlStatementStatus.SUCCESS, results.get(i).status());
            assertNotNull(results.get(i).executedAt(), "SUCCESS must carry executedAt");
            assertNull(results.get(i).errorMessage());
        }
    }

    @Test
    void duplicateColumnIsSkippedNotFailed() {
        executor.executeAll(List.of(
                "CREATE TABLE foo (id BIGINT PRIMARY KEY)"));

        // Second batch tries to re-add a column that already exists — H2 raises its
        // 42121 duplicate-column code, which the classifier recognises alongside the
        // MySQL / PostgreSQL equivalents. Treated as SKIPPED_IDEMPOTENT, not FAILED —
        // so execution continues to subsequent statements.
        List<DdlStatementResult> results = executor.executeAll(List.of(
                "ALTER TABLE foo ADD COLUMN created_at TIMESTAMP",
                "ALTER TABLE foo ADD COLUMN created_at TIMESTAMP"
        ));

        assertEquals(DdlStatementStatus.SUCCESS, results.get(0).status());
        assertEquals(DdlStatementStatus.SKIPPED_IDEMPOTENT, results.get(1).status());
        assertEquals(1, results.get(1).sequence());
    }

    @Test
    void failedStatementTriggersFailFastForRemaining() {
        List<DdlStatementResult> results = executor.executeAll(List.of(
                "CREATE TABLE foo (id BIGINT PRIMARY KEY)",
                "ALTER TABLE nonexistent_table ADD COLUMN bogus INT",
                "CREATE INDEX idx_foo_id ON foo(id)"
        ));

        assertEquals(3, results.size());
        // First — SUCCESS
        assertEquals(DdlStatementStatus.SUCCESS, results.get(0).status());
        // Second — FAILED, with error message captured
        assertEquals(DdlStatementStatus.FAILED, results.get(1).status());
        assertNotNull(results.get(1).errorMessage());
        // Third — NOT_ATTEMPTED, no execution time recorded
        assertEquals(DdlStatementStatus.NOT_ATTEMPTED, results.get(2).status());
        assertNull(results.get(2).executedAt());
        assertNull(results.get(2).errorMessage());
    }

    @Test
    void sequencePreservedAcrossStatuses() {
        List<DdlStatementResult> results = executor.executeAll(List.of(
                "CREATE TABLE foo (id BIGINT PRIMARY KEY)",
                "ALTER TABLE foo ADD COLUMN name VARCHAR(64)"
        ));
        assertEquals(0, results.get(0).sequence());
        assertEquals(1, results.get(1).sequence());
    }
}
