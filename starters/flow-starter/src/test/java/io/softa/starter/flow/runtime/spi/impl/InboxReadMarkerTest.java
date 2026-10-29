package io.softa.starter.flow.runtime.spi.impl;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.message.inbox.service.InboxNotificationService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Once an approver acts, their messages about the approval are read; an urge or a comment changes nothing. */
class InboxReadMarkerTest {

    private final InboxNotificationService inbox = mock(InboxNotificationService.class);
    private final InboxReadMarker marker;

    @SuppressWarnings("unchecked")
    InboxReadMarkerTest() {
        ObjectProvider<InboxNotificationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(inbox);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        marker = new InboxReadMarker(provider, transactions);
    }

    @Test
    void anApproverWhoActedHasTheirMessagesAboutTheInstanceRead() {
        marker.onStateChanged(state(entry(ApprovalActionType.APPROVE, "2001"), entry(ApprovalActionType.COMMENT, "1001")));

        verify(inbox).markReadBySource(2001L, "FlowInstance", "i1");
        verify(inbox, never()).markReadBySource(1001L, "FlowInstance", "i1");
    }

    @Test
    void anUrgeMarksNothing() {
        marker.onStateChanged(state(entry(ApprovalActionType.URGE, "1001")));

        verify(inbox, never()).markReadBySource(anyLong(), anyString(), any());
    }

    private static ApprovalActionAuditEntry entry(ApprovalActionType action, String actorId) {
        return ApprovalActionAuditEntry.builder().action(action).actorId(actorId).build();
    }

    private static FlowExecutionState state(ApprovalActionAuditEntry... entries) {
        return FlowExecutionState.builder()
                .instanceId("i1")
                .approvalAuditDelta(new ArrayList<>(List.of(entries)))
                .build();
    }
}
