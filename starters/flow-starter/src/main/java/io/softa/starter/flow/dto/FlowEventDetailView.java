package io.softa.starter.flow.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.flow.entity.FlowEvent;

/**
 * Single trigger event with the raw trigger-parameters payload — the detail
 * read behind a list row (list rows exclude the potentially large JSON).
 */
@Schema(name = "FlowEventDetailView")
public record FlowEventDetailView(

        @Schema(description = "Event id")
        Long id,

        @Schema(description = "Trigger type discriminator (e.g. EntityChange, Api, Cron)")
        String triggerType,

        @Schema(description = "Source model when the trigger is entity-related")
        String sourceModel,

        @Schema(description = "Source row id when the trigger is entity-related")
        String sourceRowId,

        @Schema(description = "Actor who triggered the event")
        String actorId,

        @Schema(description = "Flow code of the matched and started flow")
        String flowCode,

        @Schema(description = "Flow revision that was started")
        Integer flowRevision,

        @Schema(description = "Runtime instance id of the started flow")
        String instanceId,

        @Schema(description = "Whether the flow was started successfully")
        Boolean success,

        @Schema(description = "Error message when the flow failed to start")
        String errorMessage,

        @Schema(description = "Trigger fire method")
        String fireMethod,

        @Schema(description = "Event timestamp")
        LocalDateTime eventTime,

        @Schema(description = "Raw trigger parameters JSON captured at fire time")
        String parameters
) {

    public static FlowEventDetailView of(FlowEvent event) {
        return new FlowEventDetailView(event.getId(), event.getTriggerType(), event.getSourceModel(),
                event.getSourceRowId(), event.getActorId(), event.getFlowCode(), event.getFlowRevision(),
                event.getInstanceId(), event.getSuccess(), event.getErrorMessage(), event.getFireMethod(),
                event.getEventTime(), event.getParameters());
    }
}
