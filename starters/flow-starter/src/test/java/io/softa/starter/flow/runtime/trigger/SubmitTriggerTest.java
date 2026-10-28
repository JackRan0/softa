package io.softa.starter.flow.runtime.trigger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.validation.TriggerConsistencyValidator;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The submit trigger is chosen per record — by model and condition — and never by a generic
 * trigger event; the REST trigger refuses it and the compiler keeps it on Approval flows.
 */
class SubmitTriggerTest {

    private DefaultFlowTriggerRegistry registryFor(CompiledFlowDefinition... flows) {
        FlowBundleRegistry bundleRegistry = new FlowBundleRegistry() {
            @Override public CompiledFlowDefinition register(CompiledFlowDefinition d, DesignFlowDefinition design, String flowId) { return d; }
            @Override public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) { return Optional.empty(); }
            @Override public Optional<CompiledFlowDefinition> getActiveByFlowId(String flowId) { return Optional.empty(); }
            @Override public List<CompiledFlowDefinition> listRevisionsByFlowId(String flowId) { return List.of(); }
            @Override public Collection<CompiledFlowDefinition> list() { return List.of(flows); }
        };
        return new DefaultFlowTriggerRegistry(bundleRegistry);
    }

    private static CompiledFlowDefinition submitFlow(String flowId, String model, String condition) {
        return CompiledFlowDefinition.builder()
                .flowId(flowId)
                .flowName(flowId)
                .trigger(new SubmitTrigger(model, condition))
                .build();
    }

    @Test
    void findsTheFlowsOfTheModelWhoseConditionHolds() {
        DefaultFlowTriggerRegistry registry = registryFor(
                submitFlow("annual", "LeaveRequest", "leaveType == 'Annual'"),
                submitFlow("cancel", "LeaveRequest", "leaveType == 'CancelLeave'"),
                submitFlow("any-claim", "ExpenseClaim", null));

        List<CompiledFlowDefinition> matches = registry.findSubmitFlows("LeaveRequest", Map.of("leaveType", "Annual"));

        assertEquals(List.of("annual"), matches.stream().map(CompiledFlowDefinition::getFlowId).toList());
        assertEquals(1, registry.findSubmitFlows("ExpenseClaim", Map.of()).size());
    }

    @Test
    void aConditionThatCannotBeEvaluatedIsReportedNotSkipped() {
        DefaultFlowTriggerRegistry registry = registryFor(submitFlow("broken", "LeaveRequest", "days >"));

        assertThrows(FlowRuntimeException.class, () -> registry.findSubmitFlows("LeaveRequest", Map.of("days", 3)));
    }

    @Test
    void aGenericTriggerEventNeverStartsASubmitFlow() {
        DefaultFlowTriggerRegistry registry = registryFor(submitFlow("annual", "LeaveRequest", null));
        FlowTriggerEvent event = FlowTriggerEvent.builder().type("Submit").sourceModel("LeaveRequest").build();

        assertTrue(registry.findMatchingFlows(event).isEmpty());
    }

    @Test
    void theTriggerEndpointRefusesTheSubmitType() {
        FlowAutomationService automation = new FlowAutomationService(registryFor(), mock(FlowRuntimeEngine.class));
        FlowTriggerEvent event = FlowTriggerEvent.builder().type("Submit").sourceModel("LeaveRequest").build();

        assertThrows(FlowActionValidationException.class, () -> automation.fire(event));
    }

    @Test
    void theCompilerKeepsSubmitTriggersOnApprovalFlowsThatNameTheirModel() {
        TriggerConsistencyValidator validator = new TriggerConsistencyValidator();

        List<CompileDiagnostic> wrongScenario = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.VALIDATION).trigger(new SubmitTrigger("LeaveRequest", null)).build(), null);
        List<CompileDiagnostic> noModel = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.APPROVAL).trigger(new SubmitTrigger(" ", null)).build(), null);
        List<CompileDiagnostic> valid = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.APPROVAL).trigger(new SubmitTrigger("LeaveRequest", "days > 1")).build(), null);

        assertEquals(1, wrongScenario.size());
        assertEquals(1, noModel.size());
        assertTrue(valid.isEmpty());
    }

    @Test
    void twoHandlersForOneModelAreRefused() {
        ModelApprovalHandler first = mock(ModelApprovalHandler.class);
        ModelApprovalHandler second = mock(ModelApprovalHandler.class);
        when(first.modelName()).thenReturn("LeaveRequest");
        when(second.modelName()).thenReturn("LeaveRequest");

        ModelApprovalHandlerRegistry registry = ModelApprovalHandlerRegistry.of(List.of(first, second));

        assertThrows(IllegalStateException.class, registry::afterSingletonsInstantiated);
    }
}
