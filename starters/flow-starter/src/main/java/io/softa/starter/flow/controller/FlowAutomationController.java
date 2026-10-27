package io.softa.starter.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.runtime.api.FlowOnchangeRequest;
import io.softa.starter.flow.runtime.api.FlowValidationRequest;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;
import io.softa.starter.flow.runtime.trigger.FlowTriggerResult;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * Flow automation: fire a trigger event, which may start the flows it matches, and run a model's
 * FieldOnchange / Validation flows transiently. None of these endpoints reads or acts on an
 * existing instance — those live on {@link FlowInstanceController}.
 */
@Tag(name = "Flow Automation")
@Validated
@RestController
@RequestMapping("/flow/automation")
public class FlowAutomationController {

    private final FlowAutomationService flowAutomationService;

    public FlowAutomationController(FlowAutomationService flowAutomationService) {
        this.flowAutomationService = flowAutomationService;
    }

    @PostMapping("/trigger")
    @Operation(summary = "Fire trigger event", description = "Fires a trigger event that may start one or more flows whose trigger contract matches.")
    public ApiResponse<List<FlowTriggerResult>> fireTrigger(@Valid @RequestBody FlowTriggerEvent event) {
        event.setActorId(currentUserId());
        return ApiResponse.success(flowAutomationService.fire(event));
    }

    @PostMapping("/onchange")
    @Operation(summary = "Evaluate field onchange flows",
            description = "Executes all FieldOnchange-purpose flows matching the source model, "
                    + "merges fieldChanges into currentData, and returns the computed variables diff.")
    public ApiResponse<Map<String, Object>> onchange(@Valid @RequestBody FlowOnchangeRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(flowAutomationService.evaluateOnchange(request));
    }

    @PostMapping("/validate")
    @Operation(summary = "Evaluate validation flows",
            description = "Transiently runs all Validation-scenario flows bound to the source model "
                    + "against the candidate row data and returns each flow's declared outputs keyed "
                    + "by flow ID. No instance is persisted.")
    public ApiResponse<Map<String, Map<String, Object>>> validate(@Valid @RequestBody FlowValidationRequest request) {
        request.setActorId(currentUserId());
        return ApiResponse.success(flowAutomationService.evaluateValidation(request));
    }
}
