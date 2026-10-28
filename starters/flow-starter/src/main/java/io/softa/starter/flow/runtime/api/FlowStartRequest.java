package io.softa.starter.flow.runtime.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for starting an execution from a registered flow bundle.
 * <p>
 * Resolution priority:
 * <ol>
 *   <li>{@code bundleId} — start this exact published revision (pinned).</li>
 *   <li>{@code flowId} — start the current (active) revision of a flow.</li>
 * </ol>
 * {@code flowId} is the {@link io.softa.starter.flow.entity.FlowDesign#getId()} distributed ID,
 * which is globally unique and tenant-collision-free. To start a specific historical revision,
 * look up its {@code bundleId} via the revisions listing endpoint and pass that instead.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowStartRequest")
public class FlowStartRequest extends AbstractFlowLaunchContextRequest {

    @Schema(description = "Flow id (stable flow identity, globally unique)")
    private String flowId;

    @Schema(description = "Optional: pinned bundle id; if set, flowId is ignored")
    private Long bundleId;

    @Schema(description = "Instance title for display and search")
    private String title;

    @Schema(description = "Related model name for SQL-level indexing")
    private String modelName;

    @Schema(description = "Related row data ID for SQL-level indexing")
    private String rowId;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Identity of the trigger event that starts the run (server-side only); with the "
            + "flow, an event starts a run at most once", accessMode = Schema.AccessMode.READ_ONLY)
    private String triggerEventId;
}
