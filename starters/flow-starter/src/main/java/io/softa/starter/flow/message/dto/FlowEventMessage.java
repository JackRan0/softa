package io.softa.starter.flow.message.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.base.context.Context;

/**
 * Message DTO for asynchronous flow trigger events transported via Pulsar.
 */
@Data
@NoArgsConstructor
public class FlowEventMessage {

    /** Flow id to start directly (optional — when null, trigger matching is used). */
    private String flowId;

    /** Pinned bundle id to start directly (optional; overrides {@link #flowId} when set). */
    private Long bundleId;

    /** Trigger type string (maps to FlowTriggerType). */
    private String triggerType;

    /** Identity of the event (a change log's uuid), carried so a run is started at most once per event. */
    private String eventId;

    /** Source model for entity-related triggers. */
    private String sourceModel;

    /** Source row id for entity-related triggers. */
    private String sourceRowId;

    /** Actor who triggered the event. */
    private String actorId;

    /** Parameters passed to the flow as initial variables. */
    private Map<String, Object> parameters;

    /** Event timestamp. */
    private LocalDateTime eventTime;

    /** Serialized user context for cross-thread propagation. */
    private Context context;
}

