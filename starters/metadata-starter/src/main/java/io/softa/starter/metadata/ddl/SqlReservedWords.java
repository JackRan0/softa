package io.softa.starter.metadata.ddl;

import java.util.Locale;
import java.util.Set;

/**
 * Reserved SQL keywords that cannot be used as a bare (unquoted) table / column /
 * index identifier on the supported flavors. The DDL templates and the runtime
 * query layer both interpolate identifiers unquoted, so a reserved-word name would
 * render syntactically broken SQL — the parser rejects it at scan time instead,
 * where the error names the Java declaration to fix.
 *
 * <p>Contents = the MySQL 8.x reserved-keyword list ∪ the PostgreSQL reserved
 * category. Both flavors are always guarded regardless of the runtime's actual
 * database: metadata is portable across flavors, so a name legal on the current
 * engine but broken on the other would trap a later migration. Non-reserved
 * keywords (e.g. {@code status}, {@code level}, {@code name}) stay usable.
 */
public final class SqlReservedWords {

    private SqlReservedWords() {}

    private static final Set<String> RESERVED = Set.of(
            // ---- MySQL 8.x reserved keywords ----
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive",
            "before", "between", "bigint", "binary", "blob", "both", "by",
            "call", "cascade", "case", "change", "char", "character", "check", "collate",
            "column", "condition", "constraint", "continue", "convert", "create", "cross",
            "cube", "cume_dist", "current_date", "current_time", "current_timestamp",
            "current_user", "cursor",
            "database", "databases", "day_hour", "day_microsecond", "day_minute", "day_second",
            "dec", "decimal", "declare", "default", "delayed", "delete", "dense_rank", "desc",
            "describe", "deterministic", "distinct", "distinctrow", "div", "double", "drop", "dual",
            "each", "else", "elseif", "empty", "enclosed", "escaped", "except", "exists", "exit",
            "explain",
            "false", "fetch", "first_value", "float", "float4", "float8", "for", "force",
            "foreign", "from", "fulltext", "function",
            "generated", "get", "grant", "group", "grouping", "groups",
            "having", "high_priority", "hour_microsecond", "hour_minute", "hour_second",
            "if", "ignore", "in", "index", "infile", "inner", "inout", "insensitive", "insert",
            "int", "int1", "int2", "int3", "int4", "int8", "integer", "intersect", "interval",
            "into", "io_after_gtids", "io_before_gtids", "is", "iterate",
            "join", "json_table",
            "key", "keys", "kill",
            "lag", "last_value", "lateral", "lead", "leading", "leave", "left", "like", "limit",
            "linear", "lines", "load", "localtime", "localtimestamp", "lock", "long", "longblob",
            "longtext", "loop", "low_priority",
            "master_bind", "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob",
            "mediumint", "mediumtext", "middleint", "minute_microsecond", "minute_second", "mod",
            "modifies",
            "natural", "not", "no_write_to_binlog", "nth_value", "ntile", "null", "numeric",
            "of", "on", "optimize", "optimizer_costs", "option", "optionally", "or", "order",
            "out", "outer", "outfile", "over",
            "partition", "percent_rank", "precision", "primary", "procedure", "purge",
            "range", "rank", "read", "reads", "read_write", "real", "recursive", "references",
            "regexp", "release", "rename", "repeat", "replace", "require", "resignal", "restrict",
            "return", "revoke", "right", "rlike", "row", "rows", "row_number",
            "schema", "schemas", "second_microsecond", "select", "sensitive", "separator", "set",
            "show", "signal", "smallint", "spatial", "specific", "sql", "sqlexception", "sqlstate",
            "sqlwarning", "sql_big_result", "sql_calc_found_rows", "sql_small_result", "ssl",
            "starting", "stored", "straight_join", "system",
            "table", "terminated", "then", "tinyblob", "tinyint", "tinytext", "to", "trailing",
            "trigger", "true",
            "undo", "union", "unique", "unlock", "unsigned", "update", "usage", "use", "using",
            "utc_date", "utc_time", "utc_timestamp",
            "values", "varbinary", "varchar", "varcharacter", "varying", "virtual",
            "when", "where", "while", "window", "with", "write",
            "xor",
            "year_month",
            "zerofill",
            // ---- PostgreSQL reserved keywords not already above ----
            "analyse", "any", "array", "asymmetric", "authorization",
            "cast", "collation", "concurrently", "current_catalog", "current_role",
            "current_schema", "deferrable", "do", "end", "freeze", "full",
            "ilike", "initially", "isnull", "notnull", "offset", "only", "overlaps",
            "placing", "returning", "session_user", "similar", "some", "symmetric",
            "tablesample", "user", "variadic", "verbose");

    /** Whether {@code identifier} (any case) is reserved on MySQL or PostgreSQL. */
    public static boolean isReserved(String identifier) {
        return identifier != null && RESERVED.contains(identifier.toLowerCase(Locale.ROOT));
    }
}
