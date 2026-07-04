package io.softa.starter.metadata.scanner;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.DatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the surrogate-FK population SQL: one statement per child table, flavor-aware,
 * app-scoped, single bind parameter. Asserts shape (not exact text) so SQL rewording stays low-friction.
 */
class SysReferenceSqlTest {

    @Test
    void mysql_emitsThreeAppScopedJoinUpdates() {
        List<String> sql = SysReferenceSql.populateStatements(DatabaseType.MYSQL);
        assertEquals(3, sql.size());
        // sys_field.model_id, sys_model_index.model_id, sys_option_item.option_set_id — in that order.
        assertSetsColumn(sql.get(0), "sys_field", "model_id");
        assertSetsColumn(sql.get(1), "sys_model_index", "model_id");
        assertSetsColumn(sql.get(2), "sys_option_item", "option_set_id");
        for (String s : sql) {
            assertTrue(s.contains(" JOIN "), "MySQL uses multi-table UPDATE..JOIN: " + s);
            assertOneAppScopedParam(s);
        }
    }

    @Test
    void postgresql_emitsThreeAppScopedFromUpdates() {
        List<String> sql = SysReferenceSql.populateStatements(DatabaseType.POSTGRESQL);
        assertEquals(3, sql.size());
        assertSetsColumn(sql.get(0), "sys_field", "model_id");
        assertSetsColumn(sql.get(1), "sys_model_index", "model_id");
        assertSetsColumn(sql.get(2), "sys_option_item", "option_set_id");
        for (String s : sql) {
            assertTrue(s.contains(" SET ") && s.contains(" FROM "),
                    "PostgreSQL uses UPDATE..SET..FROM..WHERE: " + s);
            assertOneAppScopedParam(s);
        }
    }

    @Test
    void unsupportedFlavor_isRejected() {
        // Only MySQL / PostgreSQL have a DdlDialect; anything else must fail loudly, not silently no-op.
        assertThrows(IllegalStateException.class,
                () -> SysReferenceSql.populateStatements(DatabaseType.ORACLE));
    }

    private static void assertSetsColumn(String sql, String table, String column) {
        assertTrue(sql.contains(table), "targets " + table + ": " + sql);
        assertTrue(sql.contains(column + " = "), "sets " + column + ": " + sql);
    }

    /** Each statement binds the app code exactly once and confines the update to that app. */
    private static void assertOneAppScopedParam(String sql) {
        assertEquals(1, sql.chars().filter(c -> c == '?').count(), "exactly one bind param (appCode): " + sql);
        assertTrue(sql.contains("app_code = ?"), "app-scoped WHERE: " + sql);
        // Null-safe difference guard: only rows whose FK actually changes are touched,
        // so an idempotent boot rewrites (and reports) nothing.
        assertTrue(sql.contains("<=>") || sql.contains("IS DISTINCT FROM"),
                "difference-guarded UPDATE: " + sql);
    }
}
