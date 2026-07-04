package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * {@link io.softa.starter.studio.release.entity.DesignActivity} lifecycle status.
 * <p>
 * Studio operations are <b>synchronous</b> (publish/import/merge run inline), so the lifecycle is just
 * {@code RUNNING} → {@code SUCCESS} / {@code FAILURE}; {@code CANCELED} covers a stuck record released
 * by an operator. There is no {@code PENDING}/approval state and no {@code ROLLED_BACK} — roll-forward only.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Design Activity Status")
public enum DesignActivityStatus {
    RUNNING("Running"),
    SUCCESS("Success"),
    FAILURE("Failure"),
    CANCELED("Canceled"),
    ;

    @JsonValue
    private final String status;
}
