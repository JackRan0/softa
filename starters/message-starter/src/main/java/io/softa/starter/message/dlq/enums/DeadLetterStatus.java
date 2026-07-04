package io.softa.starter.message.dlq.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Lifecycle of a {@code dead_letter_message} row.
 *
 * <ul>
 *   <li>{@link #PENDING}: just landed; awaits human triage</li>
 *   <li>{@link #RESOLVED}: business compensation done, closed</li>
 *   <li>{@link #DISCARDED}: not worth compensating, closed</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum DeadLetterStatus {
    PENDING("Pending"),
    RESOLVED("Resolved"),
    DISCARDED("Discarded");

    @JsonValue
    private final String code;
}
