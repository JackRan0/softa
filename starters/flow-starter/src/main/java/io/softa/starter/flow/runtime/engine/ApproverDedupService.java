package io.softa.starter.flow.runtime.engine;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.SelfApprovalStrategy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Automatic approvals at node activation, before the node starts waiting:
 * <ul>
 *   <li><b>The record's own people</b> ("本人是审批人时"). On an Approval flow, a step whose approvers
 *       include the record's submitter, or the user of the employee the record is about, counts them as
 *       having approved — nobody is asked to approve their own record — unless the flow's
 *       {@link SelfApprovalStrategy} asks them like anyone else. Whether the step then passes is the
 *       step's own approval mode: an any-one step passes, an all-approve step still waits for the others.</li>
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

    /** Which of the record's own people an approver is. */
    public enum SelfRole {
        /** The user who submitted the record. */
        SUBMITTER,
        /** The user of the employee the record is about. */
        SUBJECT
    }

    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final ApprovalAuditReader auditReader;

    /** Optional: without it, the subject's own user is not recognized among a step's approvers. */
    private OrganizationService organizationService;

    public ApproverDedupService(ApprovalLifecycleService lifecycleService, FlowAuditService auditService,
                                ApprovalAuditReader auditReader) {
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.auditReader = auditReader;
    }

    @Autowired(required = false)
    void setOrganizationService(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    /**
     * Auto-approve the record's own people, when the flow says so, and the approvers who already approved a
     * qualifying prior node, then report whether the node is thereby complete (and should auto-advance
     * instead of waiting for a human action).
     */
    public boolean applyAndCheckComplete(CompiledFlowDefinition definition, CompiledFlowNode node,
                                         FlowExecutionState state, PendingApproval pending) {
        selfApprovers(definition, state).forEach((approver, role) -> autoApprove(definition, node, state, pending,
                approver, role == SelfRole.SUBMITTER ? "Auto-approved (submitter)" : "Auto-approved (subject)"));
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

    /** The flow's self-approval policy: {@link SelfApprovalStrategy#AUTO_APPROVE} when unconfigured. */
    static SelfApprovalStrategy selfApprovalOf(CompiledFlowDefinition definition) {
        return definition.getSelfApproval() == null ? SelfApprovalStrategy.AUTO_APPROVE : definition.getSelfApproval();
    }

    /**
     * The record's own people this flow approves for automatically, by user: its submitter, then the user of
     * the employee it is about — the submitter's role first when they are one person. Empty for a flow that
     * is not an approval, or that asks them like any other approver.
     */
    public Map<String, SelfRole> selfApprovers(CompiledFlowDefinition definition, FlowExecutionState state) {
        Map<String, SelfRole> self = new LinkedHashMap<>();
        if (!FlowScenario.APPROVAL.equals(definition.getScenario())
                || selfApprovalOf(definition) != SelfApprovalStrategy.AUTO_APPROVE) {
            return self;
        }
        if (StringUtils.hasText(state.getInitiatorId())) {
            self.put(state.getInitiatorId(), SelfRole.SUBMITTER);
        }
        Long subjectUser = subjectUser(state);
        if (subjectUser != null) {
            self.putIfAbsent(subjectUser.toString(), SelfRole.SUBJECT);
        }
        return self;
    }

    /** The user of the employee the record is about; null without one, or without the organization. */
    private Long subjectUser(FlowExecutionState state) {
        Object subject = state.getVariables() == null ? null : state.getVariables().get(ApproverResolutionService.SUBJECT_ID_KEY);
        if (organizationService == null || subject == null || subject.toString().isBlank()) {
            return null;
        }
        return organizationService.getUserIdOfEmployee(Long.valueOf(subject.toString()));
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
