package io.softa.starter.studio.release.upgrade;

import java.util.ArrayList;
import java.util.List;

import io.softa.starter.metadata.ddl.SqlStatements;

/**
 * Splits a multi-statement DDL string (as produced by {@code MetadataChangeDdlRenderer}) into
 * individual SQL statements suitable for wire transmission and per-statement
 * execution / result tracking.
 *
 * <p>The {@code MetadataChangeDdlRenderer} Pebble templates emit DDL like:
 *
 * <pre>
 * CREATE TABLE foo (...);
 * ALTER TABLE bar ADD COLUMN baz INT;
 * </pre>
 *
 * <p>Statement boundaries are delegated to metadata-starter's {@link SqlStatements}
 * lexer so semicolons inside comments, quoted literals, or quoted identifiers do
 * not corrupt the split. The public contract here preserves the historical Studio
 * wire shape by returning statements without the final terminator.
 */
public final class DdlSqlSplitter {

    private DdlSqlSplitter() {}

    /**
     * Split the concatenation of {@code tableDdl} and {@code indexDdl} into a list
     * of trimmed, non-empty SQL statements. Either argument may be {@code null}.
     */
    public static List<String> split(String tableDdl, String indexDdl) {
        List<String> out = new ArrayList<>();
        appendStatements(out, tableDdl);
        appendStatements(out, indexDdl);
        return out;
    }

    private static void appendStatements(List<String> out, String ddl) {
        SqlStatements.split(ddl).stream()
                .map(DdlSqlSplitter::withoutTerminator)
                .forEach(out::add);
    }

    private static String withoutTerminator(String statement) {
        String trimmed = statement.strip();
        return trimmed.endsWith(";")
                ? trimmed.substring(0, trimmed.length() - 1).strip()
                : trimmed;
    }
}
