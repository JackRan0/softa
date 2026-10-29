package io.softa.starter.flow.runtime.engine;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.enums.ApprovalStatus;

/**
 * Freezes a record while it is in approval and once it is approved, and keeps its approval fields the
 * framework's — for every model with a {@code ModelApprovalHandler}, and for the records it owns: those
 * of a model whose to-one field onto it deletes with it ({@code onDelete = CASCADE}), like an expense
 * claim's lines.
 *
 * <p>Held here, where every write lane meets — the record's form, the generic batch endpoints, a line
 * written on its own, an import, a flow's write node:
 * <ul>
 *   <li>A record in approval waits for its result or a withdrawal, so an approver never decides on
 *       something other than what they read; an approved one is settled for everyone. Neither is changed
 *       or deleted, and nothing it owns is filed, changed or deleted.</li>
 *   <li>Its approval fields ({@link ApprovalModel#APPROVAL_FIELDS}) are the framework's alone: a new
 *       record is a draft, never submitted, and nobody edits them. Sending back the stored value is not
 *       an edit.</li>
 * </ul>
 * Writes the system makes with permission checks off are exempt: the approval's own status writes, the
 * fields an approval step lets its approver edit, the handler's follow-up actions. This validator says
 * only when nobody may change a record; who may change it otherwise — its owner, in which of its other
 * states — is the model's own validator's to say.
 *
 * <p>Owned records are found from one link, a cascading to-one field straight onto the approved model.
 */
@Component
@Order(10)
public class ApprovalLockValidator implements ModelWriteValidator {

    private static final String IN_APPROVAL = "This record is in approval and cannot be changed. Withdraw it first to edit it.";
    private static final String APPROVED = "This record is approved and cannot be changed.";
    private static final String OWNER_IN_APPROVAL = "The record this belongs to is in approval and cannot be changed. "
            + "Withdraw it first to edit it.";
    private static final String OWNER_APPROVED = "The record this belongs to is approved and cannot be changed.";
    private static final String SET_BY_APPROVAL = "This field is set by the approval and cannot be edited.";

    private final ModelApprovalHandlerRegistry handlerRegistry;
    private final ModelService<Serializable> modelService;

    /** Model → its cascading to-one fields onto approved models; empty for a model that owns nothing. */
    private final Map<String, List<MetaField>> ownerLinksByModel = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public ApprovalLockValidator(ModelApprovalHandlerRegistry handlerRegistry, ModelService<?> modelService) {
        this.handlerRegistry = handlerRegistry;
        this.modelService = (ModelService<Serializable>) modelService;
    }

    @Override
    public boolean supports(String modelName) {
        return isApproved(modelName) || !ownerLinks(modelName).isEmpty();
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        if (systemWrite()) {
            return;
        }
        if (isApproved(ctx.modelName())) {
            ApprovalStatus status = ApprovalStatus.from(ctx.row().get(ApprovalModel.APPROVAL_STATUS));
            if (status != null && status != ApprovalStatus.DRAFT) {
                ctx.reject(ApprovalModel.APPROVAL_STATUS, SET_BY_APPROVAL);
            }
            for (String field : List.of(ApprovalModel.SUBMITTER_ID, ApprovalModel.SUBMITTED_TIME)) {
                if (ctx.row().get(field) != null) {
                    ctx.reject(field, SET_BY_APPROVAL);
                }
            }
        }
        for (MetaField link : ownerLinks(ctx.modelName())) {
            requireOwnerEditable(ctx, link, ctx.row().get(link.getFieldName()));
        }
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (systemWrite()) {
            return;
        }
        Map<String, Object> original = ctx.originalRow() == null ? Map.of() : ctx.originalRow();
        if (isApproved(ctx.modelName())) {
            ApprovalStatus status = ApprovalStatus.from(original.get(ApprovalModel.APPROVAL_STATUS));
            if (status != null && status.isLocked()) {
                ctx.fail(status == ApprovalStatus.PENDING ? IN_APPROVAL : APPROVED);
            }
            for (String field : ApprovalModel.APPROVAL_FIELDS) {
                if (changes(ctx, original, field)) {
                    ctx.reject(field, SET_BY_APPROVAL);
                }
            }
        }
        for (MetaField link : ownerLinks(ctx.modelName())) {
            String field = link.getFieldName();
            requireOwnerEditable(ctx, link, original.get(field));
            // Moved under another record: that one must take it too.
            if (ctx.patch().containsKey(field) && !Objects.equals(idText(ctx.patch().get(field)), idText(original.get(field)))) {
                requireOwnerEditable(ctx, link, ctx.patch().get(field));
            }
        }
    }

    @Override
    public void validateDelete(String modelName, List<? extends Serializable> ids) {
        if (systemWrite() || ids.isEmpty()) {
            return;
        }
        if (isApproved(modelName)) {
            for (ApprovalStatus status : statuses(modelName, ids).values()) {
                if (status.isLocked()) {
                    throw new WriteValidationException(status == ApprovalStatus.PENDING
                            ? "A record in approval cannot be deleted. Withdraw it first."
                            : "An approved record cannot be deleted.");
                }
            }
        }
        List<MetaField> links = ownerLinks(modelName);
        if (links.isEmpty()) {
            return;
        }
        List<String> fields = new ArrayList<>(List.of(ModelConstant.ID));
        links.forEach(link -> fields.add(link.getFieldName()));
        FlexQuery query = new FlexQuery(fields, new Filters().in(ModelConstant.ID, List.copyOf(ids)));
        query.setConvertType(ConvertType.TYPE_CAST);
        List<Map<String, Object>> rows = ContextUtils.withoutPermissionChecks(() -> modelService.searchList(modelName, query));
        for (MetaField link : links) {
            List<Object> ownerIds = rows.stream().map(row -> row.get(link.getFieldName())).filter(Objects::nonNull).toList();
            for (ApprovalStatus status : statuses(link.getRelatedModel(), ownerIds).values()) {
                if (status.isLocked()) {
                    throw new WriteValidationException(status == ApprovalStatus.PENDING ? OWNER_IN_APPROVAL : OWNER_APPROVED);
                }
            }
        }
    }

    private boolean isApproved(String modelName) {
        return handlerRegistry.find(modelName).isPresent();
    }

    /**
     * The model's cascading to-one fields onto approved models: the records whose state decides whether its
     * own may change. Worked out once per model, on its first write — when metadata is loaded.
     */
    private List<MetaField> ownerLinks(String modelName) {
        return ownerLinksByModel.computeIfAbsent(modelName, name -> ModelManager.getModelFields(name).stream()
                .filter(field -> FieldType.TO_ONE_TYPES.contains(field.getFieldType()))
                .filter(field -> OnDelete.CASCADE.equals(field.getOnDelete()))
                .filter(field -> isApproved(field.getRelatedModel()))
                .toList());
    }

    /** The owner a row of the write names must be editable. A missing owner is the required check's to report. */
    private void requireOwnerEditable(WriteContext ctx, MetaField link, @Nullable Object ownerValue) {
        String ownerId = idText(ownerValue);
        if (ownerId == null) {
            return;
        }
        String ownerModel = link.getRelatedModel();
        // Every row of one save names the same owner, usually: read it once per write.
        ApprovalStatus status = (ApprovalStatus) ctx.scratch().computeIfAbsent(ownerModel + ":" + ownerId,
                key -> statuses(ownerModel, List.of(ownerValue)).get(ownerId));
        if (status != null && status.isLocked()) {
            ctx.fail(status == ApprovalStatus.PENDING ? OWNER_IN_APPROVAL : OWNER_APPROVED);
        }
    }

    /**
     * The approval status of the records with these ids, by id as text — read past the caller's data scope:
     * the rule is about the record's state, not about what the caller may list.
     */
    private Map<String, ApprovalStatus> statuses(String modelName, Collection<?> ids) {
        List<Serializable> keys = ids.stream().map(ApprovalLockValidator::idText).filter(Objects::nonNull)
                .distinct().<Serializable>map(id -> IdUtils.formatId(modelName, id)).toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, ApprovalModel.APPROVAL_STATUS),
                new Filters().in(ModelConstant.ID, keys));
        query.setConvertType(ConvertType.TYPE_CAST);
        Map<String, ApprovalStatus> statuses = new HashMap<>();
        for (Map<String, Object> row : ContextUtils.withoutPermissionChecks(() -> modelService.searchList(modelName, query))) {
            ApprovalStatus status = ApprovalStatus.from(row.get(ApprovalModel.APPROVAL_STATUS));
            if (status != null) {
                statuses.put(idText(row.get(ModelConstant.ID)), status);
            }
        }
        return statuses;
    }

    /** Whether the write names a new value for an approval field; sending back the stored one is not a change. */
    private static boolean changes(WriteContext ctx, Map<String, Object> original, String field) {
        if (!ctx.patch().containsKey(field)) {
            return false;
        }
        Object sent = ctx.patch().get(field);
        Object stored = original.get(field);
        if (ApprovalModel.APPROVAL_STATUS.equals(field)) {
            return ApprovalStatus.from(sent) != ApprovalStatus.from(stored);
        }
        if (ApprovalModel.SUBMITTED_TIME.equals(field)) {
            return !Objects.equals(secondOf(sent), secondOf(stored));
        }
        return !Objects.equals(idText(sent), idText(stored));
    }

    /** A time sent or stored, to the second; anything unreadable as a time stays as it is, and differs. */
    private static @Nullable Object secondOf(@Nullable Object value) {
        try {
            LocalDateTime time = DateUtils.dateToLocalDateTime(value, false);
            return time == null ? null : time.withNano(0);
        } catch (DateTimeParseException | ClassCastException e) {
            return value;
        }
    }

    /** A record's id as text, whether given bare, as an {@code {id, displayName}} reference or an entity. */
    private static @Nullable String idText(@Nullable Object value) {
        Object id = switch (value) {
            case null -> null;
            case ModelReference reference -> reference.getId();
            case AbstractModel entity -> entity.getId();
            case Map<?, ?> reference -> reference.get(ModelConstant.ID);
            default -> value;
        };
        return id == null || id.toString().isBlank() ? null : id.toString();
    }

    /** A write the system makes with permission checks off. */
    private static boolean systemWrite() {
        return !ContextHolder.existContext() || ContextHolder.getContext().isSkipPermissionCheck();
    }
}
