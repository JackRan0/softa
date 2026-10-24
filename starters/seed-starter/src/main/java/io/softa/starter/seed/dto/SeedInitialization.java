package io.softa.starter.seed.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.starter.seed.enums.SeedLoadStatus;

/**
 * What the environment's first platform load recorded before loading: in every set-up tenant, the tenant files
 * the tenant already has rows of, recorded as run without changing any data.
 */
@Schema(name = "SeedInitialization", description = "What the environment's first platform load recorded before "
        + "loading: in every set-up tenant, the tenant files it already has rows of, recorded as run without "
        + "changing any data")
public record SeedInitialization(

        @Schema(description = "The Initialize batch, whose records are the tenant files recorded as run")
        Long batchId,

        @Schema(description = "How the recording ended. PartiallyFailed still initializes the environment; Failed "
                + "does not, and loads no platform file — load again once the cause is fixed")
        SeedLoadStatus status,

        @Schema(description = "Set-up tenants looked at")
        int tenants,

        @Schema(description = "Tenants whose recording failed; the log names them")
        int failedTenants,

        @Schema(description = "Tenant files recorded as run, over all tenants")
        int recordedFiles
) {
}
