package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;

/**
 * Execution scenario of a flow definition, declared when the flow is created and fixed from then on.
 * <p>
 * A single, self-contained discriminator: each value decides the triggers a flow may declare, the node
 * types it may use and how its runs behave — no compatibility matrix is required.
 *
 * <ul>
 *   <li>{@link #APPROVAL} — a record's approval: started by submitting the record (a {@code Submit}
 *       trigger), approval steps, the outcome written back to the record. Persisted instances.</li>
 *   <li>{@link #AUTOMATION} — started by a change, a cron, an API call or a parent flow, with no approval
 *       steps. Persisted runs, purged once the retention period has passed.</li>
 *   <li>{@link #VALIDATION} — stateless, synchronous, returns a list of validation
 *       errors. No instance is persisted.</li>
 *   <li>{@link #COMPUTE} — stateless, synchronous, returns variable diffs. No instance is persisted.</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum FlowScenario {

    @OptionItem(label = "Approval Flow")
    APPROVAL("Approval"),
    @OptionItem(label = "Automation Flow")
    AUTOMATION("Automation"),
    @OptionItem(label = "Validation Flow")
    VALIDATION("Validation"),
    @OptionItem(label = "Compute Flow")
    COMPUTE("Compute"),
    ;

    @JsonValue
    private final String type;

    /** Validation and Compute flows evaluate transiently: no instance row, no trace, no event log. */
    public boolean isTransient() {
        return this == VALIDATION || this == COMPUTE;
    }
}
