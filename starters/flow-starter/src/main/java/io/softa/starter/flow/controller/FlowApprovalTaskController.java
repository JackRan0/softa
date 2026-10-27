package io.softa.starter.flow.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxCountView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInboxService;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Query endpoints for persistent flow approval tasks.
 *
 * <p>The current user's own tasks carry {@link SkipPermissionCheck}: their filters pin every row to
 * the caller, so row scope cannot narrow them — it could only empty them, because
 * {@code FlowApprovalTask} is a model no business role is granted. A user sees their own to-dos
 * without any configuration. The waiver sits here, where the caller's id is taken from the request
 * context, and not on the service methods, which take the actor as a parameter. An instance's task
 * list is different: anyone may ask for it, and the access guard decides with the caller's own
 * permissions, so it is not waived.
 */
@Tag(name = "Flow Approval Task")
@RestController
@RequestMapping("/flow/approvalTasks")
public class FlowApprovalTaskController {

    private final FlowApprovalTaskQueryService flowApprovalTaskService;
    private final FlowInboxService flowInboxService;

    public FlowApprovalTaskController(FlowApprovalTaskQueryService flowApprovalTaskService,
                                             FlowInboxService flowInboxService) {
        this.flowApprovalTaskService = flowApprovalTaskService;
        this.flowInboxService = flowInboxService;
    }

    @GetMapping("/counts")
    @Operation(summary = "Inbox badge counts for current user",
            description = "Pending approval count and unread CC count — same filter definitions as the "
                    + "paged /pending and /cc?read=false queries, but a cheap COUNT instead of a page probe.")
    @SkipPermissionCheck
    public ApiResponse<FlowInboxCountView> counts() {
        return ApiResponse.success(flowApprovalTaskService.countInbox(currentUserId()));
    }

    @GetMapping("/pending")
    @Operation(summary = "Page pending tasks for current user")
    @SkipPermissionCheck
    public ApiResponse<Page<FlowApprovalTaskView>> pagePendingTasks(@RequestParam(required = false) String flowId,
                                                                    @RequestParam(required = false) String instanceId,
                                                                    @RequestParam(required = false) String nodeId,
                                                                    @RequestParam(defaultValue = "1") Integer pageNumber,
                                                                    @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowApprovalTaskService
                .pagePendingTasks(currentUserId(), flowId, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/completed")
    @Operation(summary = "Page completed tasks for current user")
    @SkipPermissionCheck
    public ApiResponse<Page<FlowApprovalTaskView>> pageCompletedTasks(@RequestParam(required = false) String flowId,
                                                                      @RequestParam(required = false) String instanceId,
                                                                      @RequestParam(required = false) String nodeId,
                                                                      @RequestParam(defaultValue = "1") Integer pageNumber,
                                                                      @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowApprovalTaskService
                .pageCompletedTasks(currentUserId(), flowId, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/cc")
    @Operation(summary = "Page CC tasks for current user", description = "Queries unread or read CC tasks for the current user. read=false returns unread CC tasks, read=true returns acknowledged CC tasks, and omitting read returns both.")
    @SkipPermissionCheck
    public ApiResponse<Page<FlowApprovalTaskView>> pageCcTasks(@RequestParam(required = false) Boolean read,
                                                               @RequestParam(required = false) String flowId,
                                                               @RequestParam(required = false) String instanceId,
                                                               @RequestParam(required = false) String nodeId,
                                                               @RequestParam(defaultValue = "1") Integer pageNumber,
                                                               @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowApprovalTaskService
                .pageCcTasks(currentUserId(), read, flowId, instanceId, nodeId, pageNumber, pageSize));
    }

    @GetMapping("/inbox")
    @Operation(summary = "Get unified inbox for current user", description = "Aggregates approval pending tasks, unread CC tasks, read CC tasks, and optionally completed approval tasks for the current user.")
    @SkipPermissionCheck
    public ApiResponse<FlowInboxView> getInbox(@RequestParam(required = false) String flowId,
                                                      @RequestParam(required = false) String instanceId,
                                                      @RequestParam(required = false) String nodeId,
                                                      @RequestParam(required = false) Boolean includeCompletedApprovals,
                                                      @RequestParam(defaultValue = "1") Integer pageNumber,
                                                      @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.success(flowInboxService.getInbox(
                currentUserId(), flowId, instanceId, nodeId, includeCompletedApprovals, pageNumber, pageSize));
    }

    @GetMapping("/instance/{instanceId}")
    @Operation(summary = "Get all approval tasks by runtime instance",
            description = "Cross-actor view restricted to the instance's participants/initiator.")
    public ApiResponse<List<FlowApprovalTaskView>> getTasksByInstance(@PathVariable String instanceId) {
        return ApiResponse.success(flowApprovalTaskService.getTasksByInstanceId(instanceId, currentUserId()));
    }
}
