package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Where an approved record's change stands, for a model whose approval takes effect later — on its effective date,
 * written onto another record by a job. The record says it; the framework only shows it.
 */
@Getter
@AllArgsConstructor
public enum RecordEffectStatus {

    /** Approved, not applied yet: its date has not come. */
    SCHEDULED("Scheduled"),

    /** Written where it belongs. */
    APPLIED("Applied"),

    /** The last attempt to apply it failed; it is tried again. */
    FAILED("Failed");

    @JsonValue
    private final String type;
}
