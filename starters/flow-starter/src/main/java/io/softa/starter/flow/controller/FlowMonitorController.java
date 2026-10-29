package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.enums.SystemRole;
import io.softa.framework.orm.annotation.RequireRole;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.api.FlowUrgeRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.monitor.FlowHealthSnapshot;
import io.softa.starter.flow.runtime.monitor.FlowMonitorService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Operator-facing endpoints that expose flow runtime health signals, cross-initiator instance queries for
 * monitoring consoles, and what an administrator does to an instance from there.
 */
@Tag(name = "Flow Monitor")
@RestController
@RequestMapping("/flow/monitor")
public class FlowMonitorController {

    private final FlowMonitorService monitorService;

    private final FlowInstanceService instanceService;

    private final FlowRuntimeEngine runtimeEngine;

    private final FlowInstanceAccessGuard accessGuard;

    private final UrgeThrottle urgeThrottle;

    public FlowMonitorController(FlowMonitorService monitorService, FlowInstanceService instanceService,
                                 FlowRuntimeEngine runtimeEngine, FlowInstanceAccessGuard accessGuard,
                                 UrgeThrottle urgeThrottle) {
        this.monitorService = monitorService;
        this.instanceService = instanceService;
        this.runtimeEngine = runtimeEngine;
        this.accessGuard = accessGuard;
        this.urgeThrottle = urgeThrottle;
    }

    @GetMapping("/health")
    @Operation(summary = "Flow runtime health snapshot",
            description = "Returns per-status instance counts and overdue-timer count for the flow runtime.")
    public ApiResponse<FlowHealthSnapshot> health() {
        return ApiResponse.success(monitorService.snapshot());
    }

    @PostMapping("/instances/search")
    @RequireRole(SystemRole.SYSTEM_ROLE_ADMIN)
    @Operation(summary = "Search flow instances (monitor)",
            description = "Cross-initiator paged instance summaries for operator monitoring views. Honors "
                    + "the request's initiatorId filter when present; requires the system admin role. Heavy "
                    + "JSON state columns and the trace are excluded — fetch a single instance or its "
                    + "overlay for detail.")
    public ApiResponse<Page<FlowInstance>> searchInstances(@RequestBody FlowInstanceSearchRequest request) {
        return ApiResponse.success(instanceService.searchSummaries(request, null));
    }

    @PostMapping("/instances/urge")
    @Operation(summary = "Urge pending approvers (administrator)",
            description = "Reminds the approvers an approval waits on, as an administrator rather than its "
                    + "initiator: allowed to whoever may read the instance's FlowInstance row with their own "
                    + "permissions. The initiator's interval applies: a step urged within it, by anyone, is not "
                    + "urged again. Returns when the approval may be urged next.")
    public ApiResponse<FlowUrgeStatus> urge(@Valid @RequestBody FlowUrgeRequest request) {
        String userId = currentUserId();
        accessGuard.requireInstanceAdministrator(request.getInstanceId(), userId);
        request.setActorId(userId);
        return ApiResponse.success(urgeThrottle.status(runtimeEngine.urgeAsAdministrator(request), LocalDateTime.now()));
    }
}
