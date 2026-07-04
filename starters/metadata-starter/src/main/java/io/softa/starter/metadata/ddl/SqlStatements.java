package io.softa.starter.metadata.ddl;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits rendered DDL template output into individual statements so the executor
 * can run and classify them <b>one at a time</b>.
 *
 * <p>Why this exists: the Pebble templates legitimately render several statements
 * into one text (PostgreSQL per-column ALTER blocks, {@code COMMENT ON}, index
 * DROP-then-ADD rebuilds). Handing that text to a single
 * {@code Statement.execute()} breaks twice over — MySQL Connector/J rejects
 * multi-statement strings unless {@code allowMultiQueries=true}, and a single
 * per-string error classification lets an "already applied" duplicate on the
 * first statement silently swallow every statement after it.
 *
 * <p>Splitting is on top-level {@code ;}, respecting single-quoted literals
 * (with {@code ''} doubling — the {@code sqlLiteral} convention), double-quoted
 * identifiers, backtick identifiers, {@code --} line comments and block
 * comments. A chunk containing only whitespace and
 * comments is dropped; leading comments stay attached to their statement
 * (drivers accept them). The terminating {@code ;} is kept.
 */
public final class SqlStatements {

    private SqlStatements() {}

    public static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            if (c == '-' && next == '-') {
                i = appendUntil(sql, current, i, "\n");
            } else if (c == '/' && next == '*') {
                i = appendUntil(sql, current, i, "*/");
            } else if (c == '\'' || c == '"' || c == '`') {
                i = appendQuoted(sql, current, i, c);
            } else if (c == ';') {
                current.append(c);
                addIfStatement(out, current.toString());
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        addIfStatement(out, current.toString());
        return out;
    }

    /** Append from {@code start} through the end of {@code terminator} (or EOF). */
    private static int appendUntil(String sql, StringBuilder current, int start, String terminator) {
        int end = sql.indexOf(terminator, start + 2);
        end = end < 0 ? sql.length() : end + terminator.length();
        current.append(sql, start, end);
        return end;
    }

    /**
     * Append a quoted region delimited by {@code quote}. A doubled quote
     * ({@code ''} / {@code ""} / {@code ``}) is an escaped delimiter and stays
     * inside the region.
     */
    private static int appendQuoted(String sql, StringBuilder current, int start, char quote) {
        int i = start;
        current.append(sql.charAt(i++));
        while (i < sql.length()) {
            char c = sql.charAt(i);
            current.append(c);
            i++;
            if (c == quote) {
                if (i < sql.length() && sql.charAt(i) == quote) {
                    current.append(quote);   // doubled → escaped, keep scanning
                    i++;
                } else {
                    break;                   // closing delimiter
                }
            }
        }
        return i;
    }

    /** Keep a chunk only when something non-comment, non-whitespace remains. */
    private static void addIfStatement(List<String> out, String chunk) {
        String trimmed = chunk.strip();
        if (!trimmed.isEmpty() && !isCommentsOnly(trimmed)) {
            out.add(trimmed);
        }
    }

    private static boolean isCommentsOnly(String chunk) {
        int i = 0;
        int n = chunk.length();
        while (i < n) {
            char c = chunk.charAt(i);
            if (Character.isWhitespace(c) || c == ';') {
                i++;
            } else if (c == '-' && i + 1 < n && chunk.charAt(i + 1) == '-') {
                int nl = chunk.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
            } else if (c == '/' && i + 1 < n && chunk.charAt(i + 1) == '*') {
                int end = chunk.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                return false;
            }
        }
        return true;
    }
}
