package io.softa.starter.metadata.scanner;

import java.util.List;

import io.softa.framework.orm.enums.DatabaseType;

/**
 * Flavor-aware SQL that resolves the Sys* surrogate FK columns from their business-code
 * back-links: {@code sys_field.model_id} and {@code sys_model_index.model_id} from {@code model_name}
 * (→ {@code sys_model.id}), and {@code sys_option_item.option_set_id} from {@code option_set_code}
 * (→ {@code sys_option_set.id}).
 *
 * <p>The parser cannot stamp these at parse time — the parent's surrogate id is DB-assigned — so they
 * are EXCLUDED from the scanner diff ({@code SysCatalog.EXCLUDED}) and resolved by these UPDATE-joins
 * AFTER the rows are written. The statements are idempotent (a deterministic re-derivation from the
 * parent code) and scoped to one app: the join matches {@code app_code} on both sides and the WHERE
 * confines the target to this runtime's app, so a shared multi-app database cannot
 * cross-link a child to another app's model/option-set of the same name.
 *
 * <p>MySQL uses multi-table {@code UPDATE ... JOIN}; PostgreSQL uses {@code UPDATE ... SET ... FROM ...
 * WHERE}. Only these two flavors have a {@code DdlDialect} (BuiltinDdlDialects), so any other type is
 * rejected. Mirrors the reference seed SQL in {@code deploy/<app>/init_mysql/4.update_reference.dml.sql}.
 */
public final class SysReferenceSql {

    private SysReferenceSql() {}

    /**
     * The three FK-population statements for the given flavor, each taking a single bind parameter
     * (the app code). The caller runs {@code jdbcTemplate.update(sql, appCode)} for each.
     */
    public static List<String> populateStatements(DatabaseType type) {
        // The null-safe inequality guard (MySQL NOT(<=>), PostgreSQL IS DISTINCT FROM)
        // confines the UPDATE to rows whose FK actually differs: without it every boot
        // rewrites every row (PostgreSQL physically re-writes same-value updates) and
        // the "resolved N FK(s)" log reports the whole table instead of real changes.
        return switch (type) {
            case MYSQL -> List.of(
                    "UPDATE sys_field sf JOIN sys_model sm"
                            + " ON sf.model_name = sm.model_name AND sm.app_code = sf.app_code"
                            + " SET sf.model_id = sm.id"
                            + " WHERE sf.app_code = ? AND NOT (sf.model_id <=> sm.id)",
                    "UPDATE sys_model_index si JOIN sys_model sm"
                            + " ON si.model_name = sm.model_name AND sm.app_code = si.app_code"
                            + " SET si.model_id = sm.id"
                            + " WHERE si.app_code = ? AND NOT (si.model_id <=> sm.id)",
                    "UPDATE sys_option_item soi JOIN sys_option_set sos"
                            + " ON soi.option_set_code = sos.option_set_code AND sos.app_code = soi.app_code"
                            + " SET soi.option_set_id = sos.id"
                            + " WHERE soi.app_code = ? AND NOT (soi.option_set_id <=> sos.id)");
            case POSTGRESQL -> List.of(
                    "UPDATE sys_field sf SET model_id = sm.id FROM sys_model sm"
                            + " WHERE sf.model_name = sm.model_name AND sm.app_code = sf.app_code"
                            + " AND sf.app_code = ? AND sf.model_id IS DISTINCT FROM sm.id",
                    "UPDATE sys_model_index si SET model_id = sm.id FROM sys_model sm"
                            + " WHERE si.model_name = sm.model_name AND sm.app_code = si.app_code"
                            + " AND si.app_code = ? AND si.model_id IS DISTINCT FROM sm.id",
                    "UPDATE sys_option_item soi SET option_set_id = sos.id FROM sys_option_set sos"
                            + " WHERE soi.option_set_code = sos.option_set_code"
                            + " AND sos.app_code = soi.app_code AND soi.app_code = ?"
                            + " AND soi.option_set_id IS DISTINCT FROM sos.id");
            default -> throw new IllegalStateException(
                    "Sys* surrogate-FK population supports MySQL / PostgreSQL only; got " + type);
        };
    }
}
