package io.softa.framework.web.handler;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UniqueViolationParser}: extracts the violated index name (bare, unqualified) from a
 * PostgreSQL (SQLState 23505) or MySQL (SQLState 23000 + vendor code 1062) unique violation.
 * Anything else returns {@code null}.
 */
class UniqueViolationParserTest {

    /** Wrap in a cause chain to assert the parser walks to the root SQLException. */
    private static Throwable wrap(SQLException sqle) {
        return new IllegalStateException("update failed", new RuntimeException(sqle));
    }

    @Test
    void postgres_uniqueViolation_extractsConstraintFromMessage() {
        // No PostgreSQL driver on the test classpath, so the reflective structured
        // accessor is unavailable and the parser falls back to message parsing.
        SQLException sqle = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uk_customer_email\"", "23505");
        assertEquals("uk_customer_email", UniqueViolationParser.parseConstraintName(wrap(sqle)));
    }

    @Test
    void postgres_uniqueViolationUnparseableMessage_returnsNull() {
        // 23505 but the constraint name cannot be recovered — caller falls back to the raw message.
        SQLException sqle = new SQLException("duplicate key value", "23505");
        assertNull(UniqueViolationParser.parseConstraintName(sqle));
    }

    @Test
    void mysqlDuplicate_tableQualified_stripsToBareName() {
        // MySQL 8+ reports 'table.index'; the parser strips the qualifier to the bare name.
        SQLException sqle = new SQLException(
                "Duplicate entry 'a@b.com' for key 'customer.uk_customer_email'", "23000", 1062);
        assertEquals("uk_customer_email", UniqueViolationParser.parseConstraintName(wrap(sqle)));
    }

    @Test
    void mysqlDuplicate_schemaTableQualified_stripsToBareName() {
        SQLException sqle = new SQLException(
                "Duplicate entry 'x' for key 'app_db.customer.uk_customer_email'", "23000", 1062);
        assertEquals("uk_customer_email", UniqueViolationParser.parseConstraintName(wrap(sqle)));
    }

    @Test
    void mysqlDuplicate_unqualified_returnedAsIs() {
        // Older MySQL reports just the index name (no qualifier).
        SQLException sqle = new SQLException(
                "Duplicate entry 'x' for key 'uk_customer_email'", "23000", 1062);
        assertEquals("uk_customer_email", UniqueViolationParser.parseConstraintName(wrap(sqle)));
    }

    @Test
    void mysqlIntegrityViolation_notDuplicateKey_returnsNull() {
        // 23000 but a non-1062 vendor code (e.g. FK violation 1452) is not a unique violation.
        SQLException sqle = new SQLException("Cannot add or update a child row", "23000", 1452);
        assertNull(UniqueViolationParser.parseConstraintName(wrap(sqle)));
    }

    @Test
    void nonUniqueViolation_returnsNull() {
        // PostgreSQL foreign_key_violation 23503 — not a unique violation.
        SQLException pgFk = new SQLException("violates foreign key constraint", "23503");
        assertNull(UniqueViolationParser.parseConstraintName(pgFk));
    }

    @Test
    void noSqlExceptionInChain_returnsNull() {
        assertNull(UniqueViolationParser.parseConstraintName(new RuntimeException("boom")));
    }
}
