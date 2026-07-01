package io.softa.framework.web.handler;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the violated index name from a unique-constraint violation, reduced to the
 * <b>bare</b> (unqualified) name. Index names are globally unique across all models, so the
 * bare name alone identifies the index for both databases:
 *
 * <ul>
 *   <li>PostgreSQL (SQLState {@code 23505}) — the constraint name via the driver's structured
 *       accessor, or parsed from the message; already unqualified.</li>
 *   <li>MySQL (SQLState {@code 23000} + vendor code {@code 1062}) — the key name from the
 *       {@code Duplicate entry '...' for key '...'} message; MySQL may qualify it as
 *       {@code table.index} / {@code db.table.index}, so the qualifier is stripped.</li>
 * </ul>
 *
 * <p>Returns {@code null} for anything that is not a recoverable unique violation; the caller
 * then falls back to the raw driver message. The MySQL branch relies on the (English) message
 * text — under a non-English {@code lc_messages} it may miss, degrading to that same fallback.
 */
public final class UniqueViolationParser {

    /** PostgreSQL {@code unique_violation} SQLState. */
    private static final String PG_UNIQUE_VIOLATION = "23505";

    /** MySQL integrity-constraint-violation SQLState; a duplicate key is vendor code 1062. */
    private static final String MYSQL_INTEGRITY_VIOLATION = "23000";
    private static final int MYSQL_DUP_ENTRY = 1062;

    /** PostgreSQL fallback: {@code ... violates unique constraint "uk_x"}. */
    private static final Pattern PG_CONSTRAINT = Pattern.compile("unique constraint \"([^\"]+)\"");

    /** MySQL: {@code Duplicate entry '...' for key 'db.table.index'} (name may be qualified). */
    private static final Pattern MYSQL_KEY = Pattern.compile("for key '([^']+)'");

    private UniqueViolationParser() {}

    /**
     * @param ex the caught throwable (typically a Spring {@code DuplicateKeyException} /
     *           {@code DataIntegrityViolationException} wrapping a {@code SQLException})
     * @return the violated index name (bare, unqualified), or {@code null} if it is not a
     *         recoverable unique violation
     */
    public static String parseConstraintName(Throwable ex) {
        SQLException sqle = findSqlException(ex);
        if (sqle == null) {
            return null;
        }
        String raw = extractRawName(sqle);
        return raw == null ? null : stripQualifier(raw);
    }

    private static String extractRawName(SQLException sqle) {
        String sqlState = sqle.getSQLState();
        if (PG_UNIQUE_VIOLATION.equals(sqlState)) {
            return extractPostgresConstraint(sqle);
        }
        if (MYSQL_INTEGRITY_VIOLATION.equals(sqlState) && sqle.getErrorCode() == MYSQL_DUP_ENTRY) {
            return firstGroup(MYSQL_KEY, sqle.getMessage());
        }
        return null;
    }

    /**
     * PostgreSQL exposes the constraint name structurally via
     * {@code PSQLException.getServerErrorMessage().getConstraint()}; the driver is
     * optional on the classpath, so read it reflectively and fall back to parsing the
     * message text.
     */
    private static String extractPostgresConstraint(SQLException sqle) {
        try {
            Object serverError = sqle.getClass().getMethod("getServerErrorMessage").invoke(sqle);
            if (serverError != null) {
                Object constraint = serverError.getClass().getMethod("getConstraint").invoke(serverError);
                if (constraint instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Driver/method absent or shape differs — fall through to message parsing.
        }
        return firstGroup(PG_CONSTRAINT, sqle.getMessage());
    }

    private static String firstGroup(Pattern pattern, String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Reduce a possibly-qualified name to the bare index name: MySQL reports
     * {@code table.index} / {@code db.table.index}; PostgreSQL reports the bare constraint name
     * (no dots), so this is a no-op there. Index names never contain a dot.
     */
    private static String stripQualifier(String name) {
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? name : name.substring(lastDot + 1);
    }

    private static SQLException findSqlException(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqle) {
                return sqle;
            }
        }
        return null;
    }
}
