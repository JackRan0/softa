package io.softa.starter.flow.service;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the conservative node-output type inference feeding
 * {@code FlowVariableView.type} / {@code modelName} (pure functions — no
 * ModelManager bootstrap needed).
 */
class FlowVariableTypeInferenceTest {

    private static FlowGraphNode node(FlowNodeType type, Map<String, Object> config) {
        return FlowGraphNode.builder().id("n1").type(type).config(config).build();
    }

    @Test
    void recordShapedNodesGetCoarseTypesAndTargetModel() {
        FlowGraphNode query = node(FlowNodeType.QUERY_RECORDS, Map.of("modelName", "LeaveRequest"));
        assertEquals("List", FlowVariableCatalogService.inferNodeOutputType(query));
        assertEquals("LeaveRequest", FlowVariableCatalogService.nodeTargetModel(query));

        FlowGraphNode get = node(FlowNodeType.GET_RECORD, Map.of("modelName", "ExpenseClaim"));
        assertEquals("Object", FlowVariableCatalogService.inferNodeOutputType(get));
        assertEquals("ExpenseClaim", FlowVariableCatalogService.nodeTargetModel(get));
    }

    @Test
    void aiNodesAreStringsAndOpaqueNodesStayUnknown() {
        assertEquals("String",
                FlowVariableCatalogService.inferNodeOutputType(node(FlowNodeType.QUERY_AI, Map.of())));
        assertNull(FlowVariableCatalogService.inferNodeOutputType(node(FlowNodeType.SCRIPT, Map.of())));
        assertNull(FlowVariableCatalogService.inferNodeOutputType(node(FlowNodeType.SUBFLOW, Map.of())));
    }

    @Test
    void missingOrBlankModelNameYieldsNoModel() {
        assertNull(FlowVariableCatalogService.nodeTargetModel(node(FlowNodeType.QUERY_RECORDS, Map.of())));
        assertNull(FlowVariableCatalogService.nodeTargetModel(
                node(FlowNodeType.QUERY_RECORDS, Map.of("modelName", ""))));
        assertNull(FlowVariableCatalogService.nodeTargetModel(node(FlowNodeType.SCRIPT, Map.of("modelName", "X"))));
    }
}
