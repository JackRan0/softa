package io.softa.starter.flow.runtime.spi.impl;

import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.Language;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.inbox.enums.NotificationType;
import io.softa.starter.message.service.MessageService;

/**
 * Default {@link ApprovalNotificationService}: tells the people an approval concerns through
 * message-starter's inbox, as {@link NotificationType#WORKFLOW} messages that point at the flow
 * instance ({@code sourceModel} = {@code FlowInstance}, {@code sourceId} = its instance id) — the
 * client opens the instance and goes on from there.
 *
 * <ul>
 *   <li>A step's approvers still to act, and whoever a task is transferred or delegated to:
 *       "Pending approval".</li>
 *   <li>Approvers urged or about to time out: "Reminder" / "Approval due soon".</li>
 *   <li>CC recipients: "For your information".</li>
 *   <li>The initiator, when the instance is approved, rejected (with the comment) or returned
 *       (with the comment).</li>
 * </ul>
 *
 * <p>Recipients are approver ids, i.e. login account ids. Texts are translated into the tenant's
 * default language — a message is read later by people whose own language the action does not
 * know. Messages are written after the action's transaction commits, in a transaction of their
 * own: an action that rolls back sends nothing, and a message that cannot be written is logged
 * without touching the action.
 */
@Slf4j
public class InboxApprovalNotificationService implements ApprovalNotificationService {

    private static final String TENANT_MODEL = "TenantInfo";
    private static final String TENANT_LANGUAGE_FIELD = "defaultLanguage";
    private static final String INSTANCE_MODEL = FlowInstance.class.getSimpleName();
    /** The inbox's title column width; a longer instance title is cut to fit. */
    private static final int TITLE_MAX_LENGTH = 200;
    /** The most messages message-starter takes in one batch. */
    private static final int BATCH_SIZE = 500;

    private final ObjectProvider<MessageService> messageService;
    private final ModelService<Long> modelService;
    private final TransactionTemplate newTransaction;

    @SuppressWarnings("unchecked")
    public InboxApprovalNotificationService(ObjectProvider<MessageService> messageService, ModelService<?> modelService,
                                            PlatformTransactionManager transactionManager) {
        this.messageService = messageService;
        this.modelService = (ModelService<Long>) modelService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void notify(FlowNotificationEvent event) {
        FlowExecutionState state = event.state();
        Notice notice = switch (event) {
            case FlowNotificationEvent.TaskAssigned e ->
                    new Notice(stillToAct(e.pendingApproval()), "Pending approval: {0}", null);
            case FlowNotificationEvent.TaskTransferred e ->
                    new Notice(List.of(e.newActorId()), "Pending approval: {0}", null);
            case FlowNotificationEvent.TaskDelegated e ->
                    new Notice(List.of(e.toActorId()), "Pending approval: {0}", null);
            case FlowNotificationEvent.Urged e ->
                    new Notice(e.pendingActorIds(), "Reminder to approve: {0}", e.message());
            case FlowNotificationEvent.TimeoutReminded e ->
                    new Notice(stillToAct(e.pendingApproval()), "Approval due soon: {0}", null);
            case FlowNotificationEvent.CcSent e ->
                    new Notice(e.recipientActorIds(), "For your information: {0}", e.message());
            case FlowNotificationEvent.FlowCompleted e -> e.approved()
                    ? new Notice(List.of(state.getInitiatorId()), "Approved: {0}", null)
                    : null;
            // Approving one step is no news to the initiator; a rejection ends the approval.
            case FlowNotificationEvent.TaskCompleted e -> e.approved()
                    ? null
                    : new Notice(List.of(state.getInitiatorId()), "Rejected: {0}", latestComment(state, ApprovalActionType.REJECT));
            case FlowNotificationEvent.Returned e ->
                    new Notice(List.of(state.getInitiatorId()), "Returned to you: {0}", e.comment());
        };
        if (notice == null) {
            return;
        }
        List<Long> recipients = notice.recipients() == null ? List.of() : notice.recipients().stream()
                .map(InboxApprovalNotificationService::toLong)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (recipients.isEmpty()) {
            log.debug("Flow notification not sent: instance={} has no recipient", state.getInstanceId());
            return;
        }
        String language = tenantLanguage();
        String title = fit(translate(language, notice.title(), subjectOf(state)), TITLE_MAX_LENGTH);
        String content = StringUtils.hasText(notice.content()) ? notice.content() : title;
        List<SendInboxDTO> inbox = recipients.stream().map(recipient -> {
            SendInboxDTO message = new SendInboxDTO();
            message.setRecipientId(recipient);
            message.setTitle(title);
            message.setContent(content);
            message.setNotificationType(NotificationType.WORKFLOW);
            message.setSourceModel(INSTANCE_MODEL);
            message.setSourceId(state.getInstanceId());
            return message;
        }).toList();
        sendAfterCommit(inbox, state.getInstanceId());
    }

    /**
     * Writes the messages once the action's transaction commits — at once when there is none. Not
     * inside it: a failed write there marks the action's transaction rollback-only, and the action
     * then fails on commit, whatever caught the exception.
     */
    private void sendAfterCommit(List<SendInboxDTO> inbox, String instanceId) {
        Context context = ContextHolder.cloneContext();
        Runnable send = () -> ContextHolder.runWith(context,
                () -> ContextUtils.withoutPermissionChecks(() -> write(inbox, instanceId)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void write(List<SendInboxDTO> inbox, String instanceId) {
        MessageService messages = messageService.getIfAvailable();
        if (messages == null) {
            log.debug("Flow notification not sent: instance={}, no inbox available", instanceId);
            return;
        }
        try {
            // A transaction of its own: after commit the committed transaction is still bound to the
            // thread, and a write joining it would never be committed.
            newTransaction.executeWithoutResult(status -> {
                for (int from = 0; from < inbox.size(); from += BATCH_SIZE) {
                    messages.sendInboxBatch(inbox.subList(from, Math.min(inbox.size(), from + BATCH_SIZE)));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Failed to send flow notification for instance {}: {}", instanceId, e.getMessage(), e);
        }
    }

    /** {@code text}, cut to {@code maxLength} characters with an ellipsis when longer. */
    private static String fit(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
    }

    /** What the instance is called in a message: its title, else its flow's name. */
    private static String subjectOf(FlowExecutionState state) {
        if (StringUtils.hasText(state.getTitle())) {
            return state.getTitle();
        }
        return StringUtils.hasText(state.getFlowName()) ? state.getFlowName() : state.getInstanceId();
    }

    /** The approvers of a step who have not acted yet. */
    private static List<String> stillToAct(PendingApproval step) {
        List<String> approvers = step.getApprovers() == null ? List.of() : step.getApprovers();
        return approvers.stream()
                .filter(approver -> step.getApprovedActors() == null || !step.getApprovedActors().contains(approver))
                .filter(approver -> step.getRejectedActors() == null || !step.getRejectedActors().contains(approver))
                .toList();
    }

    /** The comment of the latest {@code action} the current action recorded. */
    private static String latestComment(FlowExecutionState state, ApprovalActionType action) {
        List<ApprovalActionAuditEntry> delta = state.getApprovalAuditDelta();
        if (delta == null) {
            return null;
        }
        for (int i = delta.size() - 1; i >= 0; i--) {
            if (action.equals(delta.get(i).getAction())) {
                return delta.get(i).getComment();
            }
        }
        return null;
    }

    /** The tenant's default language, else the current context's, else the system default. */
    private String tenantLanguage() {
        Long tenantId = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        if (tenantId != null && ModelManager.existModel(TENANT_MODEL)) {
            Object code = ContextUtils.withoutPermissionChecks(() ->
                            modelService.getById(TENANT_MODEL, tenantId, List.of(TENANT_LANGUAGE_FIELD)))
                    .map(tenant -> tenant.get(TENANT_LANGUAGE_FIELD))
                    .orElse(null);
            if (code != null && Language.exists(code.toString())) {
                return code.toString();
            }
        }
        return ContextHolder.existContext() && ContextHolder.getContext().getLanguage() != null
                ? ContextHolder.getContext().getLanguage().getCode()
                : BaseConstant.DEFAULT_LANGUAGE.getCode();
    }

    private static String translate(String language, String text, Object... args) {
        Context context = ContextHolder.cloneContext();
        context.setLanguage(Language.of(language));
        return ContextHolder.callWith(context, () -> I18n.get(text, args));
    }

    private static Long toLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Who to tell, the title template (its {0} is the instance's subject), and an optional body. */
    private record Notice(List<String> recipients, String title, String content) {
    }
}
