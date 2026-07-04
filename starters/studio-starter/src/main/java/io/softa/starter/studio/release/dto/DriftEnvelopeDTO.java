package io.softa.starter.studio.release.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.studio.release.enums.DesignDriftCheckStatus;

/**
 * Envelope wrapping a freshly-computed drift report together with the check outcome,
 * error (on failure), and the time the check ran. Drift is computed on demand on every
 * request — there is no persisted drift cache — so the result always reflects the
 * runtime state at the moment the operator hit Refresh.
 * <p>
 * {@code checkStatus} and {@code lastCheckedTime} are therefore always populated:
 * {@code lastCheckedTime} is the instant this check ran, and {@code checkStatus} is
 * {@code SUCCESS} when the diff completed (with {@code hasDrift} / {@code reports}
 * carrying the result) or {@code FAILURE} when the runtime was unreachable / the diff
 * threw (with {@code errorMessage} set, {@code hasDrift} false, and {@code reports}
 * empty).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DriftEnvelope", description = "Freshly-computed drift report plus the check outcome and timing")
public class DriftEnvelopeDTO {

    @Schema(description = "Environment ID")
    private Long envId;

    @Schema(description = "Outcome of this check: SUCCESS or FAILURE (always populated — drift is computed on demand)")
    private DesignDriftCheckStatus checkStatus;

    @Schema(description = "Error message when checkStatus is FAILURE; null on success")
    private String errorMessage;

    @Schema(description = "When this check ran (always populated)")
    private LocalDateTime lastCheckedTime;

    @Schema(description = "Whether the check found drift (false on failure)")
    private boolean hasDrift;

    @Schema(description = "Drift rows grouped by model; empty when in sync or on failure")
    @Builder.Default
    private List<DriftReportDTO> reports = new ArrayList<>();
}
