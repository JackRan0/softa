package io.softa.starter.metadata.ddl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.softa.starter.metadata.dto.DdlStatementResult;
import io.softa.starter.metadata.dto.DdlStatementStatus;

/**
 * Runs studio-rendered DDL statements after the runtime has committed the
 * metadata rows.
 *
 * <p>Fail-fast: stops on the first {@link DdlStatementStatus#FAILED}, leaves
 * subsequent statements unattempted ({@link DdlStatementStatus#NOT_ATTEMPTED}).
 * Rationale: studio renders DDL in dependency order
 * (CREATE TABLE → ALTER → INDEX), and a downstream cascade after an upstream
 * failure is signal-deficient noise — the studio's "Mark as Applied" UI is
 * the proper recovery surface for the operator.
 *
 * <p>Idempotent skip: an attempt that hits a duplicate-create code (MySQL 1050/1060/1061,
 * PostgreSQL 42P07/42701/42P11) is reported as {@link DdlStatementStatus#SKIPPED_IDEMPOTENT}
 * and execution continues — the DDL was already applied (manual run, prior
 * deploy, etc.). This mirrors the scanner-side behaviour in
 * {@link io.softa.starter.metadata.ddl.DdlOrchestrator}.
 *
 * <p>The apply path runs the executor <b>before</b> the metadata-row write (DDL first), so a
 * {@link DdlStatementStatus#FAILED} statement aborts the apply before any rows are committed — committed
 * rows can never describe absent structure, and the change set stays retryable (roll-forward only).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DdlExecutor {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Execute each statement in order. Returns a sequence-aligned list of
     * outcomes — never {@code null}, never empty unless the input was.
     */
    public List<DdlStatementResult> executeAll(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return List.of();
        }
        List<DdlStatementResult> results = new ArrayList<>(statements.size());
        boolean abortRemaining = false;
        for (int i = 0; i < statements.size(); i++) {
            String sql = statements.get(i);
            if (abortRemaining) {
                results.add(new DdlStatementResult(i, DdlStatementStatus.NOT_ATTEMPTED, null, null));
                continue;
            }
            DdlStatementResult result = executeOne(i, sql);
            results.add(result);
            if (result.status() == DdlStatementStatus.FAILED) {
                abortRemaining = true;
            }
        }
        return results;
    }

    private DdlStatementResult executeOne(int sequence, String sql) {
        LocalDateTime attemptedAt = LocalDateTime.now();
        try {
            jdbcTemplate.execute(sql);
            log.info("DdlExecutor: statement[{}] OK", sequence);
            return new DdlStatementResult(sequence, DdlStatementStatus.SUCCESS, null, attemptedAt);
        } catch (BadSqlGrammarException e) {
            if (DdlErrorClassifier.isIdempotentDuplicate(e)) {
                String reason = DdlErrorClassifier.rootMessage(e);
                log.warn("DdlExecutor: statement[{}] skipped (already applied: {})", sequence, reason);
                return new DdlStatementResult(sequence, DdlStatementStatus.SKIPPED_IDEMPOTENT, reason, attemptedAt);
            }
            String reason = DdlErrorClassifier.rootMessage(e);
            log.error("DdlExecutor: statement[{}] FAILED. SQL was:\n{}", sequence, sql, e);
            return new DdlStatementResult(sequence, DdlStatementStatus.FAILED, reason, attemptedAt);
        } catch (DataAccessException e) {
            String reason = DdlErrorClassifier.rootMessage(e);
            log.error("DdlExecutor: statement[{}] FAILED. SQL was:\n{}", sequence, sql, e);
            return new DdlStatementResult(sequence, DdlStatementStatus.FAILED, reason, attemptedAt);
        }
    }
}
