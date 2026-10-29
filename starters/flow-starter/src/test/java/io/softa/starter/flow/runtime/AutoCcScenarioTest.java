package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowCcConfig;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowCcReadRequest;
import io.softa.starter.flow.runtime.api.FlowCommentRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowParticipants;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowCcConfigService;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automatic CC rules over a single-step approval (START → Approval → END): a rule's recipients are recorded
 * like a manual CC's — a CC entry with no actor on the node the rule fired at — so they get a CC task,
 * take part in the instance (they comment on it) and mark the CC read. A step's own rules fire at it; a
 * recipient is CC'd once per node and cycle.
 */
class AutoCcScenarioTest {

    private static final String FLOW_ID = "9200";
    private static final String INITIATOR = "initiator-1";
    private static final String FIRST = "manager-1";
    private static final String SECOND = "manager-2";

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final List<FlowCcConfig> rules = new ArrayList<>();
    private final List<FlowNotificationEvent> notices = new ArrayList<>();
    private final FlowApprovalTaskProjector projector =
            new FlowApprovalTaskProjector(new ApprovalAuditReader(new NoopApprovalActionLedger()));

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithCcRules(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler,
            new RuleList(rules),
            notices::add);

    @Test
    void aRuleCcsItsRecipientsAsAManualCcWould() {
        publish("AnyOne");
        rules.add(rule(null, CcTiming.ON_SUBMIT, "cc-1", true));

        FlowExecutionState started = start();
        ApprovalActionAuditEntry cc = ccsOf(started).getFirst();
        assertEquals("start", cc.getNodeId());
        assertEquals(1, cc.getCycleNumber());
        assertEquals("cc-1", cc.getTargetActorId());
        assertNull(cc.getActorId(), "no person sent it");

        FlowApprovalTask task = projector.project(started).stream()
                .filter(t -> FlowApprovalTaskType.CC.equals(t.getTaskType()))
                .findFirst()
                .orElseThrow();
        assertEquals("cc-1", task.getActorId());
        assertTrue(FlowParticipants.isParticipant(started, started.getApprovalAuditDelta(), "cc-1"));
        assertEquals(List.of("cc-1"), ccNotices().getFirst().recipientActorIds());

        assertDoesNotThrow(() -> engine.addComment(comment(started, "cc-1")));
        assertDoesNotThrow(() -> engine.readCc(read(started, "start", 1, "cc-1")));
    }

    @Test
    void aStepsOwnRuleCcsOnceWhenItsApproversApproveIt() {
        publish("Unanimous");
        rules.add(rule("approval", CcTiming.ON_APPROVE, "cc-2", true));
        rules.add(rule("elsewhere", CcTiming.ON_APPROVE, "cc-other", true));
        FlowExecutionState started = start();

        engine.approve(approve(started, FIRST));
        FlowExecutionState completed = engine.approve(approve(started, SECOND));

        assertEquals(FlowExecutionStatus.COMPLETED, completed.getStatus());
        List<ApprovalActionAuditEntry> ccs = ccsOf(completed);
        assertEquals(1, ccs.size(), "one CC per node and cycle, and none for another step's rule");
        assertEquals("approval", ccs.getFirst().getNodeId());
        assertEquals("cc-2", ccs.getFirst().getTargetActorId());
        assertEquals(1, ccNotices().size());
    }

    @Test
    void completingTheApprovalCcsTheCompletionRule() {
        publish("AnyOne");
        rules.add(rule(null, CcTiming.ON_COMPLETE, "cc-3", false));
        FlowExecutionState started = start();
        assertTrue(ccsOf(started).isEmpty(), "not complete yet");

        FlowExecutionState completed = engine.approve(approve(started, FIRST));

        ApprovalActionAuditEntry cc = ccsOf(completed).getFirst();
        assertEquals("approval", cc.getNodeId());
        assertEquals("cc-3", cc.getTargetActorId());
        assertTrue(ccNotices().isEmpty(), "the rule sends no notification, and still CCs");
    }

    @Test
    void rejectingAStepCcsItsRejectionRule() {
        publish("AnyOne");
        rules.add(rule("approval", CcTiming.ON_REJECT, "cc-4", true));
        FlowExecutionState started = start();

        FlowRejectRequest reject = new FlowRejectRequest();
        reject.setInstanceId(started.getInstanceId());
        reject.setNodeId("approval");
        reject.setActorId(FIRST);
        reject.setComment("not acceptable");
        FlowExecutionState rejected = engine.reject(reject);

        assertEquals(FlowExecutionStatus.REJECTED, rejected.getStatus());
        assertEquals("cc-4", ccsOf(rejected).getFirst().getTargetActorId());
    }

    private void publish(String approvalMode) {
        publishService.publish(FLOW_ID, DesignFlowDefinition.builder()
                .name("Auto CC")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest", null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of("approvers", List.of(FIRST, SECOND), "approvalMode", approvalMode))
                                        .build(),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")))
                        .build())
                .build());
    }

    private FlowExecutionState start() {
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        return engine.start(start);
    }

    private static FlowCcConfig rule(String nodeId, CcTiming timing, String recipient, boolean notify) {
        FlowCcConfig rule = new FlowCcConfig();
        rule.setFlowId(FLOW_ID);
        rule.setNodeId(nodeId);
        rule.setCcTiming(timing);
        rule.setCcName("rule-" + recipient);
        rule.setRecipientType("USER");
        rule.setRecipientConfig(recipient);
        rule.setSendNotification(notify);
        rule.setActive(true);
        return rule;
    }

    private static FlowApproveRequest approve(FlowExecutionState state, String actorId) {
        FlowApproveRequest approve = new FlowApproveRequest();
        approve.setInstanceId(state.getInstanceId());
        approve.setNodeId("approval");
        approve.setActorId(actorId);
        return approve;
    }

    private static FlowCommentRequest comment(FlowExecutionState state, String actorId) {
        FlowCommentRequest comment = new FlowCommentRequest();
        comment.setInstanceId(state.getInstanceId());
        comment.setActorId(actorId);
        comment.setComment("noted");
        return comment;
    }

    private static FlowCcReadRequest read(FlowExecutionState state, String nodeId, int cycle, String actorId) {
        FlowCcReadRequest read = new FlowCcReadRequest();
        read.setInstanceId(state.getInstanceId());
        read.setNodeId(nodeId);
        read.setCycleNumber(cycle);
        read.setActorId(actorId);
        return read;
    }

    private static List<ApprovalActionAuditEntry> ccsOf(FlowExecutionState state) {
        return state.getApprovalAuditDelta() == null ? List.of() : state.getApprovalAuditDelta().stream()
                .filter(entry -> ApprovalActionType.CC.equals(entry.getAction()))
                .toList();
    }

    private List<FlowNotificationEvent.CcSent> ccNotices() {
        return notices.stream()
                .filter(FlowNotificationEvent.CcSent.class::isInstance)
                .map(FlowNotificationEvent.CcSent.class::cast)
                .toList();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }

    /** The flow's active rules for a timing: its flow-level ones, and those of the node asked about. */
    private record RuleList(List<FlowCcConfig> rules) implements FlowCcConfigService {

        @Override
        public List<FlowCcConfig> getActiveConfigs(String flowId, String nodeId, CcTiming ccTiming) {
            return rules.stream()
                    .filter(rule -> Objects.equals(flowId, rule.getFlowId()) && Boolean.TRUE.equals(rule.getActive())
                            && Objects.equals(ccTiming, rule.getCcTiming())
                            && (rule.getNodeId() == null || Objects.equals(rule.getNodeId(), nodeId)))
                    .toList();
        }

        @Override
        public FlowCcConfig createConfig(FlowCcConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FlowCcConfig> listByFlowId(String flowId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deactivateConfig(Long configId) {
            throw new UnsupportedOperationException();
        }
    }
}
