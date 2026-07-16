package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.monitor.FlowHealthSnapshot;
import io.softa.starter.flow.runtime.monitor.FlowMonitorService;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Operator-facing endpoints that expose flow runtime health signals and
 * cross-initiator instance queries for monitoring consoles.
 */
@Tag(name = "Flow Monitor")
@RestController
@RequestMapping("/flow/monitor")
public class FlowMonitorController {

    private final FlowMonitorService monitorService;

    private final FlowInstanceService instanceService;

    public FlowMonitorController(FlowMonitorService monitorService, FlowInstanceService instanceService) {
        this.monitorService = monitorService;
        this.instanceService = instanceService;
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
}
