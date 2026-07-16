package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Paged instance search for monitoring views. Results are summary rows — the
 * heavy JSON state columns and the trace are excluded; fetch a single instance
 * (or its overlay) for detail.
 */
@Schema(name = "FlowInstanceSearchRequest")
public record FlowInstanceSearchRequest(

        @Schema(description = "Filter by flow code")
        String flowCode,

        @Schema(description = "Filter by design id")
        Long designId,

        @Schema(description = "Filter by execution status")
        FlowExecutionStatus status,

        @Schema(description = "Filter by initiator id. Honored only by the monitor endpoint "
                + "(POST /flow/monitor/instances/search); the runtime endpoint always overrides "
                + "it with the authenticated caller")
        String initiatorId,

        @Schema(description = "Filter by related model name")
        String modelName,

        @Schema(description = "Filter by related row id (requires modelName)")
        String rowId,

        @Schema(description = "Filter by creation time — lower bound, inclusive")
        LocalDateTime createdFrom,

        @Schema(description = "Filter by creation time — upper bound, inclusive")
        LocalDateTime createdTo,

        @Schema(description = "1-based page number; default 1")
        Integer pageNumber,

        @Schema(description = "Page size; default 50")
        Integer pageSize
) {
}
