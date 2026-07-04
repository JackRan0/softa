package io.softa.starter.metadata.dto;

import java.time.LocalDateTime;

import io.softa.starter.metadata.ddl.DdlExecutor;

/**
 * Runtime-side execution outcome for a single DDL statement, produced by
 * {@link DdlExecutor#executeAll}. The apply path scans the result
 * list for any {@link DdlStatementStatus#FAILED} entry to decide the overall outcome. {@code sequence}
 * matches the statement's index in the original DDL statements list.
 *
 * @param sequence    index in the original statements list
 * @param status      execution outcome (SUCCESS / SKIPPED_IDEMPOTENT / FAILED / NOT_ATTEMPTED)
 * @param errorMessage root-cause message when {@code status == FAILED}; null otherwise
 * @param executedAt  wall-clock time the runtime attempted the statement; null when
 *                    {@code status == NOT_ATTEMPTED}
 */
public record DdlStatementResult(
        int sequence,
        DdlStatementStatus status,
        String errorMessage,
        LocalDateTime executedAt) {
}
