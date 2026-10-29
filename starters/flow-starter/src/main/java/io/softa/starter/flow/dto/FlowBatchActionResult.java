package io.softa.starter.flow.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What became of each task in a batch, in the order they were sent, with the counts a client reports.
 */
@Schema(name = "FlowBatchActionResult")
public record FlowBatchActionResult(

        @Schema(description = "Tasks the action was done on")
        int succeeded,

        @Schema(description = "Tasks left as they were because the action does not apply to them — a step that allows "
                + "no return, a handling step, a step with fields to fill in")
        int skipped,

        @Schema(description = "Tasks the action was refused on")
        int failed,

        @Schema(description = "Each task's outcome, in request order")
        List<Item> items
) {

    public static FlowBatchActionResult of(List<Item> items) {
        return new FlowBatchActionResult(count(items, Outcome.SUCCEEDED), count(items, Outcome.SKIPPED),
                count(items, Outcome.FAILED), items);
    }

    private static int count(List<Item> items, Outcome outcome) {
        return (int) items.stream().filter(item -> item.outcome() == outcome).count();
    }

    @Schema(name = "FlowBatchActionItem")
    public record Item(

            @Schema(description = "Runtime instance id")
            String instanceId,

            @Schema(description = "The step acted on")
            String nodeId,

            @Schema(description = "What became of the task")
            Outcome outcome,

            @Schema(description = "Why it was skipped or refused; null when it succeeded")
            String message
    ) {
    }

    @Getter
    @AllArgsConstructor
    public enum Outcome {
        SUCCEEDED("Succeeded"),
        SKIPPED("Skipped"),
        FAILED("Failed");

        @JsonValue
        private final String type;
    }
}
