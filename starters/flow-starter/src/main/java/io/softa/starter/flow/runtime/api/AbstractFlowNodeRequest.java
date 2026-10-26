package io.softa.starter.flow.runtime.api;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Base request scoped to a specific node within a runtime instance.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractFlowNodeRequest extends AbstractFlowInstanceRequest {

    @NotBlank(message = "nodeId must not be blank")
    @Schema(description = "Pending approval node id")
    private String nodeId;

    @Schema(description = "Form field edits submitted with the action; only fields the node's form permissions "
            + "mark EDITABLE or REQUIRED are written back to the business row (REQUIRED ones are validated), "
            + "everything else is dropped")
    private Map<String, Object> formData;
}
