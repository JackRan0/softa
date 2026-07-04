package io.softa.starter.studio.release.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.ExternalException;

/**
 * {@link JdbcDdlExecutor} runs native DDL against an external JDBC database over
 * a short-lived connection. Exercised against an in-memory H2 instance — proving the real execution path
 * (connect → execute in order → close), the empty-list no-op, and the loud, progress-bearing failure.
 */
class JdbcDdlExecutorTest {

    private final JdbcDdlExecutor executor = new JdbcDdlExecutor();

    @BeforeAll
    static void seedSystemConfig() {
        // ExternalException construction reaches I18n via BaseException, which needs SystemConfig.env.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @Test
    @DisplayName("executes the DDL statements in order against the external database")
    void executesAgainstExternalDb() throws SQLException {
        String url = "jdbc:h2:mem:p32_ok;DB_CLOSE_DELAY=-1";

        executor.execute(url, "sa", "", List.of(
                "CREATE TABLE widget (id INT PRIMARY KEY)",
                "ALTER TABLE widget ADD name VARCHAR(50)"));

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'WIDGET'")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "both columns (id + name) were created");
        }
    }

    @Test
    @DisplayName("empty statement list is a no-op (no connection opened)")
    void emptyListIsNoOp() {
        // An unreachable URL would fail if a connection were opened; an empty list must not open one.
        executor.execute("jdbc:h2:mem:never_opened", "sa", "", List.of());
    }

    @Test
    @DisplayName("a failed statement fails loud, carrying how far it got (no DDL rollback)")
    void failureIsLoudWithProgress() {
        String url = "jdbc:h2:mem:p32_fail;DB_CLOSE_DELAY=-1";

        ExternalException ex = assertThrows(ExternalException.class, () -> executor.execute(url, "sa", "",
                List.of("CREATE TABLE ok_table (id INT)", "THIS IS NOT VALID SQL")));

        assertTrue(ex.getMessage().contains("1/2"),
                "reports the one statement that succeeded before the failure: " + ex.getMessage());
    }
}
