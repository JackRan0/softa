package io.softa.starter.flow.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.service.FlowApprovalRecordQueryService;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Query endpoints for persistent flow approval records.
 *
 * <p>The current user's own history carries {@link SkipPermissionCheck}, for the reason given on
 * {@code FlowApprovalTaskController}: its rows are pinned to the caller, and {@code FlowApprovalRecord}
 * is granted to no business role. An instance's history is ruled on by the access guard instead.
 */
@Tag(name = "Flow Approval Record")
@RestController
@RequestMapping("/flow/approvalRecords")
public class FlowApprovalRecordController {

    private final FlowApprovalRecordQueryService flowApprovalRecordService;

    public FlowApprovalRecordController(FlowApprovalRecordQueryService flowApprovalRecordService) {
        this.flowApprovalRecordService = flowApprovalRecordService;
    }

    @GetMapping("/instance/{instanceId}")
    @Operation(summary = "Get approval history by runtime instance",
            description = "Cross-actor view restricted to the instance's participants/initiator.")
    public ApiResponse<List<FlowApprovalRecordView>> getByInstance(@PathVariable String instanceId) {
        return ApiResponse.success(flowApprovalRecordService.getByInstanceId(instanceId, currentUserId()));
    }

    @GetMapping("/history")
    @Operation(summary = "Get approval history for current user", description = "Paged, newest first.")
    @SkipPermissionCheck
    public ApiResponse<Page<FlowApprovalRecordView>> getHistory(@RequestParam(required = false) String flowId,
                                                                @RequestParam(required = false) String instanceId,
                                                                @RequestParam(required = false) String nodeId,
                                                                @RequestParam(required = false) Integer pageNumber,
                                                                @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(flowApprovalRecordService.getHistory(
                currentUserId(), flowId, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/cc/sent")
    @Operation(summary = "Get sender-side CC history for current user", description = "Shows CC entries sent by the current user together with recipient read acknowledgement state.")
    @SkipPermissionCheck
    public ApiResponse<List<FlowSentCcView>> getSentCcHistory(@RequestParam(required = false) Boolean read,
                                                                     @RequestParam(required = false) String flowId,
                                                                     @RequestParam(required = false) String instanceId,
                                                                     @RequestParam(required = false) String nodeId) {
        return ApiResponse.success(flowApprovalRecordService.getSentCcHistory(currentUserId(), read, flowId, instanceId, nodeId));
    }
}
