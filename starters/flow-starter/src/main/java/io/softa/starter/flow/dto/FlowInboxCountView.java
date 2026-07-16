package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lightweight inbox badge counts for the current actor — cheaper than probing
 * the paged endpoints with {@code pageSize=1} for their totals.
 */
@Schema(name = "FlowInboxCountView")
public record FlowInboxCountView(

        @Schema(description = "Pending approval tasks assigned to the caller")
        long pendingApprovals,

        @Schema(description = "Unread CC copies assigned to the caller")
        long unreadCc
) {
}
