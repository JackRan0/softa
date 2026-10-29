package io.softa.starter.flow.runtime.engine;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * How often the steps an approval waits on may be urged: a step urged — by the initiator or by an
 * administrator — is not urged again until {@code flow.approval.urge-interval-minutes} (default 240) have
 * passed. The interval runs per step and per pass through it: it is measured from the step's last
 * {@code URGE} entry in the approval history on the same node and cycle, so a step reached again starts
 * afresh.
 */
@Component
public class UrgeThrottle {

    private final ApprovalAuditReader auditReader;

    private final Duration interval;

    public UrgeThrottle(ApprovalAuditReader auditReader,
                        @Value("${flow.approval.urge-interval-minutes:240}") long intervalMinutes) {
        this.auditReader = auditReader;
        this.interval = Duration.ofMinutes(intervalMinutes);
    }

    /** The steps that may be urged now: they wait on approvers still to act, and none was urged within the interval. */
    public List<PendingApproval> urgeable(FlowExecutionState state, LocalDateTime now) {
        List<ApprovalActionAuditEntry> urges = urges(state);
        return waitingSteps(state).stream()
                .filter(step -> mayUrge(lastUrge(urges, step), now))
                .toList();
    }

    /**
     * Whether the approval may be urged now, and if not, when it may be: the earliest time one of the
     * steps it waits on comes out of the interval. An instance not waiting for approval, or waiting on
     * nobody, may not be urged and has no such time.
     */
    public FlowUrgeStatus status(FlowExecutionState state, LocalDateTime now) {
        if (state == null || !FlowExecutionStatus.WAITING.equals(state.getStatus())) {
            return new FlowUrgeStatus(false, null);
        }
        List<ApprovalActionAuditEntry> urges = urges(state);
        LocalDateTime next = null;
        for (PendingApproval step : waitingSteps(state)) {
            LocalDateTime last = lastUrge(urges, step);
            if (mayUrge(last, now)) {
                return new FlowUrgeStatus(true, null);
            }
            LocalDateTime again = last.plus(interval);
            if (next == null || again.isBefore(next)) {
                next = again;
            }
        }
        return new FlowUrgeStatus(false, next);
    }

    /** The approvers of a step still to act: asked, and neither approved nor rejected yet. */
    public static List<String> stillToAct(PendingApproval step) {
        if (step.getApprovers() == null) {
            return List.of();
        }
        List<String> actors = new ArrayList<>();
        for (String approver : step.getApprovers()) {
            if (!contains(step.getApprovedActors(), approver) && !contains(step.getRejectedActors(), approver)
                    && !actors.contains(approver)) {
                actors.add(approver);
            }
        }
        return actors;
    }

    private boolean mayUrge(LocalDateTime lastUrge, LocalDateTime now) {
        return lastUrge == null || !now.isBefore(lastUrge.plus(interval));
    }

    private static List<PendingApproval> waitingSteps(FlowExecutionState state) {
        if (state.getPendingApprovals() == null) {
            return List.of();
        }
        return state.getPendingApprovals().stream()
                .filter(step -> step != null && !stillToAct(step).isEmpty())
                .toList();
    }

    private List<ApprovalActionAuditEntry> urges(FlowExecutionState state) {
        return auditReader.fullHistory(state).stream()
                .filter(entry -> entry != null && ApprovalActionType.URGE.equals(entry.getAction()))
                .toList();
    }

    /** When the step was last urged: its newest URGE entry on the same node and cycle. */
    private static LocalDateTime lastUrge(List<ApprovalActionAuditEntry> urges, PendingApproval step) {
        LocalDateTime last = null;
        for (ApprovalActionAuditEntry urge : urges) {
            if (Objects.equals(step.getNodeId(), urge.getNodeId())
                    && Objects.equals(step.getCycleNumber(), urge.getCycleNumber())
                    && urge.getEventTime() != null
                    && (last == null || urge.getEventTime().isAfter(last))) {
                last = urge.getEventTime();
            }
        }
        return last;
    }

    private static boolean contains(List<String> actors, String actor) {
        return actors != null && actors.contains(actor);
    }
}
