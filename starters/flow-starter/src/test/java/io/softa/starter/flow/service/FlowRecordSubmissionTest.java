package io.softa.starter.flow.service;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.ApprovalOutcomeType;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.StubFlowInstanceStore;
import io.softa.starter.flow.runtime.TestFlowRuntimeFactory;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.ApprovalOutcome;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.service.impl.FlowLaunchServiceImpl;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Record submission end to end on the in-memory engine: the submit trigger chooses the flow, the
 * handler is called in order, approver steps skip the submitter, and every result reaches the
 * record through the outcome listener.
 */
class FlowRecordSubmissionTest {

    private static final String MODEL = "LeaveRequest";
    private static final String ROW = "42";
    private static final String SUBMITTER = "1001";
    private static final String MANAGER = "2001";

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    /** When each instance was first saved: the store's creation order, which the stub does not keep. */
    private final Map<String, LocalDateTime> createdAt = new HashMap<>();
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore() {
        @Override
        public FlowExecutionState save(FlowExecutionState state) {
            createdAt.putIfAbsent(state.getInstanceId(), LocalDateTime.now().plusNanos(createdAt.size()));
            return super.save(state);
        }
    };
    private final ApprovalAuditReader auditReader = new ApprovalAuditReader(new NoopApprovalActionLedger());
    private final ModelApprovalHandler handler = mock(ModelApprovalHandler.class);
    private final ModelApprovalHandlerRegistry handlerRegistry = ModelApprovalHandlerRegistry.of(List.of(handler));
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry, instanceStore,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler, state -> {},
            new ApprovalOutcomeListener(handlerRegistry, auditReader));
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    // The preview's collaborators stay null: FlowApprovalPreviewTest covers it.
    private final FlowLaunchServiceImpl launchService = new FlowLaunchServiceImpl(
            publishService, engine, bundleRegistry, compiler, handlerRegistry,
            new DefaultFlowTriggerRegistry(bundleRegistry), instanceService, modelService, null, null, null);

    private final Map<String, Object> record = new LinkedHashMap<>(Map.of("id", 42L, "leaveType", "Annual", "days", 3));
    private MockedStatic<IdUtils> idUtils;

    @BeforeEach
    void setUp() {
        idUtils = mockStatic(IdUtils.class);
        idUtils.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(handler.modelName()).thenReturn(MODEL);
        when(handler.prepare(ROW)).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 3)));
        when(modelService.getById(eq(MODEL), any())).thenAnswer(invocation -> Optional.of(record));
        // The instance table, as the store holds it.
        when(instanceService.findByModelNameAndRowId(anyString(), anyString())).thenAnswer(invocation -> {
            List<FlowInstance> instances = new ArrayList<>();
            for (FlowExecutionStatus status : FlowExecutionStatus.values()) {
                for (FlowExecutionState state : instanceStore.listByStatus(status)) {
                    if (Objects.equals(state.getModelName(), invocation.getArgument(0))
                            && Objects.equals(state.getRowId(), invocation.getArgument(1))) {
                        FlowInstance instance = new FlowInstance();
                        instance.setInstanceId(state.getInstanceId());
                        instance.setBundleId(state.getBundleId());
                        instance.setStatus(state.getStatus());
                        instance.setInitiatorId(state.getInitiatorId());
                        instance.setModelName(state.getModelName());
                        instance.setRecordApproval(state.getRecordApproval());
                        instance.setCreatedTime(createdAt.get(state.getInstanceId()));
                        instances.add(instance);
                    }
                }
            }
            return instances;
        });
    }

    @AfterEach
    void tearDown() {
        idUtils.close();
    }

    @Test
    void submitStartsTheFlowWhoseSubmitTriggerMatchesTheRecord() {
        publishService.publish("annual", approvalFlow("Annual leave", "leaveType == 'Annual'", List.of(MANAGER)));
        publishService.publish("sick", approvalFlow("Sick leave", "leaveType == 'Sick'", List.of(MANAGER)));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        assertEquals("annual", state.getFlowId());
        assertEquals("Leave of Alice", state.getTitle());
        assertEquals(SUBMITTER, state.getInitiatorId());
        assertEquals(MODEL, state.getModelName());
        assertEquals(ROW, state.getRowId());
        assertEquals(3, state.getVariables().get("days"));
        assertEquals("Submit", state.getVariables().get("_triggerType"));
        assertEquals(List.of(MANAGER), state.getPendingApprovals().getFirst().getApprovers());
        assertTrue(state.isApprovalOfRecord());
        InOrder order = inOrder(handler);
        order.verify(handler).prepare(ROW);
        order.verify(handler).onSubmitted(ROW);
        verify(handler, never()).onOutcome(anyString(), any());
    }

    @Test
    void submitIsRefusedWhenNoFlowApplies() {
        publishService.publish("sick", approvalFlow("Sick leave", "leaveType == 'Sick'", List.of(MANAGER)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("No approval flow is configured"), error.getMessage());
        verify(handler, never()).onSubmitted(anyString());
    }

    @Test
    void submitIsRefusedWhenSeveralFlowsApply() {
        publishService.publish("a", approvalFlow("Flow A", null, List.of(MANAGER)));
        publishService.publish("b", approvalFlow("Flow B", "days > 1", List.of(MANAGER)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("Several approval flows"), error.getMessage());
        verify(handler, never()).onSubmitted(anyString());
    }

    @Test
    void aRecordAlreadyInApprovalCannotBeSubmittedAgain() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        launchService.submitRecord(MODEL, ROW, SUBMITTER);

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("already in approval"), error.getMessage());
    }

    @Test
    void anAutomationRunOnTheRecordNeitherBlocksSubmissionNorWritesBack() {
        publishService.publish("automation", flow("Automation", new ApiTrigger(null), null));
        FlowStartRequest automation = new FlowStartRequest();
        automation.setFlowId("automation");
        automation.setInitiatorId(SUBMITTER);
        automation.setModelName(MODEL);
        automation.setRowId(ROW);
        assertEquals(FlowExecutionStatus.COMPLETED, engine.startAutomation(automation).getStatus());
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));

        assertEquals(FlowExecutionStatus.WAITING, launchService.submitRecord(MODEL, ROW, SUBMITTER).getStatus());

        verify(handler, never()).onOutcome(anyString(), any());
    }

    @Test
    void theSubmitFlowIsNotStartedByAnApiStart() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        FlowStartRequest apiStart = new FlowStartRequest();
        apiStart.setFlowId("annual");
        apiStart.setInitiatorId(SUBMITTER);
        apiStart.setModelName(MODEL);
        apiStart.setRowId(ROW);

        assertThrows(FlowActionValidationException.class, () -> engine.startAutomation(apiStart));
        verify(handler, never()).onSubmitted(anyString());
    }

    @Test
    void theSubmitFlowStartedAnotherWayIsNotTheRecordsApproval() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        // A debug run of the same flow, on the same record.
        FlowStartRequest debugRun = new FlowStartRequest();
        debugRun.setFlowId("annual");
        debugRun.setInitiatorId(SUBMITTER);
        debugRun.setModelName(MODEL);
        debugRun.setRowId(ROW);
        FlowExecutionState other = engine.start(debugRun);
        assertFalse(other.isApprovalOfRecord());

        engine.approve(nodeRequest(new FlowApproveRequest(), other.getInstanceId(), MANAGER, "fine"));

        verify(handler, never()).onOutcome(anyString(), any());
        assertEquals(FlowExecutionStatus.WAITING, launchService.submitRecord(MODEL, ROW, SUBMITTER).getStatus());
    }

    @Test
    void approvalWritesApprovedBackWithTheDecidingApprover() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        engine.approve(nodeRequest(new FlowApproveRequest(), started.getInstanceId(), MANAGER, "enjoy"));

        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.APPROVED, started.getInstanceId(), MANAGER, "enjoy"),
                capturedOutcome());
    }

    @Test
    void rejectionWritesRejectedBack() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        engine.reject(nodeRequest(new FlowRejectRequest(), started.getInstanceId(), MANAGER, "busy week"));

        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.REJECTED, started.getInstanceId(), MANAGER, "busy week"),
                capturedOutcome());
    }

    @Test
    void aReturnEndsTheApprovalAndTheNextSubmissionStartsANewOneFromTheStart() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        FlowExecutionState returned = engine.returnApproval(
                nodeRequest(new FlowReturnRequest(), started.getInstanceId(), MANAGER, "fix the dates"));
        assertEquals(FlowExecutionStatus.RETURNED, returned.getStatus());
        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.RETURNED, started.getInstanceId(), MANAGER, "fix the dates"),
                capturedOutcome());

        when(handler.prepare(ROW)).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 2)));
        FlowExecutionState submittedAgain = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertNotEquals(started.getInstanceId(), submittedAgain.getInstanceId());
        assertEquals(FlowExecutionStatus.WAITING, submittedAgain.getStatus());
        assertEquals("approval", submittedAgain.getPendingApprovals().getFirst().getNodeId());
        assertEquals(2, submittedAgain.getVariables().get("days"));
        verify(handler, times(2)).onSubmitted(ROW);
    }

    @Test
    void theFlowIsChosenAgainForTheRecordAsItIsNow() {
        publishService.publish("short", approvalFlow("Short leave", "days <= 3", List.of(MANAGER)));
        publishService.publish("long", approvalFlow("Long leave", "days > 3", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        assertEquals("short", started.getFlowId());
        engine.returnApproval(nodeRequest(new FlowReturnRequest(), started.getInstanceId(), MANAGER, "make it a week"));

        record.put("days", 5);
        FlowExecutionState submittedAgain = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals("long", submittedAgain.getFlowId());
    }

    @Test
    void withdrawalWritesWithdrawnBack() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        FlowExecutionState withdrawn = launchService.withdrawRecord(MODEL, ROW, SUBMITTER, "not needed");

        assertEquals(FlowExecutionStatus.WITHDRAWN, withdrawn.getStatus());
        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.WITHDRAWN, started.getInstanceId(), SUBMITTER, "not needed"),
                capturedOutcome());
    }

    @Test
    void aRecordWithNoApprovalInProgressCannotBeWithdrawn() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.withdrawRecord(MODEL, ROW, SUBMITTER, "old request"));

        assertTrue(error.getMessage().contains("no approval in progress"), error.getMessage());
        verify(handler, never()).onOutcome(anyString(), any());
    }

    @Test
    void afterAFailedApprovalTheSubmitterTakesTheRecordBack() {
        FlowExecutionState failed = FlowExecutionState.builder()
                .instanceId("failed-1").initiatorId(SUBMITTER).recordApproval(true)
                .modelName(MODEL).rowId(ROW).status(FlowExecutionStatus.FAILED)
                .build();
        instanceStore.save(failed);

        assertThrows(FlowAuthorizationException.class,
                () -> launchService.withdrawRecord(MODEL, ROW, MANAGER, "not mine"));
        FlowExecutionState state = launchService.withdrawRecord(MODEL, ROW, SUBMITTER, "try again later");

        assertEquals(FlowExecutionStatus.FAILED, state.getStatus(), "the failed instance stays as it is");
        verify(handler).onOutcome(ROW, new ApprovalOutcome(ApprovalOutcomeType.WITHDRAWN, "failed-1", SUBMITTER,
                "try again later"));
    }

    @Test
    void aFlowWithoutApprovalStepsCompletesAfterTheRecordIsInApproval() {
        publishService.publish("auto", flow("Auto approve", new SubmitTrigger(MODEL, null), null));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
        InOrder order = inOrder(handler);
        order.verify(handler).onSubmitted(ROW);
        order.verify(handler).onOutcome(eq(ROW), argThat(o -> o.type() == ApprovalOutcomeType.APPROVED));
    }

    @Test
    void aStepWhoseOnlyApproverIsTheSubmitterIsApprovedOnRecord() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(SUBMITTER)));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
        assertTrue(state.getApprovalAuditDelta().stream().anyMatch(entry ->
                entry.getAction() == ApprovalActionType.AUTO_APPROVE && SUBMITTER.equals(entry.getActorId())));
        // The engine's note on an automatic approval is no one's comment.
        verify(handler).onOutcome(ROW, new ApprovalOutcome(ApprovalOutcomeType.APPROVED, state.getInstanceId(),
                SUBMITTER, null));
    }

    @Test
    void theSubmittersApprovalPassesAnAnyOneStepTheyShareWithOthers() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(SUBMITTER, MANAGER)));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
    }

    @Test
    void anAllApproveStepTheSubmitterSharesStillWaitsForTheOthers() {
        DesignFlowDefinition design = approvalFlow("Annual leave", null, List.of(SUBMITTER, MANAGER));
        design.getGraph().getNodes().set(1, FlowGraphNode.builder()
                .id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                .config(Map.of("approvers", List.of(SUBMITTER, MANAGER), "approvalMode", "Unanimous"))
                .build());
        publishService.publish("annual", design);

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        assertEquals(List.of(SUBMITTER), state.getPendingApprovals().getFirst().getApprovedActors());
    }

    @Test
    void aStepThatResolvesToNobodyFailsTheSubmission() {
        DesignFlowDefinition design = approvalFlow("Supervisor approval", null, List.of(MANAGER));
        // No organization service here, so the supervisor source resolves to nobody (strategy Error).
        design.getGraph().getNodes().set(1, FlowGraphNode.builder()
                .id("approval").type(FlowNodeType.APPROVAL).label("Supervisor")
                .config(Map.of("approverSource", Map.of("type", "Supervisor", "level", 1)))
                .build());
        publishService.publish("supervisor", design);

        // Thrown from the start, so the submission's transaction rolls the record back.
        assertThrows(FlowRuntimeException.class, () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        verify(handler, never()).onOutcome(anyString(), any());
    }

    @Test
    void aCommentOnAFinishedInstanceIsNotWrittenBackAgain() {
        publishService.publish("annual", approvalFlow("Annual leave", null, List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        engine.approve(nodeRequest(new FlowApproveRequest(), started.getInstanceId(), MANAGER, null));

        FlowCommentRequest comment = new FlowCommentRequest();
        comment.setInstanceId(started.getInstanceId());
        comment.setActorId(SUBMITTER);
        comment.setComment("thanks");
        engine.addComment(comment);

        verify(handler, times(1)).onOutcome(anyString(), any());
    }

    private ApprovalOutcome capturedOutcome() {
        ArgumentCaptor<ApprovalOutcome> outcome = ArgumentCaptor.forClass(ApprovalOutcome.class);
        verify(handler, atLeastOnce()).onOutcome(eq(ROW), outcome.capture());
        return outcome.getValue();
    }

    private static <T extends AbstractFlowNodeRequest> T nodeRequest(T request, String instanceId, String actorId,
                                                                    String comment) {
        request.setInstanceId(instanceId);
        request.setNodeId("approval");
        request.setActorId(actorId);
        request.setComment(comment);
        return request;
    }

    private static DesignFlowDefinition approvalFlow(String name, String condition, List<String> approvers) {
        return flow(name, new SubmitTrigger(MODEL, condition), approvers);
    }

    /** start → approval → end, or start → end when {@code approvers} is null. */
    private static DesignFlowDefinition flow(String name, TriggerSource trigger, List<String> approvers) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(node("start", FlowNodeType.START));
        if (approvers == null) {
            edges.add(edge("e1", "start", "end"));
        } else {
            nodes.add(FlowGraphNode.builder()
                    .id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                    .config(Map.of("approvers", approvers, "returnEnabled", true))
                    .build());
            edges.add(edge("e1", "start", "approval"));
            edges.add(edge("e2", "approval", "end"));
        }
        nodes.add(node("end", FlowNodeType.END));
        boolean approval = trigger instanceof SubmitTrigger;
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(approval ? FlowScenario.APPROVAL : FlowScenario.AUTOMATION)
                .allowInitiatorWithdraw(approval)
                .trigger(trigger)
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
