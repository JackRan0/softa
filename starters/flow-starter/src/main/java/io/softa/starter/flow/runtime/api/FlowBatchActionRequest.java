package io.softa.starter.flow.runtime.api;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * One approver action — approve, return or reject — over several of the caller's pending approval tasks, with
 * one comment for all of them. Each task is acted on separately; see {@code FlowBatchActionResult}.
 */
@Data
@Schema(name = "FlowBatchActionRequest")
public class FlowBatchActionRequest {

    /** A page of My Tasks at most: a batch is chosen from one page. */
    public static final int MAX_TASKS = 100;

    @NotEmpty(message = "Choose at least one task")
    @Size(max = MAX_TASKS, message = "At most " + MAX_TASKS + " tasks can be acted on at once")
    @Schema(description = "The pending tasks to act on: each names its instance and the step it waits on")
    private List<@Valid Task> tasks;

    @Size(max = 256, message = "The comment can be at most 256 characters")
    @Schema(description = "The comment recorded on every task; required to return or reject")
    private String comment;

    @Data
    @Schema(name = "FlowBatchActionTask")
    public static class Task {

        @NotBlank(message = "instanceId must not be blank")
        @Schema(description = "Runtime instance id")
        private String instanceId;

        @NotBlank(message = "nodeId must not be blank")
        @Schema(description = "The pending approval step")
        private String nodeId;
    }
}
