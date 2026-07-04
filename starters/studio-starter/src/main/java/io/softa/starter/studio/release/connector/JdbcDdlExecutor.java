package io.softa.starter.studio.release.connector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.ExternalException;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes native DDL against an <b>external</b> JDBC database — the apply path of
 * {@link JdbcSchemaConnector}. A raw JDBC target has no Softa runtime / signed envelope, so this opens a
 * short-lived connection straight to the env's {@code jdbcUrl} (publish is not high-frequency, so a
 * connection pool would be over-engineering) and runs each statement in order. Deliberately NOT the
 * metadata-starter {@code DdlExecutor}, which is bound to this runtime's own {@code JdbcTemplate}.
 *
 * <p><b>No rollback</b>: DDL auto-commits on most databases, so a mid-list failure cannot be rolled back.
 * We fail loud with how far we got (the publish records the activity as FAILURE; the operator reruns the
 * remaining DDL after fixing the cause). Stateless / no Spring deps beyond being a bean for injection.
 */
@Slf4j
@Component
public class JdbcDdlExecutor {

    /**
     * Open a connection to {@code jdbcUrl} and execute {@code ddlStatements} in order. No-op for an empty
     * list (a converge whose only changes are non-DDL, or nothing to do).
     *
     * @throws ExternalException if the connection or any statement fails (carries how many succeeded)
     */
    public void execute(String jdbcUrl, String username, String password, List<String> ddlStatements) {
        if (ddlStatements == null || ddlStatements.isEmpty()) {
            return;
        }
        int executed = 0;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            for (String sql : ddlStatements) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                    executed++;
                }
            }
            log.info("JDBC DDL: executed {} statement(s) against {}.", executed, jdbcUrl);
        } catch (SQLException e) {
            // DDL is not transactional on most DBs — the statements already executed stand. Fail loud.
            log.error("JDBC DDL execution failed against {} after {}/{} statement(s).",
                    jdbcUrl, executed, ddlStatements.size(), e);
            throw new ExternalException(
                    "JDBC DDL execution failed against {0} after {1}/{2} statement(s): {3}",
                    jdbcUrl, executed, ddlStatements.size(), e.getMessage());
        }
    }
}
