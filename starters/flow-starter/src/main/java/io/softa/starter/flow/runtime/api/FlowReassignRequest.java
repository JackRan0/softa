package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * An administrator moving one approver's pending task to another: {@code actorId} is the approver whose task
 * it is, {@code targetActorId} who takes it over, {@code operatorId} the administrator, {@code comment} the
 * reason (required). Built by the reassignment service, never bound from a request body.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(hidden = true)
public class FlowReassignRequest extends AbstractFlowNodeTargetActorRequest {

    private String operatorId;
}
