package io.softa.starter.metadata.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Bidirectional typed&harr;JDBC converter for a single {@code sys_*} column.
 *
 * <p>Each {@link io.softa.starter.metadata.catalog.SysColumn} pairs a column
 * name + typed accessor with one {@code Codec}, so the writer (typed&rarr;DB),
 * the loader (DB&rarr;typed) and the diff (typed equality) all derive from the
 * same definition.
 */
public interface Codec {

    /** Typed value &rarr; JDBC bind value (INSERT / UPDATE). */
    Object toDb(Object typed);

    /** Read {@code column} from the row and convert to the typed value (load). */
    Object fromDb(ResultSet rs, String column) throws SQLException;

    /** Typed equality for change detection. Defaults to value-equality. */
    default boolean typedEquals(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
