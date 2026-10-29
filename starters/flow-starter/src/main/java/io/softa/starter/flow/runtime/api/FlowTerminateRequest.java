package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * An administrator ending an approval that runs, waits or failed: the instance is cancelled and its record with
 * it. The {@code comment} is the reason, required. Authorized by the caller beforehand — the monitoring
 * console's permission and data scope.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowTerminateRequest")
public class FlowTerminateRequest extends AbstractFlowInstanceRequest {
}
