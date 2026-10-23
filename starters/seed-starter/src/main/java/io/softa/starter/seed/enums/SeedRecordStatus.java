package io.softa.starter.seed.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * How one file's run in one scope ended, on its {@link io.softa.starter.seed.entity.SeedLoadRecord}.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeedRecordStatus {
    SUCCEEDED("Succeeded"),
    FAILED("Failed"),
    ;

    @JsonValue
    private final String code;
}
