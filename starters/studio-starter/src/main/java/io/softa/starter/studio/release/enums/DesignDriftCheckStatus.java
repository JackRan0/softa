package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outcome of an on-demand drift check for a {@code DesignAppEnv}. Computed fresh on every
 * request and returned in the {@code DriftEnvelopeDTO} (not persisted), so the UI can tell
 * a completed check — whose {@code reports} reflect the actual runtime state — apart from
 * a "check failed, runtime unreachable" outcome.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum DesignDriftCheckStatus {
    /** The drift check completed — the {@code reports} reflect the actual runtime state. */
    SUCCESS("Success"),
    /** The drift check failed (e.g. remote env unreachable); {@code errorMessage} carries the cause and {@code reports} is empty. */
    FAILURE("Failure"),
    ;

    @JsonValue
    private final String status;
}
