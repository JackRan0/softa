package io.softa.starter.metadata.ddl;

import java.util.List;

/**
 * One rendered DDL unit produced by {@link DdlOrchestrator}, carrying the
 * individual statements the template output splits into. {@code DdlOrchestrator.apply}
 * (scanner boot) executes the {@link Kind#autoExecute() auto-executable} units
 * <b>statement by statement</b> — each statement is classified independently, so an
 * "already applied" duplicate on one statement never swallows the statements after it —
 * and logs the rest as warn-only manual-SQL hints.
 *
 * @param kind       what the unit does and whether it auto-executes
 * @param label      short human label, e.g. {@code "CREATE TABLE customer"}
 * @param statements the individual SQL statements — executed one by one for auto
 *                   kinds, shown joined as a copy-paste manual hint for warn-only kinds
 */
public record RenderedDdl(Kind kind, String label, List<String> statements) {

    public RenderedDdl {
        statements = List.copyOf(statements);
    }

    /** Convenience: split {@code sql} into statements via {@link SqlStatements#split}. */
    public static RenderedDdl of(Kind kind, String label, String sql) {
        return new RenderedDdl(kind, label, SqlStatements.split(sql));
    }

    /** The full SQL text (all statements joined) — for warn-only hints and logs. */
    public String sql() {
        return String.join("\n", statements);
    }

    /**
     * DDL categories. Additive / widening changes auto-execute; data-bearing
     * <em>destructive</em> changes (any DROP) are warn-only — never auto-executed,
     * surfaced for explicit human action (mirrors the scanner DDL policy).
     *
     * <p>Declared renames get their own kinds so the "source already renamed" retry
     * degradation ({@code DdlErrorClassifier}) can be scoped to exactly the statement
     * class that legitimately produces it: {@link #DECLARED_COLUMN_RENAME}
     * ({@code renamedFrom} on a field / a changed {@code columnName} → {@code CHANGE
     * COLUMN old new}) tolerates "unknown column <old>", {@link #DECLARED_TABLE_RENAME}
     * ({@code renamedFrom} on a model → {@code RENAME TABLE old TO new}) tolerates
     * "missing table <old>". A plain {@link #ALTER_TABLE} gets <b>no</b> such tolerance —
     * a MODIFY on a genuinely missing column must fail the boot, not be mistaken for an
     * applied rename. {@link #UNDECLARED_TABLE_RENAME} is a bare {@code tableName}-attribute
     * change inferred from the diff: it could equally be a silent data divorce, so it stays
     * warn-only and is surfaced as a copy-paste hint.
     */
    public enum Kind {
        CREATE_TABLE(true),
        ALTER_TABLE(true),
        ALTER_INDEX(true),
        DROP_TABLE(false),
        DROP_COLUMN(false),
        DROP_INDEX(false),
        DECLARED_COLUMN_RENAME(true),
        DECLARED_TABLE_RENAME(true),
        UNDECLARED_TABLE_RENAME(false);

        private final boolean autoExecute;

        Kind(boolean autoExecute) {
            this.autoExecute = autoExecute;
        }

        public boolean autoExecute() {
            return autoExecute;
        }
    }

    /** Whether {@code apply} executes this unit (vs. logging it warn-only). */
    public boolean autoExecute() {
        return kind.autoExecute();
    }
}
