package io.softa.starter.flow.service.support;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowParticipants;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Who may see a flow instance, a record's approval history, and the record an instance approves.
 *
 * <p>Two grants need no configuration: the initiator sees what they submitted, and the people an
 * instance gives a task to — its approvers, pending or done, CC recipients, and the targets of a
 * transfer, a delegation or an added signature — see the instances behind their tasks. Everyone else
 * goes through the standard role and data-scope configuration: they see an instance when their own
 * permissions let them read its {@code FlowInstance} row (the monitoring console's administrators
 * hold that through their roles) or, for a record's approval, read the record.
 *
 * <p>Reading the approved record past one's own row permissions is narrower: only the initiator and
 * the task holders have it ({@link #requireRecordGrantee}). Anyone else reads the record, if their
 * permissions allow, through the model's own API.
 *
 * <p>Every refusal is a {@link FlowAuthorizationException} (HTTP 403).
 */
@Component
public class FlowInstanceAccessGuard {

    private static final String FLOW_INSTANCE_MODEL = FlowInstance.class.getSimpleName();

    private static final String INSTANCE_ID_FIELD = LambdaUtils.getAttributeName(FlowInstance::getInstanceId);

    private final FlowInstanceService instanceService;

    private final ApprovalAuditReader auditReader;

    private final ModelService<Serializable> modelService;

    @SuppressWarnings("unchecked")
    public FlowInstanceAccessGuard(FlowInstanceService instanceService, ApprovalAuditReader auditReader,
                                   ModelService<?> modelService) {
        this.instanceService = instanceService;
        this.auditReader = auditReader;
        this.modelService = (ModelService<Serializable>) modelService;
    }

    /**
     * Authorizes a caller to view a business record's approval history: they took part in one of
     * its approvals — submitted it, wait to approve it, acted on it or were its target — or they can
     * read the record with their own permissions, as anyone who sees a record also sees its history.
     *
     * @param modelName   the record's model
     * @param rowId       the record
     * @param history     the record's approval history, already loaded
     * @param requesterId the authenticated caller's user id
     * @throws FlowAuthorizationException if neither holds
     */
    public void requireRecordViewer(String modelName, String rowId, FlowRecordApprovalsView history,
                                    String requesterId) {
        requireAuthenticated(requesterId, "view the approvals of " + modelName + " " + rowId);
        if (participatesIn(history, requesterId) || canReadRecord(modelName, rowId)) {
            return;
        }
        throw new FlowAuthorizationException("User may not view the approvals of " + modelName + " " + rowId);
    }

    /**
     * Authorizes a caller to preview the approval a business record's submission would start: they
     * can read the record with their own permissions. Taking part in the record's earlier approvals
     * is not enough — the preview is about its next one.
     *
     * @param modelName   the record's model
     * @param rowId       the record
     * @param requesterId the authenticated caller's user id
     * @throws FlowAuthorizationException if they cannot read the record
     */
    public void requireRecordReader(String modelName, String rowId, String requesterId) {
        requireAuthenticated(requesterId, "preview the approval of " + modelName + " " + rowId);
        if (canReadRecord(modelName, rowId)) {
            return;
        }
        throw new FlowAuthorizationException("User may not preview the approval of " + modelName + " " + rowId);
    }

    /**
     * Authorizes a caller to view an instance's cross-actor approval data — its task list or its
     * approval records.
     *
     * @param instanceId          the runtime instance id being queried
     * @param requesterId         the authenticated caller's user id
     * @param participantInResult whether the caller already appears as an actor in the loaded rows
     * @throws FlowAuthorizationException if the caller is neither a participant, nor the initiator,
     *                                    nor permitted by their roles
     */
    public void requireInstanceViewer(String instanceId, String requesterId, boolean participantInResult) {
        requireAuthenticated(requesterId, "view instance " + instanceId);
        if (participantInResult) {
            return;
        }
        Optional<FlowInstance> instance = ContextUtils.withoutPermissionChecks(
                () -> instanceService.findByInstanceId(instanceId));
        if (instance.isPresent() && (requesterId.equals(instance.get().getInitiatorId())
                || permittedByRoles(instanceId, instance.get().getRecordApproval(),
                        instance.get().getModelName(), instance.get().getRowId()))) {
            return;
        }
        throw new FlowAuthorizationException("User may not view flow instance " + instanceId);
    }

    /**
     * Authorizes a caller against the full persisted runtime state before detail endpoints expose it.
     */
    public void requireInstanceViewer(FlowExecutionState state, String requesterId) {
        String instanceId = state == null ? null : state.getInstanceId();
        requireAuthenticated(requesterId, "view instance " + instanceId);
        if (state != null && (isTaskHolder(state, requesterId)
                || permittedByRoles(instanceId, state.getRecordApproval(), state.getModelName(), state.getRowId()))) {
            return;
        }
        throw new FlowAuthorizationException("User may not view flow instance " + instanceId);
    }

    /**
     * Authorizes a caller to read the record an instance approves past their own row permissions:
     * the initiator and the people the instance gives a task to. Only a record's approval grants it —
     * its record was submitted, checked with the submitter's own permissions; any other start names
     * its record freely, and must not become a way to read past row permissions.
     */
    public void requireRecordGrantee(FlowExecutionState state, String requesterId) {
        String instanceId = state == null ? null : state.getInstanceId();
        requireAuthenticated(requesterId, "read the record of instance " + instanceId);
        if (state != null && !state.isApprovalOfRecord()) {
            throw new FlowAuthorizationException("Flow instance " + instanceId
                    + " is not a record's approval; its record is read through the record's own permissions");
        }
        if (state != null && isTaskHolder(state, requesterId)) {
            return;
        }
        throw new FlowAuthorizationException("Only the initiator and the people flow instance " + instanceId
                + " gives a task to may read its record through it");
    }

    private static void requireAuthenticated(String requesterId, String action) {
        if (!StringUtils.hasText(requesterId)) {
            throw new FlowAuthorizationException("Authentication is required to " + action);
        }
    }

    /**
     * Authorizes an administrator's action on an instance — what the monitoring console does to it: the
     * caller's own permissions let them read its {@code FlowInstance} row, with the data scope of their
     * roles. Taking part in the instance is not enough; the console's actions are not a participant's.
     *
     * @throws FlowAuthorizationException if their permissions do not reach the instance
     */
    public void requireInstanceAdministrator(String instanceId, String requesterId) {
        requireAuthenticated(requesterId, "administer instance " + instanceId);
        if (canReadInstance(instanceId)) {
            return;
        }
        throw new FlowAuthorizationException("User may not administer flow instance " + instanceId);
    }

    /** The default grants: the initiator, and whoever the instance gives or gave a task to. */
    private boolean isTaskHolder(FlowExecutionState state, String requesterId) {
        return FlowParticipants.isParticipant(state, auditReader.fullHistory(state), requesterId);
    }

    /**
     * The standard permissions: the caller's roles let them read the instance's row, or — for a
     * record's approval — the record itself.
     */
    private boolean permittedByRoles(String instanceId, Boolean recordApproval, String modelName, String rowId) {
        return canReadInstance(instanceId)
                || (Boolean.TRUE.equals(recordApproval) && canReadRecord(modelName, rowId));
    }

    /**
     * Of these instances, the ones the caller may administer: those whose FlowInstance row their own permissions
     * read — the monitoring console's data scope. One query however many.
     */
    public Set<String> administrableInstanceIds(Collection<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return Set.of();
        }
        FlexQuery query = new FlexQuery(new Filters().in(FlowInstance::getInstanceId, List.copyOf(instanceIds)));
        query.setFields(List.of(INSTANCE_ID_FIELD));
        try {
            return modelService.searchList(FLOW_INSTANCE_MODEL, query).stream()
                    .map(row -> Objects.toString(row.get(INSTANCE_ID_FIELD), null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (PermissionException e) {
            return Set.of();
        }
    }

    private boolean canReadInstance(String instanceId) {
        if (!StringUtils.hasText(instanceId)) {
            return false;
        }
        try {
            return modelService.count(FLOW_INSTANCE_MODEL, new Filters().eq(FlowInstance::getInstanceId, instanceId)) > 0;
        } catch (PermissionException e) {
            return false;
        }
    }

    /** Whether the caller can read the record under their own row scope. */
    private boolean canReadRecord(String modelName, String rowId) {
        if (!StringUtils.hasText(modelName) || !StringUtils.hasText(rowId) || !ModelManager.existModel(modelName)) {
            return false;
        }
        try {
            return modelService.getById(modelName, IdUtils.formatId(modelName, rowId), List.of(ModelConstant.ID))
                    .isPresent();
        } catch (PermissionException e) {
            // No access to the model at all.
            return false;
        }
    }

    private static boolean participatesIn(FlowRecordApprovalsView history, String requesterId) {
        if (history == null || history.getInstances() == null) {
            return false;
        }
        for (FlowRecordApprovalsView.Instance instance : history.getInstances()) {
            if (isActor(instance.getInitiator(), requesterId)
                    || (instance.getPending() != null && instance.getPending().stream()
                            .anyMatch(step -> step.getApprovers() != null
                                    && step.getApprovers().stream().anyMatch(a -> isActor(a, requesterId))))
                    || (instance.getActions() != null && instance.getActions().stream()
                            .anyMatch(a -> isActor(a.getActor(), requesterId) || isActor(a.getTarget(), requesterId)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isActor(FlowRecordApprovalsView.Actor actor, String requesterId) {
        return actor != null && requesterId.equals(actor.getId());
    }
}
