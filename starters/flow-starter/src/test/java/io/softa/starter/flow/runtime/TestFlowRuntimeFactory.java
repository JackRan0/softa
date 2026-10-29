package io.softa.starter.flow.runtime;

import java.util.List;
import org.mockito.Mockito;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.action.*;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.impl.DefaultApprovalNotificationService;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;
import io.softa.starter.flow.service.FlowCcConfigService;

/**
 * Test helper to build a fully wired {@link FlowRuntimeFacade} without Spring context.
 */
public final class TestFlowRuntimeFactory {

    private TestFlowRuntimeFactory() {
    }

    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, state -> {}, state -> {});
    }

    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSyncListener, state -> {});
    }

    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener,
                                    FlowStateChangeListener recordSyncListener) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSyncListener,
                recordSyncListener, Mockito.mock(ModelService.class));
    }

    /** As above, with the model service an approval's form edits are written to the record through. */
    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener,
                                    FlowStateChangeListener recordSyncListener,
                                    ModelService<?> recordModelService) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSyncListener,
                recordSyncListener, recordModelService, new DefaultApprovalNotificationService());
    }

    /** With the notification service the engine reports to, for tests that watch what it announces. */
    public static FlowRuntimeFacade createWithNotifications(FlowBundleRegistry bundleRegistry,
                                                     FlowInstanceStore instanceStore,
                                                     DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                                     ApprovalNodeExecutionHandler approvalHandler,
                                                     ApprovalNotificationService notificationService) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, state -> {}, state -> {},
                Mockito.mock(ModelService.class), notificationService, Mockito.mock(FlowCcConfigService.class));
    }

    /** With the CC rules the engine applies, and the notification service it reports to. */
    public static FlowRuntimeFacade createWithCcRules(FlowBundleRegistry bundleRegistry,
                                               FlowInstanceStore instanceStore,
                                               DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                               ApprovalNodeExecutionHandler approvalHandler,
                                               FlowCcConfigService ccConfigService,
                                               ApprovalNotificationService notificationService) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, state -> {}, state -> {},
                Mockito.mock(ModelService.class), notificationService, ccConfigService);
    }

    private static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener,
                                    FlowStateChangeListener recordSyncListener,
                                    ModelService<?> recordModelService,
                                    ApprovalNotificationService notificationService) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSyncListener,
                recordSyncListener, recordModelService, notificationService, Mockito.mock(FlowCcConfigService.class));
    }

    private static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener,
                                    FlowStateChangeListener recordSyncListener,
                                    ModelService<?> recordModelService,
                                    ApprovalNotificationService notificationService,
                                    FlowCcConfigService ccConfigService) {
        FlowProjectionPublisher projectionPublisher =
                new FlowProjectionPublisher(List.of(taskSyncListener, recordSyncListener));

        ApprovalAuditReader auditReader = new ApprovalAuditReader(new NoopApprovalActionLedger());
        FlowAuditService auditService = new FlowAuditService(auditReader);
        ApproverResolutionService approverResolutionService = new ApproverResolutionService();
        PendingApprovalFactory pendingApprovalFactory = new PendingApprovalFactory(approverResolutionService, auditReader);
        ApprovalLifecycleService lifecycleService = new ApprovalLifecycleService(pendingApprovalFactory);
        ApprovalActorValidator actorValidator = new ApprovalActorValidator(lifecycleService, auditReader);
        FlowActionContextService contextService = new FlowActionContextService(instanceStore, bundleRegistry, projectionPublisher);

        FlowGatewayRouter gatewayRouter = new FlowGatewayRouter();
        NodeRetryExecutor retryExecutor = new NodeRetryExecutor(auditService);
        ApproverDedupService approverDedupService = new ApproverDedupService(lifecycleService, auditService, auditReader);
        FlowActionReplayGuard replayGuard = new FlowActionReplayGuard(auditReader);
        FlowExecutionOrchestrator orchestrator = new FlowExecutionOrchestrator(
                bundleRegistry, nodeHandlerRegistry, contextService, auditService, pendingApprovalFactory, notificationService,
                gatewayRouter, retryExecutor, approverDedupService);

        AutoCcService autoCcService = new AutoCcService(ccConfigService, contextService, auditService, auditReader,
                notificationService);

        return new FlowRuntimeFacade(contextService, orchestrator, instanceStore, autoCcService,
                new ApproveActionHandler(contextService, actorValidator, lifecycleService, auditService, orchestrator, notificationService,
                        new ApprovalFormWriteService(recordModelService, ModelApprovalHandlerRegistry.of(List.of())), replayGuard),
                new RejectActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService, replayGuard),
                new TransferActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService),
                new DelegateActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService),
                new AddSignBeforeActionHandler(contextService, actorValidator, lifecycleService, auditService),
                new AddSignAfterActionHandler(contextService, actorValidator, lifecycleService, auditService),
                new CcActionHandler(contextService, actorValidator, auditService, notificationService),
                new BatchCcActionHandler(contextService, actorValidator, auditService),
                new ReadCcActionHandler(contextService, auditService),
                new ReturnApprovalActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService),
                new WithdrawActionHandler(contextService, auditService, new WithdrawPolicyGuard(bundleRegistry, auditReader)),
                new UrgeActionHandler(contextService, auditService, notificationService,
                        new UrgeThrottle(auditReader, 240)),
                new CommentActionHandler(contextService, auditService, auditReader),
                new TerminateActionHandler(contextService, auditService, notificationService),
                new ReassignActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService));
    }
}
