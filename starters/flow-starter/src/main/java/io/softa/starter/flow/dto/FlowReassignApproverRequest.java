package io.softa.starter.flow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Move one approver's pending approval tasks to another approver, from the monitoring console. A preview needs no
 * reason; the reassignment itself does.
 */
@Data
@Schema(name = "FlowReassignApproverRequest")
public class FlowReassignApproverRequest {

    @NotBlank(message = "Choose the original approver")
    @Schema(description = "The approver whose pending tasks move")
    private String fromActorId;

    @NotBlank(message = "Choose the new approver")
    @Schema(description = "Who takes them over")
    private String toActorId;

    @Size(max = 256, message = "The reason can be at most 256 characters")
    @Schema(description = "Why; required to reassign, recorded on each task")
    private String reason;
}
