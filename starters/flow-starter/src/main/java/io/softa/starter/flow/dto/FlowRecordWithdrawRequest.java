package io.softa.starter.flow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a submitter says when they withdraw their record from its approval.
 */
@Schema(name = "FlowRecordWithdrawRequest")
public record FlowRecordWithdrawRequest(

        @Schema(description = "Why the record is withdrawn; optional")
        String comment
) {
}
