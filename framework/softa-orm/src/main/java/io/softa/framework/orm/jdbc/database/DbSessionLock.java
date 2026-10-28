package io.softa.framework.orm.jdbc.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.enums.DatabaseType;

/**
 * Cross-instance mutual exclusion for a startup window that writes to the database — the boot DDL window, the
 * startup seed load. Without it, N replicas starting together on the same fresh or drifted database race the
 * same writes.
 *
 * <p>Database-native session locks — MySQL {@code GET_LOCK} / PostgreSQL {@code pg_advisory_lock} — on a
 * <b>dedicated connection held for the lock's whole lifetime</b>: both engines scope the lock to the session, so
 * it must never be acquired through a pooled connection that the pool could hand to somebody else. The
 * application's own statements keep flowing through the normal pool. If the process dies mid-window the session
 * dies with it and the engine releases the lock — no lease bookkeeping.
 *
 * <p>The lock is named {@code softa:<key>:<database>}, so one key excludes only the instances working on the same
 * database. A well-behaved sibling finishes its window in seconds, so a wait that runs out its budget means
 * something is stuck — the start fails with a pointer rather than piling instances behind a wedged lock (the
 * supervisor's restart is the retry).
 *
 * <p>Unsupported dialects and test seams (no {@link DataSource}) return {@code null} — the caller proceeds
 * lockless, which is exactly the behavior before the lock.
 */
@Slf4j
public final class DbSessionLock implements AutoCloseable {

    private static final long PG_POLL_MILLIS = 500;

    private final Connection connection;
    private final DatabaseType type;
    private final String name;

    private DbSessionLock(Connection connection, DatabaseType type, String name) {
        this.connection = connection;
        this.type = type;
        this.name = name;
    }

    /**
     * Acquire the lock for {@code key}, waiting up to {@code timeoutSeconds}.
     *
     * @param key            what the lock guards, e.g. {@code boot-ddl}
     * @param timeoutSeconds how long to wait for an instance holding it
     * @return the held lock, or {@code null} when locking is not applicable (no datasource, unparseable URL, or a
     *         dialect without session locks)
     * @throws IllegalStateException on wait timeout or a lock-acquisition failure
     */
    public static DbSessionLock acquire(DataSource dataSource, String datasourceUrl, String key, int timeoutSeconds) {
        if (dataSource == null) {
            return null;
        }
        DatabaseType type;
        try {
            type = DBUtil.parseDatabaseType(datasourceUrl);
        } catch (RuntimeException e) {
            log.debug("DbSessionLock: datasource url not parseable ({}); proceeding lockless", e.getMessage());
            return null;
        }
        if (type != DatabaseType.MYSQL && type != DatabaseType.POSTGRESQL) {
            log.debug("DbSessionLock: no session-lock support for {}; proceeding lockless", type);
            return null;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            String name = "softa:" + key + ":" + scopeName(connection);
            boolean acquired = switch (type) {
                case MYSQL -> acquireMysql(connection, name, timeoutSeconds);
                case POSTGRESQL -> acquirePostgres(connection, name, timeoutSeconds);
                default -> throw new IllegalStateException("unreachable");
            };
            if (!acquired) {
                throw new IllegalStateException("Timed out after " + timeoutSeconds + "s waiting for the database "
                        + "lock '" + name + "' — another instance appears stuck while holding it. Check its logs, "
                        + "then restart this instance.");
            }
            log.info("DbSessionLock: acquired '{}'", name);
            DbSessionLock lock = new DbSessionLock(connection, type, name);
            connection = null;   // ownership transferred
            return lock;
        } catch (SQLException e) {
            // Distinguish "database unreachable" from genuine lock trouble: this can be the FIRST JDBC touch of
            // the start, so a network/credential failure surfaces here and a message that only says "lock"
            // reads as contention.
            throw new IllegalStateException(connection == null
                    ? "Could not obtain a JDBC connection to acquire the database lock for '" + key + "' — the "
                            + "database is unreachable or the credentials are wrong (check endpoint, security "
                            + "group, and datasource settings); this is not lock contention"
                    : "Failed to acquire the database lock for '" + key + "'", e);
        } finally {
            closeQuietly(connection);
        }
    }

    private static boolean acquireMysql(Connection connection, String name, int timeoutSeconds) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, name);
            ps.setInt(2, timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    /** {@code pg_advisory_lock} has no wait budget — poll the try-variant against a deadline. */
    private static boolean acquirePostgres(Connection connection, String name, int timeoutSeconds)
            throws SQLException {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (true) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return true;
                    }
                }
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(PG_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the database lock '" + name + "'", e);
            }
        }
    }

    /** Both drivers report the connected database through the catalog. */
    private static String scopeName(Connection connection) throws SQLException {
        String catalog = connection.getCatalog();
        return catalog == null || catalog.isBlank() ? "default" : catalog;
    }

    @Override
    public void close() {
        try (PreparedStatement ps = connection.prepareStatement(switch (type) {
            case MYSQL -> "SELECT RELEASE_LOCK(?)";
            case POSTGRESQL -> "SELECT pg_advisory_unlock(hashtext(?))";
            default -> throw new IllegalStateException("unreachable");
        })) {
            ps.setString(1, name);
            ps.executeQuery().close();
            log.info("DbSessionLock: released '{}'", name);
        } catch (SQLException e) {
            // The session's death releases the lock anyway — closing the connection below is the real release;
            // this explicit call just keeps the session reusable semantics tidy.
            log.warn("DbSessionLock: explicit release of '{}' failed ({}); closing the session releases it",
                    name, e.getMessage());
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("DbSessionLock: connection close failed: {}", e.getMessage());
            }
        }
    }
}
