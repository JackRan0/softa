package io.softa.starter.seed.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a run of every tenant would do.
 */
@Schema(name = "TenantPendingSummary", description = "What a run of every tenant would do")
public record TenantPendingSummary(

        @Schema(description = "Set-up tenants with at least one pending file")
        int tenantCount,

        @Schema(description = "Pending file runs, over all of them")
        int fileCount
) {
}
