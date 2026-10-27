package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowApprovalPreview.AutoApproval;
import io.softa.starter.flow.dto.FlowApprovalPreview.AutoApprovalReason;
import io.softa.starter.flow.dto.FlowApprovalPreview.Step;
import io.softa.starter.flow.dto.FlowApprovalPreview.StepOutcome;
import io.softa.starter.flow.dto.FlowApprovalPreview.Stop;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.nodeconfig.ScriptNodeConfig;
import io.softa.starter.flow.runtime.nodeconfig.SubflowNodeConfig;
import io.softa.starter.flow.runtime.nodeconfig.TaskNodeConfig;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Works out the approval path of a record's submission without running its flow, for the approval
 * path preview ({@code FlowLaunchService.previewRecord}). It walks the flow from its start node the
 * way {@link FlowExecutionOrchestrator} does, every approval step assumed approved: routes are decided
 * by {@link FlowGatewayRouter}, each step's approvers by {@link PendingApprovalFactory} and its
 * automatic approvals by {@link ApproverDedupService}, on the state the submission would start from —
 * which is never saved. No node runs; no instance, task, ledger row or notification is created, and
 * no delegation's use is counted.
 *
 * <p>Nothing is guessed. Once the walk passes a node it does not run — a script, a task, a subflow —
 * the variables that node writes are unknown, as is the instance id from the start: a route whose
 * conditions read one is undecided and not followed, and a step whose approvers come from the
 * variables (a dynamic approver source) is undetermined. With approver dedup on, so is a step whose
 * approver may or may not have approved an earlier step, one of several there who could pass it.
 */
@Component
public class ApprovalPathPreviewer {

    /** Start variables whose values exist only once the instance has started. */
    private static final Set<String> SET_ON_START = Set.of("_instanceId");

    private final FlowGatewayRouter gatewayRouter;
    private final PendingApprovalFactory pendingApprovalFactory;
    private final ApproverResolutionService approverResolutionService;
    private final ApproverDedupService approverDedupService;
    private final FlowAuditService auditService;

    public ApprovalPathPreviewer(FlowGatewayRouter gatewayRouter,
                                 PendingApprovalFactory pendingApprovalFactory,
                                 ApproverResolutionService approverResolutionService,
                                 ApproverDedupService approverDedupService,
                                 FlowAuditService auditService) {
        this.gatewayRouter = gatewayRouter;
        this.pendingApprovalFactory = pendingApprovalFactory;
        this.approverResolutionService = approverResolutionService;
        this.approverDedupService = approverDedupService;
        this.auditService = auditService;
    }

    /**
     * The approval path {@code request} — the start request of a record's submission — would take on
     * {@code definition}. Users come as ids; the caller names them.
     */
    public FlowApprovalPreview preview(CompiledFlowDefinition definition, FlowStartRequest request) {
        Walk walk = new Walk(definition, FlowRuntimeFacade.initialState(definition, request, true, null));
        walk.run();
        return FlowApprovalPreview.builder()
                .status(FlowApprovalPreview.Status.READY)
                .flow(new FlowApprovalPreview.Flow(definition.getFlowId(), definition.getFlowName()))
                .steps(walk.steps)
                .undecided(walk.undecided)
                .failure(walk.failure)
                .build();
    }

    /** Thrown by a route condition that reads data only known once the approval runs. */
    private static final class UndecidedRoute extends RuntimeException {

        UndecidedRoute() {
            super(null, null, false, false);
        }
    }

    /** One walk of a flow: the state it walks on and what it finds. */
    private final class Walk {

        private final CompiledFlowDefinition definition;
        /**
         * The state the submission would start from. The approvals the walk assumes are recorded on it,
         * so that later steps' dedup sees them, as on a running instance.
         */
        private final FlowExecutionState state;
        /** Variables whose values are not known before the approval runs. */
        private final Set<String> unknownVariables = new HashSet<>(SET_ON_START);
        /** A node passed may have written any variable: its outputs are not declared. */
        private boolean anyVariableUnknown;
        /** A node passed may have written variables: approvers resolved from them are not known. */
        private boolean variablesWritten;
        /** By step: approvers who may or may not approve it — one of several who could pass it. */
        private final Map<String, Set<String>> possibleApprovers = new HashMap<>();
        /** Steps whose approvers are not known, so anyone may have approved them. */
        private final Set<String> unknownApprovers = new HashSet<>();

        private final List<Step> steps = new ArrayList<>();
        private final List<Stop> undecided = new ArrayList<>();
        private Stop failure;

        Walk(CompiledFlowDefinition definition, FlowExecutionState state) {
            this.definition = definition;
            this.state = state;
        }

        void run() {
            Deque<String> queue = new ArrayDeque<>(definition.getEntryNodeIds());
            while (!queue.isEmpty() && failure == null) {
                String nodeId = queue.removeFirst();
                CompiledFlowNode node = definition.getNodeIndex().get(nodeId);
                if (node == null) {
                    failure = new Stop(nodeId, null, "Node not found in compiled definition: " + nodeId);
                    return;
                }
                if (FlowExecutionOrchestrator.isParallelJoin(node)
                        && FlowExecutionOrchestrator.joinStillWaits(definition, state, node)) {
                    continue;
                }
                if (FlowNodeType.RETURN_VALUE.equals(node.getType())) {
                    // Ends the flow, branches still queued included.
                    return;
                }
                try {
                    if (FlowNodeType.APPROVAL.equals(node.getType())) {
                        previewStep(node);
                    } else {
                        passOver(node);
                    }
                    if (failure == null) {
                        queue.addAll(gatewayRouter.resolveNextNodeIds(definition, node, this::decide));
                    }
                } catch (UndecidedRoute e) {
                    undecided.add(new Stop(node.getNodeId(), node.getLabel(), null));
                } catch (RuntimeException e) {
                    // What fails the instance here ends the walk here.
                    failure = new Stop(node.getNodeId(), node.getLabel(), e.getMessage());
                }
            }
        }

        /** A route condition, decided over the start variables unless it reads one that is not known. */
        private boolean decide(String expression) {
            if (anyVariableUnknown || ComputeUtils.getVariables(expression).stream().anyMatch(unknownVariables::contains)) {
                throw new UndecidedRoute();
            }
            return ComputeUtils.executeBoolean(expression, new LinkedHashMap<>(state.getVariables()));
        }

        /** A node the preview does not run: the variables it writes are unknown from here on. */
        private void passOver(CompiledFlowNode node) {
            switch (node.getType()) {
                case START, END, PARALLEL_FORK, PARALLEL_JOIN, INCLUSIVE_GATEWAY, TIMER -> {
                    // write no variable
                }
                default -> {
                    Object config = node.getParsedConfig();
                    if (config instanceof ScriptNodeConfig script) {
                        written(declared(script.getOutputVariable()));
                    } else if (config instanceof SubflowNodeConfig subflow) {
                        Set<String> names = new HashSet<>(declared(subflow.getOutputVariable()));
                        if (subflow.getOutputMapping() != null) {
                            names.addAll(subflow.getOutputMapping().keySet());
                        }
                        written(names);
                    } else if (config instanceof TaskNodeConfig task && StringUtils.hasText(task.getOutputVariable())
                            && !FlowNodeType.ASYNC_TASK.equals(node.getType())) {
                        written(Set.of(task.getOutputVariable()));
                    } else {
                        // A task without an output variable merges its whole result, an async task its callback's too.
                        anyVariableUnknown = true;
                        variablesWritten = true;
                    }
                }
            }
        }

        private void written(Collection<String> names) {
            unknownVariables.addAll(names);
            variablesWritten |= !names.isEmpty();
        }

        private void previewStep(CompiledFlowNode node) {
            if (variablesWritten && approverResolutionService.hasDynamicApproverSource(node)) {
                // Resolved from variables a node before it writes once the approval runs.
                steps.add(step(node, StepOutcome.UNDETERMINED, List.of(), null, List.of()));
                unknownApprovers.add(node.getNodeId());
                recordApproval(node, null);
                return;
            }
            PendingApproval pending = pendingApprovalFactory.previewPendingApproval(definition, node, state);
            if (pending == null) {
                previewEmptyStep(node);
                return;
            }
            List<ApprovalActionAuditEntry> recorded = state.getApprovalAuditDelta();
            int before = recorded.size();
            boolean passes = approverDedupService.applyAndCheckComplete(definition, node, state, pending);
            List<AutoApproval> autoApprovals = autoApprovals(List.copyOf(recorded.subList(before, recorded.size())));
            if (passes) {
                steps.add(step(node, StepOutcome.AUTO_APPROVED, List.of(), null, autoApprovals));
                return;
            }
            List<String> remaining = pending.getApprovers().stream()
                    .filter(approver -> !pending.getApprovedActors().contains(approver))
                    .toList();
            int required = pending.getRequiredApprovalCount() - pending.getApprovedActors().size();
            StepOutcome outcome = mayHaveApprovedEarlier(node, remaining) ? StepOutcome.UNDETERMINED : StepOutcome.WAITING;
            steps.add(step(node, outcome, remaining, required, autoApprovals));
            if (required >= remaining.size()) {
                // The step passes only once each of them has approved it.
                remaining.forEach(approver -> recordApproval(node, approver));
            } else {
                // Some of them will; which ones is only known once they act.
                recordApproval(node, null);
                possibleApprovers.put(node.getNodeId(), Set.copyOf(remaining));
            }
        }

        /** A step that resolves to nobody, as its empty-approver strategy has the engine treat it. */
        private void previewEmptyStep(CompiledFlowNode node) {
            switch (pendingApprovalFactory.resolveEmptyApproverStrategy(node)) {
                case AUTO_APPROVE -> {
                    auditService.appendEmptyStepAutoApproval(state, definition, node);
                    steps.add(step(node, StepOutcome.NO_APPROVER_APPROVED, List.of(), null, List.of()));
                }
                case SKIP -> steps.add(step(node, StepOutcome.NO_APPROVER_SKIPPED, List.of(), null, List.of()));
                case ERROR -> {
                    steps.add(step(node, StepOutcome.NO_APPROVER_FAILS, List.of(), null, List.of()));
                    failure = new Stop(node.getNodeId(), node.getLabel(), PendingApprovalFactory.emptyApproverMessage(node));
                }
            }
        }

        /**
         * Whether one of {@code approvers} may or may not have approved an earlier step that the flow's
         * approver dedup counts here — which would pass them here automatically.
         */
        private boolean mayHaveApprovedEarlier(CompiledFlowNode node, List<String> approvers) {
            ApproverDedupStrategy strategy = ApproverDedupService.strategyOf(definition);
            Set<String> earlierSteps = new HashSet<>();
            approverDedupService.countedApprovals(state, node.getNodeId(), strategy)
                    .forEach(approval -> earlierSteps.add(approval.getNodeId()));
            for (String step : earlierSteps) {
                Set<String> possible = possibleApprovers.getOrDefault(step, Set.of());
                if (unknownApprovers.contains(step) || approvers.stream().anyMatch(possible::contains)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Records on the walk's state that {@code approver} approves the step — or someone does, when
         * null — as the engine records an approval, so later steps' dedup sees it.
         */
        private void recordApproval(CompiledFlowNode node, String approver) {
            auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                    .flowId(definition.getFlowId())
                    .flowRevision(definition.getRevision())
                    .nodeId(node.getNodeId())
                    .nodeLabel(node.getLabel())
                    .action(ApprovalActionType.APPROVE)
                    .actorId(approver)
                    .build());
        }

        /** The automatic approvals {@link ApproverDedupService} recorded on a step, and why each. */
        private List<AutoApproval> autoApprovals(List<ApprovalActionAuditEntry> entries) {
            return entries.stream()
                    .filter(entry -> ApprovalActionType.AUTO_APPROVE.equals(entry.getAction()) && entry.getActorId() != null)
                    .map(entry -> new AutoApproval(actor(entry.getActorId()),
                            entry.getActorId().equals(state.getInitiatorId())
                                    ? AutoApprovalReason.SUBMITTER
                                    : AutoApprovalReason.EARLIER_APPROVAL))
                    .toList();
        }

        private Step step(CompiledFlowNode node, StepOutcome outcome, List<String> approvers, Integer requiredApprovals,
                          List<AutoApproval> autoApprovals) {
            return Step.builder()
                    .nodeId(node.getNodeId())
                    .nodeLabel(node.getLabel())
                    .outcome(outcome)
                    .approvers(approvers.stream().map(Walk::actor).toList())
                    .requiredApprovals(requiredApprovals)
                    .autoApprovals(autoApprovals)
                    .build();
        }

        private static FlowRecordApprovalsView.Actor actor(String userId) {
            return new FlowRecordApprovalsView.Actor(userId, null);
        }

        private static List<String> declared(String outputVariable) {
            return StringUtils.hasText(outputVariable) ? List.of(outputVariable) : List.of();
        }
    }
}
