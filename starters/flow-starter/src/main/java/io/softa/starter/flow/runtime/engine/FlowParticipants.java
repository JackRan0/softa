package io.softa.starter.flow.runtime.engine;

import java.util.List;

import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Who takes part in a flow instance: its initiator and the people it gives or gave a task to — its
 * approvers, pending or done, the people blocked behind them or added before them, and the actors and
 * targets of its approval actions (transfers, delegations, added signatures, CCs). They see the instance
 * without any configured permission, and only they comment on it.
 */
public final class FlowParticipants {

    private FlowParticipants() {
    }

    /**
     * Whether the user takes part in the instance.
     *
     * @param state   the instance
     * @param history its full approval history, as {@link ApprovalAuditReader#fullHistory} reads it
     * @param userId  the user
     */
    public static boolean isParticipant(FlowExecutionState state, List<ApprovalActionAuditEntry> history,
                                        String userId) {
        if (state == null || userId == null) {
            return false;
        }
        return userId.equals(state.getInitiatorId())
                || pendingApprovalParticipant(state.getPendingApprovals(), userId)
                || auditHistoryParticipant(history, userId);
    }

    private static boolean pendingApprovalParticipant(List<PendingApproval> approvals, String userId) {
        if (approvals == null) {
            return false;
        }
        return approvals.stream().anyMatch(approval -> approval != null
                && (contains(approval.getApprovers(), userId)
                || contains(approval.getApprovedActors(), userId)
                || contains(approval.getRejectedActors(), userId)
                || userId.equals(approval.getBlockedActorId())
                || userId.equals(approval.getPrerequisiteActorId())));
    }

    private static boolean auditHistoryParticipant(List<ApprovalActionAuditEntry> history, String userId) {
        if (history == null) {
            return false;
        }
        return history.stream().anyMatch(entry -> entry != null
                && (userId.equals(entry.getActorId())
                || userId.equals(entry.getTargetActorId())
                || contains(entry.getApprovedActors(), userId)
                || contains(entry.getRejectedActors(), userId)));
    }

    private static boolean contains(List<String> actors, String userId) {
        return actors != null && actors.contains(userId);
    }
}
