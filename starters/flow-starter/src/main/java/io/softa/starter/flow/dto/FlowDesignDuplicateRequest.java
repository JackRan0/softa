package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Editor request to duplicate an existing flow draft under a fresh name.
 * Replaces the generic copy API, which is disabled on {@code FlowDesign}
 * because a verbatim copy would collide on the unique flow name.
 */
@Schema(name = "FlowDesignDuplicateRequest")
public record FlowDesignDuplicateRequest(

        @Schema(description = "Name for the copy; derived from the source name when omitted")
        String newFlowName
) {
}
