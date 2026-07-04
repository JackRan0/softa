package io.softa.starter.metadata.ddl;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SqlStatements} — the statement splitter behind
 * per-statement DDL execution and classification.
 */
class SqlStatementsTest {

    @Test
    void singleStatementStaysWhole() {
        List<String> out = SqlStatements.split("CREATE TABLE t (id BIGINT);");
        assertEquals(1, out.size());
        assertEquals("CREATE TABLE t (id BIGINT);", out.get(0));
    }

    @Test
    void multiStatementSplitsOnTopLevelSemicolons() {
        List<String> out = SqlStatements.split("""
                ALTER TABLE t DROP INDEX i;
                ALTER TABLE t ADD INDEX i (a, b);
                """);
        assertEquals(2, out.size());
        assertEquals("ALTER TABLE t DROP INDEX i;", out.get(0));
        assertEquals("ALTER TABLE t ADD INDEX i (a, b);", out.get(1));
    }

    @Test
    void semicolonInsideSingleQuotedLiteralIsNotASplit() {
        List<String> out = SqlStatements.split(
                "ALTER TABLE t ADD COLUMN c VARCHAR(64) COMMENT 'a; b''s; c';\nDROP INDEX i;");
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("'a; b''s; c'"));
        assertEquals("DROP INDEX i;", out.get(1));
    }

    @Test
    void semicolonInsideCommentsIsNotASplit() {
        List<String> out = SqlStatements.split("""
                /* header; with semicolon */
                CREATE TABLE t (id BIGINT); -- trailing; note
                CREATE INDEX i ON t (id);
                """);
        assertEquals(2, out.size());
        // Leading block comment stays attached to its statement.
        assertTrue(out.get(0).startsWith("/* header; with semicolon */"));
        assertTrue(out.get(0).endsWith("(id BIGINT);"));
        // The line comment belongs to the tail of statement 1's chunk → statement 2 clean.
        assertTrue(out.get(1).endsWith("CREATE INDEX i ON t (id);"));
    }

    @Test
    void commentOnlyChunksAreDropped() {
        List<String> out = SqlStatements.split("""
                /* Table indexes for model: Customer */
                ALTER TABLE t ADD INDEX i (a);
                /* nothing after this */
                """);
        assertEquals(1, out.size());
    }

    @Test
    void trailingStatementWithoutSemicolonIsKept() {
        List<String> out = SqlStatements.split("CREATE TABLE a (x INT);\nCREATE TABLE b (y INT)");
        assertEquals(2, out.size());
        assertEquals("CREATE TABLE b (y INT)", out.get(1));
    }

    @Test
    void blankInputYieldsNothing() {
        assertTrue(SqlStatements.split(null).isEmpty());
        assertTrue(SqlStatements.split("   \n ").isEmpty());
        assertTrue(SqlStatements.split(" ; ; ").isEmpty());
    }
}
