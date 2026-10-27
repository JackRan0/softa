package io.softa.starter.flow.service.support;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FlowInstanceAccessGuard}: the initiator and the instance's task holders see it
 * by default; anyone else through their own permissions — on the instance row, or on the record a
 * record approval approves. Reading the record through the instance stays with the default grants.
 */
class FlowInstanceAccessGuardTest {

    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(instanceService,
            new ApprovalAuditReader(new NoopApprovalActionLedger()), modelService);

    private FlowInstance instanceInitiatedBy(String initiatorId) {
        FlowInstance instance = new FlowInstance();
        instance.setInitiatorId(initiatorId);
        return instance;
    }

    @Test
    void participantInResultIsAllowed() {
        // A caller who appears in the loaded rows is allowed without touching the instance store.
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "u1", true));
    }

    @Test
    void initiatorIsAllowedEvenWhenNotInResult() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("u1")));
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void nonParticipantNonInitiatorIsDenied() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("someoneElse")));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void missingInstanceIsDenied() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.empty());
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "u1", false));
    }

    @Test
    void blankRequesterIsDenied() {
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "", false));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", null, false));
    }

    @Test
    void runtimeStateInitiatorIsAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("u1")
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "u1"));
    }

    @Test
    void runtimeStatePendingApproverIsAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .approvers(List.of("u1"))
                        .build()))
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "u1"));
    }

    @Test
    void runtimeStateAddSignParticipantsAreAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .blockedActorId("blocked")
                        .prerequisiteActorId("prereq")
                        .build()))
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "blocked"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "prereq"));
    }

    @Test
    void runtimeStateHistoryParticipantIsAllowed() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .approvalAuditDelta(List.of(ApprovalActionAuditEntry.builder()
                        .actorId("actor")
                        .targetActorId("target")
                        .approvedActors(List.of("approved"))
                        .rejectedActors(List.of("rejected"))
                        .build()))
                .build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "actor"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "target"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "approved"));
        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "rejected"));
    }

    @Test
    void runtimeStateOutsiderIsDenied() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder()
                        .approvers(List.of("approver"))
                        .build()))
                .build();
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("initiator")));

        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(state, "outsider"));
    }

    // ---- everyone else: their own permissions ----

    @Test
    void whoeverTheirRolesLetReadTheInstanceMayViewIt() {
        when(modelService.count(eq("FlowInstance"), any(Filters.class))).thenReturn(1L);
        FlowExecutionState state = FlowExecutionState.builder().instanceId("i1").initiatorId("someoneElse").build();

        assertDoesNotThrow(() -> guard.requireInstanceViewer(state, "monitor"));
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("someoneElse")));
        assertDoesNotThrow(() -> guard.requireInstanceViewer("i1", "monitor", false));
    }

    @Test
    void aReaderOfTheRecordMayViewItsApproval() {
        FlowExecutionState approval = FlowExecutionState.builder().instanceId("i1").initiatorId("someoneElse")
                .recordApproval(true).modelName("ExpenseClaim").rowId("7").build();
        FlowExecutionState automation = FlowExecutionState.builder().instanceId("i2").initiatorId("someoneElse")
                .modelName("ExpenseClaim").rowId("7").build();
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> ids = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel("ExpenseClaim")).thenReturn(true);
            ids.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(modelService.getById(eq("ExpenseClaim"), any(), anyList())).thenReturn(Optional.of(Map.<String, Object>of("id", "7")));

            assertDoesNotThrow(() -> guard.requireInstanceViewer(approval, "reader"));
            // Reading the record says nothing about another flow that merely runs on it.
            assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer(automation, "reader"));
        }
    }

    @Test
    void readingTheRecordThroughTheInstanceIsForTheDefaultGrantsOnly() {
        when(modelService.count(eq("FlowInstance"), any(Filters.class))).thenReturn(1L);
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1")
                .initiatorId("initiator")
                .pendingApprovals(List.of(PendingApproval.builder().approvers(List.of("approver")).build()))
                .build();

        assertDoesNotThrow(() -> guard.requireRecordGrantee(state, "initiator"));
        assertDoesNotThrow(() -> guard.requireRecordGrantee(state, "approver"));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordGrantee(state, "monitor"));
    }

    @Test
    void withoutAnyOfThemTheCallerIsDenied() {
        when(instanceService.findByInstanceId("i1")).thenReturn(Optional.of(instanceInitiatedBy("someoneElse")));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireInstanceViewer("i1", "u1", false));
    }
}
