package io.softa.starter.seed.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Where a {@link io.softa.starter.seed.entity.SeedLoadBatch} stands.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeedLoadStatus {
    RUNNING("Running"),
    SUCCEEDED("Succeeded"),
    /** Some tenants failed; the others are done. */
    PARTIALLY_FAILED("PartiallyFailed"),
    FAILED("Failed"),
    /** It stopped making progress — the instance running it went down. Running again continues it. */
    INTERRUPTED("Interrupted"),
    ;

    @JsonValue
    private final String code;
}
