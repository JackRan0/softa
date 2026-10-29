package io.softa.starter.flow.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Terminate approvals from the monitoring console: one or several, with one reason. */
@Data
@Schema(name = "FlowTerminateBatchRequest")
public class FlowTerminateBatchRequest {

    @NotEmpty(message = "Choose at least one instance")
    @Size(max = 100, message = "At most 100 instances can be terminated at once")
    @Schema(description = "Runtime instance ids")
    private List<String> instanceIds;

    @NotBlank(message = "A reason is required")
    @Size(max = 256, message = "The reason can be at most 256 characters")
    @Schema(description = "Why they are terminated; recorded on each and sent to its initiator")
    private String reason;
}
