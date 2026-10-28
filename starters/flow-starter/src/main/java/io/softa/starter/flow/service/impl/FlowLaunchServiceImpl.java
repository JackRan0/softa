package io.softa.starter.flow.service.impl;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowDebugHistory;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.ApprovalOutcomeType;
import io.softa.starter.flow.runtime.engine.FlowLaunchResponse;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.api.PublishAndStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalPathPreviewer;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.spi.ApprovalOutcome;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.service.FlowDebugHistoryService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Default launch facade built on top of publish and runtime services.
 */
@Slf4j
@Service
public class FlowLaunchServiceImpl implements FlowLaunchService {

    // A submission is refused with these, and its preview reports them.
    private static final String ALREADY_IN_APPROVAL = "This record is already in approval.";
    private static final String NO_SUBMIT_FLOW = "No approval flow is configured for this {0} record. "
            + "Ask your administrator to set one up.";
    private static final String SEVERAL_SUBMIT_FLOWS = "Several approval flows apply to this {0} record ({1}). Ask "
            + "your administrator to narrow their submit conditions so that exactly one applies.";

    private final FlowPublishService flowPublishService;
    private final FlowRuntimeEngine flowRuntimeEngine;
    private final FlowBundleRegistry flowBundleRegistry;
    private final DefaultFlowCompiler flowCompiler;
    private final ModelApprovalHandlerRegistry handlerRegistry;
    private final DefaultFlowTriggerRegistry triggerRegistry;
    private final FlowInstanceService instanceService;
    private final ModelService<Serializable> modelService;
    private final ApprovalPathPreviewer pathPreviewer;
    private final FlowInstanceAccessGuard accessGuard;
    private final FlowUserNames userNames;

    @Autowired(required = false)
    private FlowDebugHistoryService debugHistoryService;

    @Autowired(required = false)
    private FlowDesignService designService;

    @SuppressWarnings("unchecked")
    public FlowLaunchServiceImpl(FlowPublishService flowPublishService,
                                 FlowRuntimeEngine flowRuntimeEngine,
                                 FlowBundleRegistry flowBundleRegistry,
                                 DefaultFlowCompiler flowCompiler,
                                 ModelApprovalHandlerRegistry handlerRegistry,
                                 DefaultFlowTriggerRegistry triggerRegistry,
                                 FlowInstanceService instanceService,
                                 ModelService<?> modelService,
                                 ApprovalPathPreviewer pathPreviewer,
                                 FlowInstanceAccessGuard accessGuard,
                                 FlowUserNames userNames) {
        this.flowPublishService = flowPublishService;
        this.flowRuntimeEngine = flowRuntimeEngine;
        this.flowBundleRegistry = flowBundleRegistry;
        this.flowCompiler = flowCompiler;
        this.handlerRegistry = handlerRegistry;
        this.triggerRegistry = triggerRegistry;
        this.instanceService = instanceService;
        this.modelService = (ModelService<Serializable>) modelService;
        this.pathPreviewer = pathPreviewer;
        this.accessGuard = accessGuard;
        this.userNames = userNames;
    }

    @Override
    public FlowLaunchResponse publishAndStart(PublishAndStartRequest request) {
        if (request == null || request.getDefinition() == null) {
            throw new FlowCompileException("definition is required to publish and start a flow");
        }
        CompiledFlowDefinition published = flowPublishService.publish(request.getFlowId(), request.getDefinition());
        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setBundleId(published.getBundleId());
        startRequest.setInitiatorId(request.getInitiatorId());
        startRequest.setVariables(request.getVariables());
        FlowExecutionState state = flowRuntimeEngine.startAutomation(startRequest);
        return FlowLaunchResponse.builder()
                .bundle(published)
                .state(state)
                .build();
    }

    @Override
    public FlowLaunchResponse debugRunDraft(String flowId, FlowStartRequest request) {
        if (designService == null) {
            throw new FlowCompileException("FlowDesignService is not available; cannot load draft for a debug run");
        }
        DesignFlowDefinition definition = designService.getById(flowId)
                .map(FlowDesign::getDesignJson)
                .orElseThrow(() -> new FlowCompileException("FlowDesign not found: " + flowId));

        // compile errors carry anchored diagnostics — same surface as /validate
        CompiledFlowDefinition debugBundle = flowBundleRegistry.registerDebug(
                flowCompiler.compile(definition), definition, flowId);

        LocalDateTime startTime = LocalDateTime.now();
        FlowStartRequest startRequest = request != null ? request : new FlowStartRequest();
        startRequest.setFlowId(flowId);
        startRequest.setBundleId(debugBundle.getBundleId());
        FlowExecutionState state = flowRuntimeEngine.start(startRequest);

        recordDebugHistory(state, startRequest, startTime);

        return FlowLaunchResponse.builder()
                .bundle(debugBundle)
                .state(state)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlowExecutionState submitRecord(String modelName, String rowId, String initiatorId) {
        requireRecordKey(modelName, rowId, "submit");
        ModelApprovalHandler handler = handlerRegistry.require(modelName);
        // The handler's checks run first, with the submitter's own permissions: nothing moves for a
        // record they may not submit.
        ApprovalSubmission submission = handler.prepare(rowId);
        if (inProgress(latestApproval(modelName, rowId))) {
            throw new BusinessException(ALREADY_IN_APPROVAL);
        }
        // Every submission starts a new approval from the beginning — after a return too — with the
        // flow chosen again for the record as it is now.
        CompiledFlowDefinition definition = chooseSubmitFlow(modelName, readRecord(modelName, rowId));
        // Before the start: a flow without an approval step completes while it starts, and its
        // result must land on a record that is already in approval.
        ContextUtils.withoutPermissionChecks(() -> handler.onSubmitted(rowId));
        // Started as the record's approval: its result is written back, and the unique open-record
        // key refuses a second approval of the record, a concurrent submission included.
        return flowRuntimeEngine.startRecordApproval(
                submitStartRequest(definition, modelName, rowId, initiatorId, submission));
    }

    /**
     * No transaction of its own: a refusal the handler throws through a transactional method marks
     * the transaction it joins rollback-only, and the preview reports the refusal rather than failing.
     */
    @Override
    public FlowApprovalPreview previewRecord(String modelName, String rowId, String requesterId) {
        requireRecordKey(modelName, rowId, "preview");
        // The caller's own permissions decide, before anything is read past them.
        accessGuard.requireRecordReader(modelName, rowId, requesterId);
        FlowApprovalPreview preview = previewSubmission(modelName, rowId, requesterId);
        userNames.name(actorsOf(preview));
        return preview;
    }

    /** {@link #submitRecord} up to the start of the approval, whose path is worked out instead. */
    private FlowApprovalPreview previewSubmission(String modelName, String rowId, String submitterId) {
        ApprovalSubmission submission;
        try {
            submission = handlerRegistry.require(modelName).prepare(rowId);
        } catch (BusinessException refusal) {
            return notReady(FlowApprovalPreview.Status.REFUSED, refusal.getMessage());
        }
        if (inProgress(latestApproval(modelName, rowId))) {
            return notReady(FlowApprovalPreview.Status.REFUSED, I18n.get(ALREADY_IN_APPROVAL));
        }
        List<CompiledFlowDefinition> flows = triggerRegistry.findSubmitFlows(modelName, readRecord(modelName, rowId));
        if (flows.isEmpty()) {
            return notReady(FlowApprovalPreview.Status.NO_FLOW, I18n.get(NO_SUBMIT_FLOW, modelName));
        }
        if (flows.size() > 1) {
            FlowApprovalPreview preview = notReady(FlowApprovalPreview.Status.SEVERAL_FLOWS,
                    I18n.get(SEVERAL_SUBMIT_FLOWS, modelName, flowNames(flows)));
            preview.setCandidates(flows.stream()
                    .map(flow -> new FlowApprovalPreview.Flow(flow.getFlowId(), flow.getFlowName()))
                    .toList());
            return preview;
        }
        CompiledFlowDefinition definition = flows.getFirst();
        return pathPreviewer.preview(definition,
                submitStartRequest(definition, modelName, rowId, submitterId, submission));
    }

    private static FlowApprovalPreview notReady(FlowApprovalPreview.Status status, String message) {
        return FlowApprovalPreview.builder().status(status).message(message).build();
    }

    /** Every user the preview mentions: its steps' approvers and those counted as having approved. */
    private static List<FlowRecordApprovalsView.Actor> actorsOf(FlowApprovalPreview preview) {
        List<FlowRecordApprovalsView.Actor> actors = new ArrayList<>();
        if (preview.getSteps() != null) {
            for (FlowApprovalPreview.Step step : preview.getSteps()) {
                actors.addAll(step.getApprovers());
                step.getAutoApprovals().forEach(approval -> actors.add(approval.getActor()));
            }
        }
        return actors;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlowExecutionState withdrawRecord(String modelName, String rowId, String actorId, String comment) {
        requireRecordKey(modelName, rowId, "withdraw");
        ModelApprovalHandler handler = handlerRegistry.require(modelName);
        FlowInstance latest = latestApproval(modelName, rowId);
        if (latest != null && FlowExecutionStatus.FAILED.equals(latest.getStatus())) {
            return takeBackFailed(handler, latest, rowId, actorId, comment);
        }
        if (latest == null || latest.getStatus() == null || latest.getStatus().isTerminal()) {
            throw new BusinessException("This record has no approval in progress to withdraw.");
        }
        FlowWithdrawRequest request = new FlowWithdrawRequest();
        request.setInstanceId(latest.getInstanceId());
        request.setActorId(actorId);
        request.setComment(comment);
        // The outcome listener writes "withdrawn" back to the record in this transaction.
        return flowRuntimeEngine.withdraw(request);
    }

    private static void requireRecordKey(String modelName, String rowId, String action) {
        if (!StringUtils.hasText(modelName) || !StringUtils.hasText(rowId)) {
            throw new FlowActionValidationException("modelName and rowId are required to " + action + " a record");
        }
    }

    /**
     * The record's latest approval — the instance its newest submission started — or null when it was
     * never submitted. Other instances may run on the same record (automation, debug runs) and do not
     * count.
     */
    private FlowInstance latestApproval(String modelName, String rowId) {
        return ContextUtils.withoutPermissionChecks(() -> instanceService.findByModelNameAndRowId(modelName, rowId))
                .stream()
                .filter(instance -> Boolean.TRUE.equals(instance.getRecordApproval()))
                .max(Comparator.comparing(FlowInstance::getCreatedTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** Whether {@code approval} — a record's latest — is still in progress. */
    private static boolean inProgress(FlowInstance approval) {
        return approval != null && approval.getStatus() != null && !approval.getStatus().isTerminal();
    }

    /**
     * An approval that failed leaves its record in approval with nobody to act on it, so its submitter
     * takes the record back — the record's handler is told it was withdrawn. The failed instance stays
     * as it is, for flow monitoring.
     */
    private FlowExecutionState takeBackFailed(ModelApprovalHandler handler, FlowInstance failed, String rowId,
                                             String actorId, String comment) {
        if (!Objects.equals(actorId, failed.getInitiatorId())) {
            throw new FlowAuthorizationException("Only the submitter can withdraw the record after its approval failed: "
                    + failed.getModelName() + " " + rowId);
        }
        ContextUtils.withoutPermissionChecks(() -> handler.onOutcome(rowId,
                new ApprovalOutcome(ApprovalOutcomeType.WITHDRAWN, failed.getInstanceId(), actorId, comment)));
        return ContextUtils.withoutPermissionChecks(() -> flowRuntimeEngine.getInstance(failed.getInstanceId()))
                .orElseThrow(() -> new FlowRuntimeException("Flow instance not found: " + failed.getInstanceId()));
    }

    /** The whole record, read past the submitter's field and row checks: submit conditions see every field. */
    private Map<String, Object> readRecord(String modelName, String rowId) {
        Serializable id = IdUtils.formatId(modelName, rowId);
        return ContextUtils.withoutPermissionChecks(() -> modelService.getById(modelName, id))
                .orElseThrow(() -> new BusinessException("The {0} record {1} does not exist.", modelName, rowId));
    }

    private CompiledFlowDefinition chooseSubmitFlow(String modelName, Map<String, Object> record) {
        List<CompiledFlowDefinition> flows = triggerRegistry.findSubmitFlows(modelName, record);
        if (flows.isEmpty()) {
            throw new BusinessException(NO_SUBMIT_FLOW, modelName);
        }
        if (flows.size() > 1) {
            throw new BusinessException(SEVERAL_SUBMIT_FLOWS, modelName, flowNames(flows));
        }
        return flows.getFirst();
    }

    private static String flowNames(List<CompiledFlowDefinition> flows) {
        return flows.stream().map(CompiledFlowDefinition::getFlowName).collect(Collectors.joining(", "));
    }

    private static FlowStartRequest submitStartRequest(CompiledFlowDefinition definition, String modelName,
                                                       String rowId, String initiatorId,
                                                       ApprovalSubmission submission) {
        Map<String, Object> variables = new LinkedHashMap<>(submission.variables());
        // The trigger metadata every triggered start carries (see FlowAutomationService).
        variables.put("_triggerType", FlowAutomationService.SUBMIT_TRIGGER_TYPE);
        variables.put("_sourceModel", modelName);
        variables.put("_sourceRowId", rowId);
        FlowStartRequest request = new FlowStartRequest();
        request.setBundleId(definition.getBundleId());
        request.setInitiatorId(initiatorId);
        request.setTitle(submission.title());
        request.setModelName(modelName);
        request.setRowId(rowId);
        request.setVariables(variables);
        return request;
    }

    private void recordDebugHistory(FlowExecutionState state, FlowStartRequest request, LocalDateTime startTime) {
        if (debugHistoryService == null) {
            return;
        }
        try {
            LocalDateTime endTime = LocalDateTime.now();
            FlowDebugHistory history = new FlowDebugHistory();
            history.setFlowId(state.getFlowId());
            history.setFlowRevision(state.getFlowRevision());
            history.setInstanceId(state.getInstanceId());
            history.setStatus(state.getStatus());
            history.setInitiatorId(state.getInitiatorId());
            history.setStartTime(startTime);
            history.setEndTime(endTime);
            history.setDurationMs(Duration.between(startTime, endTime).toMillis());
            history.setEventMessage(JsonUtils.objectToString(request.getVariables()));
            history.setNodeTrace(JsonUtils.objectToString(state.getTrace()));
            history.setFinalVariables(JsonUtils.objectToString(state.getVariables()));
            history.setErrorMessage(state.getErrorMessage());
            debugHistoryService.recordHistory(history);
        } catch (Exception e) {
            log.error("Failed to record debug history: {}", e.getMessage(), e);
        }
    }
}
