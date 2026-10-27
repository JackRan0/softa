package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.api.FlowInstanceView;
import io.softa.starter.flow.api.MineQuery;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The port's instance reads make the same service calls as {@code FlowInstanceController}: "my
 * instances" is the summary search pinned to the current user, a record's approval preview is the
 * current user's, and form permissions pass the viewer check of the instance detail first.
 */
class InProcessFlowClientInstanceReadsTest {

    private static final String ACTOR = "100";

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowLaunchService launch = mock(FlowLaunchService.class);
    private final FormPermissionService forms = mock(FormPermissionService.class);
    private final FlowInstanceService instances = mock(FlowInstanceService.class);
    private final FlowInstanceAccessGuard accessGuard = mock(FlowInstanceAccessGuard.class);

    private final FlowClient client =
            new InProcessFlowClient(engine, launch, forms, null, null, null, instances, null, accessGuard);

    @Test
    void myInstancesIsTheSummarySearchOfTheCurrentUser() {
        LocalDateTime submittedAt = LocalDateTime.of(2026, 10, 27, 9, 30);
        FlowInstance row = new FlowInstance();
        row.setInstanceId("i-1");
        row.setFlowId("f-1");
        row.setFlowName("Leave");
        row.setTitle("Leave request");
        row.setStatus(FlowExecutionStatus.WAITING);
        row.setCreatedTime(submittedAt);
        Page<FlowInstance> page = Page.of(1, 50);
        page.setRows(List.of(row));
        ArgumentCaptor<FlowInstanceSearchRequest> request = ArgumentCaptor.forClass(FlowInstanceSearchRequest.class);
        when(instances.searchSummaries(request.capture(), eq(ACTOR))).thenReturn(page);

        List<FlowInstanceView> views = asCurrentUser(
                () -> client.myInstances(new MineQuery("f-1", FlowExecutionStatus.WAITING)));

        assertEquals(List.of(new FlowInstanceView("i-1", "f-1", "Leave", "Leave request",
                FlowExecutionStatus.WAITING, submittedAt)), views);
        assertEquals("f-1", request.getValue().flowId());
        assertEquals(FlowExecutionStatus.WAITING, request.getValue().status());
        assertNull(request.getValue().pageNumber());
        verify(instances, never()).findByInitiatorId(any());
    }

    @Test
    void formPermissionsOfAViewer() {
        FlowExecutionState state = new FlowExecutionState();
        when(engine.getInstance("i-1")).thenReturn(Optional.of(state));
        when(forms.getFieldPermissions("i-1", "approval")).thenReturn(Map.of("amount", FormFieldPermission.EDITABLE));

        assertEquals(Map.of("amount", FormFieldPermission.EDITABLE),
                asCurrentUser(() -> client.getFormPermissions("i-1", "approval")));
        verify(accessGuard).requireInstanceViewer(state, ACTOR);
    }

    @Test
    void formPermissionsAreRefusedToANonViewer() {
        FlowExecutionState state = new FlowExecutionState();
        when(engine.getInstance("i-1")).thenReturn(Optional.of(state));
        doThrow(new FlowAuthorizationException("User is not a participant of flow instance i-1"))
                .when(accessGuard).requireInstanceViewer(state, ACTOR);

        asCurrentUser(() -> assertThrows(FlowAuthorizationException.class,
                () -> client.getFormPermissions("i-1", "approval")));
        verify(forms, never()).getFieldPermissions(any(), any());
    }

    @Test
    void theApprovalPreviewOfARecordIsTheCurrentUsers() {
        FlowApprovalPreview preview = FlowApprovalPreview.builder().status(FlowApprovalPreview.Status.NO_FLOW).build();
        when(launch.previewRecord("LeaveRequest", "42", ACTOR)).thenReturn(preview);

        assertSame(preview, asCurrentUser(() -> client.previewRecord("LeaveRequest", "42")));
    }

    @Test
    void formPermissionsOfAMissingInstanceAreEmpty() {
        when(engine.getInstance("gone")).thenReturn(Optional.empty());

        assertEquals(Map.of(), asCurrentUser(() -> client.getFormPermissions("gone", "approval")));
        verify(forms, never()).getFieldPermissions(any(), any());
    }

    private static <T> T asCurrentUser(Supplier<T> call) {
        Context ctx = new Context();
        ctx.setUserId(Long.valueOf(ACTOR));
        return ContextHolder.callWith(ctx, call::get);
    }
}
