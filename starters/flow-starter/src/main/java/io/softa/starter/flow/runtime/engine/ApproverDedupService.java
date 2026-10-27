package io.softa.starter.flow.runtime.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Automatic approvals at node activation, before the node starts waiting:
 * <ul>
 *   <li><b>The submitter.</b> On a submit flow, a step whose approvers include the record's
 *       submitter counts them as having approved — nobody is asked to approve their own
 *       submission. Whether the step then passes is the step's own approval mode: an any-one step
 *       passes, an all-approve step still waits for the others.</li>
 *   <li><b>Approver consolidation</b> ("审批人去重"): an actor who already approved an earlier node
 *       of the instance approves a later one on their behalf, per the flow's
 *       {@link ApproverDedupStrategy}. Default (unconfigured) is {@link ApproverDedupStrategy#GLOBAL}.</li>
 * </ul>
 *
 * <p>These are approval <em>business rules</em> — each writes a real
 * {@link ApprovalActionType#AUTO_APPROVE} audit record, so the approval history shows the step and
 * why it passed — and NOT action idempotency (which dedups retried / redelivered physical requests).
 */
@Component
public class ApproverDedupService {

    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final ApprovalAuditReader auditReader;

    public ApproverDedupService(ApprovalLifecycleService lifecycleService, FlowAuditService auditService,
                                ApprovalAuditReader auditReader) {
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.auditReader = auditReader;
    }

    /**
     * Auto-approve the submitter and the approvers who already approved a qualifying prior node,
     * then report whether the node is thereby complete (and should auto-advance instead of waiting
     * for a human action).
     */
    public boolean applyAndCheckComplete(CompiledFlowDefinition definition, CompiledFlowNode node,
                                         FlowExecutionState state, PendingApproval pending) {
        if (definition.getTrigger() instanceof SubmitTrigger && StringUtils.hasText(state.getInitiatorId())) {
            autoApprove(definition, node, state, pending, state.getInitiatorId(), "Auto-approved (submitter)");
        }
        ApproverDedupStrategy strategy = strategyOf(definition);
        if (strategy != ApproverDedupStrategy.NONE) {
            Set<String> priorApprovers = new HashSet<>();
            for (ApprovalActionAuditEntry approval : countedApprovals(state, node.getNodeId(), strategy)) {
                if (approval.getActorId() != null) {
                    priorApprovers.add(approval.getActorId());
                }
            }
            for (String approver : List.copyOf(pending.getApprovers())) {
                if (priorApprovers.contains(approver)) {
                    autoApprove(definition, node, state, pending, approver,
                            "Auto-approved (approver dedup: " + strategy + ")");
                }
            }
        }
        return lifecycleService.canCompleteApproval(pending);
    }

    /** Record {@code approver}'s automatic approval of the step, once, if they are one of its approvers. */
    private void autoApprove(CompiledFlowDefinition definition, CompiledFlowNode node, FlowExecutionState state,
                             PendingApproval pending, String approver, String reason) {
        if (!pending.getApprovers().contains(approver) || pending.getApprovedActors().contains(approver)) {
            return;
        }
        pending.getApprovedActors().add(approver);
        auditService.appendApprovalAudit(state,
                auditService.baseEntry(definition, node, pending, state.getStatus(), state.getStatus()).toBuilder()
                        .action(ApprovalActionType.AUTO_APPROVE)
                        .actorId(approver)
                        .comment(reason)
                        .build());
    }

    /** The flow's approver dedup policy: {@link ApproverDedupStrategy#GLOBAL} when unconfigured. */
    static ApproverDedupStrategy strategyOf(CompiledFlowDefinition definition) {
        return definition.getApproverDedup() == null ? ApproverDedupStrategy.GLOBAL : definition.getApproverDedup();
    }

    /**
     * The approvals recorded on earlier nodes of the instance that the strategy counts at
     * {@code currentNodeId}: every one under {@code GLOBAL}, the immediately-preceding approval node's
     * under {@code CONTIGUOUS}, none under {@code NONE}. A return ends the instance, so every approval
     * recorded on it belongs to the current round.
     */
    List<ApprovalActionAuditEntry> countedApprovals(FlowExecutionState state, String currentNodeId,
                                                    ApproverDedupStrategy strategy) {
        if (strategy == ApproverDedupStrategy.NONE) {
            return List.of();
        }
        List<ApprovalActionAuditEntry> priorApprovals = auditReader.fullHistory(state).stream()
                .filter(e -> e.getAction() == ApprovalActionType.APPROVE || e.getAction() == ApprovalActionType.AUTO_APPROVE)
                .filter(e -> e.getNodeId() != null && !e.getNodeId().equals(currentNodeId))
                .toList();
        if (priorApprovals.isEmpty() || strategy == ApproverDedupStrategy.GLOBAL) {
            return priorApprovals;
        }
        // CONTIGUOUS: the immediately-preceding approval node's approvals.
        String prevNodeId = priorApprovals.getLast().getNodeId();
        return priorApprovals.stream().filter(e -> prevNodeId.equals(e.getNodeId())).toList();
    }
}
