package io.softa.starter.metadata.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.sequence.SequencePreview;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.orm.sequence.exception.SequenceCrossTenantException;
import io.softa.framework.orm.sequence.exception.SequenceNotFoundException;
import io.softa.framework.orm.sequence.exception.SequenceTimeoutException;
import io.softa.starter.metadata.entity.SysSequence;
import io.softa.starter.metadata.enums.SequenceMode;
import io.softa.starter.metadata.sequence.dialect.SequenceDialect;
import io.softa.starter.metadata.sequence.dialect.SequenceDialectFactory;
import io.softa.starter.metadata.service.SysSequenceService;
import io.softa.starter.metadata.sequence.CompanyAbbreviationResolver;
import io.softa.starter.metadata.sequence.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * Default {@link SequenceService} implementation.
 *
 * <p>The class dispatches by {@link SysSequence#getMode()} into two
 * proxy-routed methods:
 * <ul>
 *   <li>{@link #allocateInOuterTx(String, SysSequence, int)} — joins the
 *       caller's business transaction ({@code MANDATORY}); strict no-gap.</li>
 *   <li>{@link #allocateInNewTx(String, SysSequence, int)} — opens an
 *       independent transaction ({@code REQUIRES_NEW}); business rollback
 *       leaves the counter advanced.</li>
 * </ul>
 * The {@code self} field (Spring proxy injected lazily to break the cycle)
 * is mandatory: a direct {@code this.allocate...} call would bypass the
 * AOP advice and silently disable the propagation annotation.
 *
 * <p>Both single and batch paths share the same dispatcher and defer SQL
 * details to database-specific sequence dialects.
 *
 * <p>Allocation hands back numbers; rendering happens after, per record. Tokens
 * that depend on the record being numbered ({@code {companyAbbr}}) are resolved
 * before the allocation is dispatched, in the caller's transaction — an
 * ALLOW_GAP allocation runs in a transaction of its own, which cannot see a
 * record the caller inserted moments earlier (an employee whose address is
 * being numbered in the same save).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SequenceServiceImpl implements SequenceService {

    private static final String MODEL_NAME = "SysSequence";

    private final JdbcProxy jdbcProxy;
    private final SysSequenceService sysSequenceService;
    private final TemplateRenderer templateRenderer;
    private final SequenceDialectFactory sequenceDialectFactory;
    private final CompanyAbbreviationResolver companyAbbreviationResolver;

    /** Self-proxy for transactional dispatch; must be lazy to break the cycle. */
    @Autowired
    @Lazy
    private SequenceServiceImpl self;

    @Override
    public String next(String code) {
        return next(code, Map.of());
    }

    @Override
    public List<String> nextBatch(String code, int count) {
        Assert.isTrue(count > 0, "count must be positive");
        return nextBatch(code, Collections.nCopies(count, Map.of()));
    }

    @Override
    public String next(String code, Map<String, Object> row) {
        return nextBatch(code, List.of(row == null ? Map.of() : row)).getFirst();
    }

    @Override
    public List<String> nextBatch(String code, List<Map<String, Object>> rows) {
        Assert.isTrue(rows != null && !rows.isEmpty(), "rows must not be empty");
        rejectCrossTenant(code);
        SysSequence cfg = sysSequenceService.loadConfigByCode(code);
        String template = cfg.getTemplate();
        List<Map<String, String>> variables = variablesFor(code, template, rows);
        Allocation allocation = allocate(code, cfg, rows.size());
        List<String> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            result.add(templateRenderer.render(
                    template, allocation.numbers().get(i), allocation.anchor(), code, variables.get(i)));
        }
        return result;
    }

    @Override
    public SequencePreview peek(String code) {
        rejectCrossTenant(code);
        // Bypass config cache for peek: the cache holds a 5-minute snapshot
        // of the row, including the mutable {@code currentValue}/{@code
        // lastResetKey} that other allocations may have advanced past.
        // A direct read keeps the preview honest
        // (still advisory — another caller can race ahead between this read
        // and the user's next() call).
        SysSequence row = sysSequenceService
                .searchOne(new FlexQuery(new Filters().eq(SysSequence::getCode, code)))
                .orElseThrow(() -> new SequenceNotFoundException(code));
        LocalDateTime now = LocalDateTime.now();
        String currentKey = row.getResetCadence().computeKey(now);
        long preview;
        if (currentKey.equals(row.getLastResetKey())) {
            preview = (row.getCurrentValue() == null ? 0L : row.getCurrentValue()) + row.getIncrementStep();
        } else {
            preview = row.getStartValue();
        }
        String rendered = templateRenderer.render(row.getTemplate(), preview, now, code);
        return new SequencePreview(code, rendered, preview, "Preview only, not reserved");
    }

    /** Numbers a template is rendered with, and the moment its date tokens take their values from. */
    public record Allocation(List<Long> numbers, LocalDateTime anchor) {
    }

    /** Mode dispatcher; goes through {@link #self} so the propagation annotations apply. */
    private Allocation allocate(String code, SysSequence cfg, int count) {
        return SequenceMode.ALLOW_GAP == cfg.getMode()
                ? self.allocateInNewTx(code, cfg, count)
                : self.allocateInOuterTx(code, cfg, count);
    }

    /**
     * Per-record token values. Empty for every row unless the template asks for one, so a template
     * without record-dependent tokens costs no lookup at all.
     */
    private List<Map<String, String>> variablesFor(String code, String template, List<Map<String, Object>> rows) {
        if (!TemplateRenderer.usesCompanyAbbr(template)) {
            return Collections.nCopies(rows.size(), Map.of());
        }
        List<String> abbreviations = companyAbbreviationResolver.abbreviationsOf(modelOf(code), rows);
        List<Map<String, String>> variables = new ArrayList<>(rows.size());
        for (String abbreviation : abbreviations) {
            variables.add(abbreviation == null ? Map.of() : Map.of(TemplateRenderer.COMPANY_ABBR, abbreviation));
        }
        return variables;
    }

    /** The model a sequence numbers: its code is {@code "<ModelName>.<fieldName>"}. */
    static String modelOf(String code) {
        int dot = code.lastIndexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    /**
     * NO_GAP path. Joins the caller's transaction; row lock is held until
     * the outer business transaction commits or rolls back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Allocation allocateInOuterTx(String code, SysSequence cfg, int count) {
        return doAllocate(code, cfg, count);
    }

    /**
     * ALLOW_GAP path. Suspends the outer transaction (if any), opens a fresh
     * physical transaction on a separate connection, commits it, then
     * resumes. Business rollback after this point cannot retract the counter.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Allocation allocateInNewTx(String code, SysSequence cfg, int count) {
        return doAllocate(code, cfg, count);
    }

    private Allocation doAllocate(String code, SysSequence cfg, int count) {
        Long tenantId = ContextHolder.getContext().getTenantId();
        if (SystemConfig.env.isEnableMultiTenancy() && tenantId == null) {
            throw new IllegalStateException(
                    "tenantId is required for sequence allocation when multi-tenancy is enabled, code=" + code);
        }
        long step = cfg.getIncrementStep();
        long startValue = cfg.getStartValue();
        LocalDateTime now = LocalDateTime.now();
        String currentKey = cfg.getResetCadence().computeKey(now);
        SequenceDialect sequenceDialect = sequenceDialectFactory.getCurrentDialect();

        SqlParams sqlParams = sequenceDialect.buildAllocateSql(
                currentKey, step, startValue, count, cfg.getId(), tenantId);

        int rows;
        try {
            rows = jdbcProxy.update(MODEL_NAME, sqlParams);
        } catch (CannotAcquireLockException e) {
            throw new SequenceTimeoutException(code, e);
        }
        if (rows == 0) {
            // No row updated → no sys_sequence row for this (tenant, code).
            // v1 has no status column: existence equals active.
            throw new SequenceNotFoundException(code);
        }

        Long endValue = sequenceDialect.fetchEndValue(MODEL_NAME, cfg.getId(), tenantId);
        if (endValue == null) {
            throw new SequenceNotFoundException(code);
        }

        long start = endValue - (long) (count - 1) * step;
        List<Long> numbers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            numbers.add(start + (long) i * step);
        }
        return new Allocation(numbers, now);
    }

    private void rejectCrossTenant(String code) {
        if (ContextHolder.getContext().isCrossTenant()) {
            throw new SequenceCrossTenantException(code);
        }
    }
}
