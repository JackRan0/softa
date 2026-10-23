package io.softa.starter.seed.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tenant's seed files in numbers.
 */
@Schema(name = "TenantSeedSummary", description = "A tenant's seed files in numbers")
public record TenantSeedSummary(

        @Schema(description = "The tenant")
        Long tenantId,

        @Schema(description = "The files its plan and countries call for")
        int dueFiles,

        @Schema(description = "Files it ran that do not run again: loaded, changed after load, or loaded by a newer "
                + "build")
        int loadedFiles,

        @Schema(description = "What the next run picks up, failed ones included")
        int pendingFiles,

        @Schema(description = "Files whose latest attempt failed")
        int failedFiles,

        @Schema(description = "Run-once files that changed after the tenant ran them; they do not run again")
        int changedAfterLoadFiles,

        @Schema(description = "When it last ran a file successfully")
        LocalDateTime lastLoadedAt
) {
}
