package io.softa.starter.seed.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.seed.enums.SeedLoadStatus;

/**
 * What initializing an environment did: the tenant files it recorded as run, then the first platform load.
 */
@Schema(name = "InitializeResult", description = "What initializing an environment did: the tenant files it recorded "
        + "as run, then the first platform load")
public record InitializeResult(

        @Schema(description = "The Initialize batch, whose records are the tenant files recorded as run")
        Long batchId,

        @Schema(description = "How the recording ended. PartiallyFailed still initializes the environment; Failed does "
                + "not, and loads no platform file — call again once the cause is fixed")
        SeedLoadStatus status,

        @Schema(description = "Set-up tenants looked at")
        int tenants,

        @Schema(description = "Tenants whose recording failed; the log names them")
        int failedTenants,

        @Schema(description = "Tenant files recorded as run, over all tenants")
        int recordedFiles,

        @Schema(description = "The platform load that followed; null when nothing was pending or the recording failed. "
                + "After a successful one, restart every instance")
        PlatformLoadResult platformLoad
) {
}
