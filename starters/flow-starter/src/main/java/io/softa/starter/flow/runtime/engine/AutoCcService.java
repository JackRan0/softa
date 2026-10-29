package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.entity.FlowCcConfig;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowCcConfigService;

/**
 * Processes automatic CC rules ({@link FlowCcConfig}): at a timing, the flow's active rules for it CC their
 * recipients — the flow-level rules, and at {@code OnApprove} / {@code OnReject} also the rules of the step
 * acted on.
 * <p>
 * An automatic CC is recorded like a manual one: one {@code CC} entry per recipient in the approval history,
 * with no actor — no person sent it — on the node it happened at (the step acted on, or the start node when
 * the approval is submitted) and that node's current cycle. The entry gives the recipient a CC task in the
 * inbox, makes them a participant of the instance — they open it and comment on it — and is what they mark
 * as read. A recipient already CC'd on the same node and cycle is not CC'd again, so a step several
 * approvers approve CCs once. The rule's notification, when it sends one, goes to the recipients CC'd now.
 * The configurations are read past the caller's data permissions — they are the flow's, not the actor's.
 * </p>
 */
@Slf4j
@Component
public class AutoCcService {

    private final FlowCcConfigService ccConfigService;

    private final FlowActionContextService contextService;

    private final FlowAuditService auditService;

    private final ApprovalAuditReader auditReader;

    private final ApprovalNotificationService notificationService;

    public AutoCcService(FlowCcConfigService ccConfigService,
                         FlowActionContextService contextService,
                         FlowAuditService auditService,
                         ApprovalAuditReader auditReader,
                         ApprovalNotificationService notificationService) {
        this.ccConfigService = ccConfigService;
        this.contextService = contextService;
        this.auditService = auditService;
        this.auditReader = auditReader;
        this.notificationService = notificationService;
    }

    /**
     * Processes the flow's active CC rules for a timing, and saves the instance when they CC'd anyone.
     *
     * @param state    the instance
     * @param node     where it happens — the step acted on, or the start node — which the CC entries are
     *                 recorded on, and whose own rules apply at {@code OnApprove} and {@code OnReject}
     * @param ccTiming the timing
     */
    public void processCc(FlowExecutionState state, CompiledFlowNode node, CcTiming ccTiming) {
        if (state == null || node == null) {
            return;
        }
        // A step's own rules are about what happens at it; submitting and completing happen to the approval.
        String ruleNodeId = CcTiming.ON_APPROVE.equals(ccTiming) || CcTiming.ON_REJECT.equals(ccTiming)
                ? node.getNodeId()
                : null;
        List<FlowCcConfig> configs = ContextUtils.withoutPermissionChecks(
                () -> ccConfigService.getActiveConfigs(state.getFlowId(), ruleNodeId, ccTiming));
        if (configs == null || configs.isEmpty()) {
            return;
        }

        boolean recorded = false;
        for (FlowCcConfig config : configs) {
            try {
                recorded |= processSingleCc(state, node, config);
            } catch (Exception e) {
                log.error("Error processing auto-CC config '{}' for instance {}: {}",
                        config.getCcName(), state.getInstanceId(), e.getMessage(), e);
            }
        }
        if (recorded) {
            contextService.persistState(state);
        }
    }

    /** CCs the rule's recipients not CC'd on the node and cycle yet; whether it CC'd anyone. */
    private boolean processSingleCc(FlowExecutionState state, CompiledFlowNode node, FlowCcConfig config) {
        // Evaluate condition if present
        if (config.getCcCondition() != null && !config.getCcCondition().isBlank()) {
            try {
                Map<String, Object> env = new LinkedHashMap<>(state.getVariables() != null ? state.getVariables() : Map.of());
                boolean shouldCc = ComputeUtils.executeBoolean(config.getCcCondition(), env);
                if (!shouldCc) {
                    log.debug("CC condition not met for config '{}' on instance {}", config.getCcName(), state.getInstanceId());
                    return false;
                }
            } catch (Exception e) {
                log.warn("Error evaluating CC condition for config '{}': {}", config.getCcName(), e.getMessage());
                return false;
            }
        }

        // Resolve recipients from config
        List<String> recipientActorIds = resolveRecipients(config, state);
        if (recipientActorIds.isEmpty()) {
            log.debug("No CC recipients resolved for config '{}'", config.getCcName());
            return false;
        }

        String message = config.getMessageTemplate() != null
                ? config.getMessageTemplate()
                : "You have been CC'd on: " + (state.getTitle() != null ? state.getTitle() : state.getInstanceId());
        int cycleNumber = currentCycle(state, node.getNodeId());
        List<String> ccdActorIds = new ArrayList<>();
        for (String recipient : recipientActorIds) {
            if (auditService.findCcAudit(state, node.getNodeId(), cycleNumber, recipient).isEmpty()) {
                auditService.appendAutoCcAudit(state, node, cycleNumber, recipient, message);
                ccdActorIds.add(recipient);
            }
        }
        if (ccdActorIds.isEmpty()) {
            return false;
        }

        log.info("Processing auto-CC '{}' for instance {}, {} recipients, timing={}",
                config.getCcName(), state.getInstanceId(), ccdActorIds.size(), config.getCcTiming());
        auditService.addTrace(state, state.getFlowId(), node, FlowTraceEventType.APPROVAL_CCED,
                "Auto-CC '" + config.getCcName() + "' sent to " + ccdActorIds);

        if (Boolean.TRUE.equals(config.getSendNotification())) {
            notificationService.notify(new FlowNotificationEvent.CcSent(state, node.getNodeId(), ccdActorIds, message));
        }
        return true;
    }

    /** The node's current cycle: the latest it waits on or has recorded, the first when it has neither. */
    private int currentCycle(FlowExecutionState state, String nodeId) {
        int cycle = 1;
        if (state.getPendingApprovals() != null) {
            for (PendingApproval pending : state.getPendingApprovals()) {
                if (nodeId.equals(pending.getNodeId()) && pending.getCycleNumber() != null) {
                    cycle = Math.max(cycle, pending.getCycleNumber());
                }
            }
        }
        for (ApprovalActionAuditEntry entry : auditReader.fullHistory(state)) {
            if (nodeId.equals(entry.getNodeId()) && entry.getCycleNumber() != null) {
                cycle = Math.max(cycle, entry.getCycleNumber());
            }
        }
        return cycle;
    }

    /**
     * Resolve recipients from the CC config, each once.
     * Supports recipientType: USER (comma-separated IDs in recipientConfig),
     * INITIATOR (uses state.initiatorId), or raw list.
     */
    private List<String> resolveRecipients(FlowCcConfig config, FlowExecutionState state) {
        List<String> recipients = new ArrayList<>();
        String type = config.getRecipientType();

        if ("INITIATOR".equalsIgnoreCase(type)) {
            if (state.getInitiatorId() != null) {
                recipients.add(state.getInitiatorId());
            }
        } else if (config.getRecipientConfig() != null) {
            // USER, and any other type: comma-separated user IDs
            for (String id : config.getRecipientConfig().split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty() && !recipients.contains(trimmed)) {
                    recipients.add(trimmed);
                }
            }
        }

        return recipients;
    }
}
