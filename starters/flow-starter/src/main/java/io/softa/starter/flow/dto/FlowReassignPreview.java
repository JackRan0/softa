package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a reassignment would move: the approver's pending tasks within the caller's data scope. */
@Schema(name = "FlowReassignPreview")
public record FlowReassignPreview(

        @Schema(description = "Pending approval tasks of the original approver that would move")
        int tasks,

        @Schema(description = "Approvals those tasks belong to")
        int instances
) {
}
