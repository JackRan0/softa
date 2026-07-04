package io.softa.starter.metadata.ddl;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DdlErrorClassifier} against synthetic driver errors —
 * the MySQL / PostgreSQL codes the H2-backed e2e suite cannot produce.
 */
class DdlErrorClassifierTest {

    private static BadSqlGrammarException error(String sqlState, int vendorCode) {
        return new BadSqlGrammarException("task", "SQL",
                new SQLException("boom", sqlState, vendorCode));
    }

    @Test
    void duplicateCreateCodesAreIdempotent() {
        assertTrue(DdlErrorClassifier.isIdempotentDuplicate(error(null, 1050)));  // MySQL table
        assertTrue(DdlErrorClassifier.isIdempotentDuplicate(error(null, 1060)));  // MySQL column
        assertTrue(DdlErrorClassifier.isIdempotentDuplicate(error(null, 1061)));  // MySQL index
        assertTrue(DdlErrorClassifier.isIdempotentDuplicate(error("42P07", 0)));  // PG table/index
        assertTrue(DdlErrorClassifier.isIdempotentDuplicate(error("42701", 0)));  // PG column
        assertTrue(DdlErrorClassifier.isIdempotentDuplicate(error(null, 42121))); // H2 column
        assertFalse(DdlErrorClassifier.isIdempotentDuplicate(error("42000", 1064)));
    }

    @Test
    void columnRenameRetryCodes() {
        assertTrue(DdlErrorClassifier.isColumnRenameAlreadyApplied(error(null, 1054)));
        assertTrue(DdlErrorClassifier.isColumnRenameAlreadyApplied(error("42703", 0)));
        assertTrue(DdlErrorClassifier.isColumnRenameAlreadyApplied(error(null, 42122))); // H2
        assertFalse(DdlErrorClassifier.isColumnRenameAlreadyApplied(error(null, 1060)));
    }

    @Test
    void tableRenameRetryCodes() {
        assertTrue(DdlErrorClassifier.isTableRenameAlreadyApplied(error(null, 1146)));
        assertTrue(DdlErrorClassifier.isTableRenameAlreadyApplied(error("42P01", 0)));
        assertTrue(DdlErrorClassifier.isTableRenameAlreadyApplied(error(null, 42102)));  // H2
        assertFalse(DdlErrorClassifier.isTableRenameAlreadyApplied(error(null, 1054)));
    }

    @Test
    void indexDropRetryCodes() {
        assertTrue(DdlErrorClassifier.isIndexDropAlreadyApplied(error(null, 1091)));     // MySQL
        assertTrue(DdlErrorClassifier.isIndexDropAlreadyApplied(error("42704", 0)));     // PG
        assertTrue(DdlErrorClassifier.isIndexDropAlreadyApplied(error(null, 42112)));    // H2
        assertFalse(DdlErrorClassifier.isIndexDropAlreadyApplied(error(null, 1061)));
    }
}
