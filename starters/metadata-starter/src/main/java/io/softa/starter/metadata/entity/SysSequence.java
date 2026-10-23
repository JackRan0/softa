package io.softa.starter.metadata.entity;

import java.io.Serial;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.metadata.enums.ResetCadence;
import io.softa.starter.metadata.enums.SequenceMode;
import io.softa.starter.metadata.sequence.ManualEntryRule;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Sequence generator configuration and counter.
 * Each row holds a single (tenant, code) counter plus its rendering template
 * and reset / mode policy. Allocation goes through
 * {@code io.softa.framework.orm.sequence.SequenceService} (port in softa-orm,
 * implementation in this starter).
 *
 * <p>v1 hard rules (by convention — config-API enforcement is deferred):
 * <ul>
 *   <li>{@code code} matches {@code "<ModelName>.<fieldName>"} for
 *       fields participating in auto-fill (see SequenceProcessor).</li>
 *   <li>{@code incrementStep == 1}.</li>
 *   <li>{@code code} is not changed after creation; rows are provisioned by a
 *       tenant seed file, which seed-starter runs when the tenant is set up,
 *       not created ad hoc through the API.</li>
 * </ul>
 *
 * <p>There is no {@code status} column: a row's existence equals "active".
 * Emergency disable is a DBA-only operation (DELETE the row, optionally
 * preserving {@code currentValue} elsewhere if a later restoration is
 * needed).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Sequence",
        businessKey = {"code"},
        multiTenant = true,
        copyable = false,
        description = "Sequence generator configuration and counter"
)
@Index(fields = {"tenantId", "code"}, unique = true,
        message = "A sequence with this code already exists.")
public class SysSequence extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Sequence Code", required = true,
            description = "Sequence code, e.g. \"Employee.code\"")
    private String code;

    @Field(required = true,
            description = "Format template, e.g. EMP-{yyyy}-{seq:5}")
    private String template;

    @Field(required = true,
            description = "First number after each reset (default 1)")
    private Long startValue;

    @Field(required = true, description = "Step size; v1 enforces 1")
    private Integer incrementStep;

    /**
     * Last allocated value; next = {@code current_value + step}.
     *
     * <p><b>Runtime state, not configuration — never put it in a seed file.</b> Deliberately
     * optional (defaulting to 0) so a seed row can leave it out: {@code loadPreTenantData} is
     * create-or-update, so a seed that carries the counter rewinds a live one every time it is
     * re-applied, and the sequence then re-issues numbers it already handed out until it climbs
     * back past the highest one in use. That surfaces far from its cause — as a duplicate-key
     * error on the business table, with the code field left blank on screen.
     *
     * <p>Omitting it from the seed is necessary but not sufficient, which is what {@code readonly}
     * is here for. {@code loadPreTenantData}'s update branch, in an overwrite load, is a whole-row
     * reconcile: it writes an explicit null for every updatable field the file does NOT mention, so
     * leaving the counter out used to null it — the mirror of the rewind above, and worse, because a
     * NULL counter can no longer advance ({@code current_value + step} is NULL) and reads as a fresh
     * row, re-issuing numbers from {@code startValue}. Marked readonly, it is not an updatable field, so no re-load
     * can touch it either way — a readonly field is dropped from the write payload rather than
     * rejected, so the loader's null never reaches the column. The allocator is unaffected: it
     * advances the counter in its own {@code UPDATE} through {@code JdbcProxy}, below the field
     * processors. A first insert leaves the column out entirely and takes the DB-level default this
     * {@code defaultValue} is materialized into, which is why the declared 0 still lands on a new row.
     */
    @Field(readonly = true, defaultValue = "0",
            description = "Last allocated value; next = current_value + step")
    private Long currentValue;

    @Field(required = true)
    private ResetCadence resetCadence;

    /**
     * Runtime state like {@link #currentValue}, and readonly for the same reason: a re-load that
     * nulled this alongside the counter would make an in-flight period look like a fresh row and
     * restart the sequence. Written only by the allocator's own UPDATE.
     */
    @Field(readonly = true, description = "Period key of the last reset, e.g. \"2026\" / \"2026-04\"")
    private String lastResetKey;

    @Field(label = "Allocation Mode", required = true)
    private SequenceMode mode;

    /**
     * How a code typed by hand into the numbered field is checked; null checks as {@code Any}.
     * Generated codes are not checked against it.
     */
    @Field(label = "Manual Entry",
            description = "Rules for codes typed in by hand. Auto-generated codes are not checked.")
    private ManualEntryRule manualEntry;

    @Field(length = 256)
    private String description;
}
