package io.softa.starter.flow.service.query;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TaskInstanceContextEnricher} — one batched instance lookup
 * per page, merged onto the task views by instanceId.
 */
class TaskInstanceContextEnricherTest {

    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    private final TaskInstanceContextEnricher enricher = new TaskInstanceContextEnricher(instanceService);

    private static FlowInstance instance(String instanceId, String title, String modelName, String rowId) {
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId(instanceId);
        instance.setTitle(title);
        instance.setModelName(modelName);
        instance.setRowId(rowId);
        instance.setStatus(FlowExecutionStatus.WAITING);
        return instance;
    }

    private static FlowApprovalTaskView view(String instanceId) {
        return FlowApprovalTaskView.builder().instanceId(instanceId).build();
    }

    @Test
    void enrichMergesInstanceContextByInstanceId() {
        when(instanceService.findByInstanceIds(argThat(ids -> ids.containsAll(List.of("i1", "i2")))))
                .thenReturn(List.of(
                        instance("i1", "Leave request", "LeaveRequest", "row-1"),
                        instance("i2", "Expense claim", "ExpenseClaim", "row-2")));

        FlowApprovalTaskView first = view("i1");
        FlowApprovalTaskView second = view("i2");
        // Duplicate instance ids collapse into one lookup key.
        FlowApprovalTaskView third = view("i1");
        enricher.enrich(List.of(first, second, third));

        assertEquals("Leave request", first.getInstanceTitle());
        assertEquals("LeaveRequest", first.getModelName());
        assertEquals("row-1", first.getRowId());
        assertEquals(FlowExecutionStatus.WAITING, first.getInstanceStatus());
        assertEquals("Expense claim", second.getInstanceTitle());
        assertEquals("Leave request", third.getInstanceTitle());
    }

    @Test
    void missingInstanceRowsLeaveViewsUntouched() {
        when(instanceService.findByInstanceIds(argThat(ids -> ids.contains("gone"))))
                .thenReturn(List.of());

        FlowApprovalTaskView view = view("gone");
        assertDoesNotThrow(() -> enricher.enrich(List.of(view)));
        assertNull(view.getInstanceTitle());
        assertNull(view.getInstanceStatus());
    }

    @Test
    void emptyViewsSkipTheLookupEntirely() {
        enricher.enrich(List.of());
        enricher.enrich(null);
        verifyNoInteractions(instanceService);
    }
}
