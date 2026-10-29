package io.softa.starter.flow.controller;

import java.io.Serializable;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import io.softa.framework.orm.service.EntityService;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.dto.FlowRecordWithdrawRequest;
import io.softa.starter.flow.entity.ApprovalModel;

/**
 * The controller of a model whose records are approved by flows: extend it instead of
 * {@link EntityController}, and the model's records are submitted, withdrawn and cancelled the same way as
 * every other's — {@code POST /{Model}/submit}, {@code POST /{Model}/withdraw} and
 * {@code POST /{Model}/cancel}, through {@link FlowClient}. Each model keeps its own endpoints, so who may
 * submit which records is granted per model, like any other endpoint. What a record's submission would
 * do is answered for every model alike by {@code GET /flow/instances/preview}.
 *
 * @param <S> the model's service
 * @param <T> the model's entity
 * @param <K> its id type
 */
public abstract class ApprovalModelController<S extends EntityService<T, K>, T extends ApprovalModel,
        K extends Serializable> extends EntityController<S, T, K> {

    /** Lazy: the flow engine is needed at the first submission, not when the controller is built. */
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Lazy
    @Autowired
    protected FlowClient flowClient;

    /** The model this controller serves: its entity class's name, as for every entity. */
    protected final String modelName = ResolvableType.forClass(getClass())
            .as(ApprovalModelController.class).getGeneric(1).toClass().getSimpleName();

    @PostMapping("/submit")
    @Operation(summary = "Submit for approval",
            description = "Submits the draft for approval as the current user — again after a return or a withdrawal, "
                    + "which made it a draft and starts a new approval. Refused unless it is a draft, when the "
                    + "model's own checks refuse it, or when no approval flow, or several, apply to it.")
    public ApiResponse<Boolean> submit(@RequestParam K id) {
        flowClient.submitRecord(modelName, String.valueOf(id));
        return ApiResponse.success(true);
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw from approval",
            description = "Withdraws the record from its approval in progress as the current user, its submitter — or "
                    + "takes it back when its approval failed — making it a draft again. Refused when its flow does "
                    + "not let its submitter withdraw. The comment is optional.")
    public ApiResponse<Boolean> withdraw(@RequestParam K id,
                                         @RequestBody(required = false) FlowRecordWithdrawRequest request) {
        flowClient.withdrawRecord(modelName, String.valueOf(id), request == null ? null : request.comment());
        return ApiResponse.success(true);
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel the draft",
            description = "Cancels the draft for good as the current user. Refused unless it is a draft, or when the "
                    + "model's own checks refuse it. A cancelled record is never submitted again.")
    public ApiResponse<Boolean> cancel(@RequestParam K id) {
        flowClient.cancelRecord(modelName, String.valueOf(id));
        return ApiResponse.success(true);
    }
}
