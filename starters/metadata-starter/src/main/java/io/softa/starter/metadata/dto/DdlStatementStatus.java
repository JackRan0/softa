package io.softa.starter.metadata.dto;

import io.softa.starter.metadata.ddl.DdlExecutor;

/**
 * Per-statement outcome of DDL execution on the runtime side, produced by
 * {@link DdlExecutor}.
 *
 * <ul>
 *   <li>{@code SUCCESS} — runtime executed the statement cleanly.</li>
 *   <li>{@code SKIPPED_IDEMPOTENT} — runtime hit an idempotent-duplicate code
 *       (MySQL 1050/1060/1061, PG 42P07/42701/42P11) and treated it as already applied.</li>
 *   <li>{@code FAILED} — runtime hit a genuine SQLException; {@code errorMessage} carries the root cause.</li>
 *   <li>{@code NOT_ATTEMPTED} — fail-fast: a previous statement in the same batch failed, so this one
 *       was never attempted.</li>
 * </ul>
 */
public enum DdlStatementStatus {
    SUCCESS,
    SKIPPED_IDEMPOTENT,
    FAILED,
    NOT_ATTEMPTED
}
