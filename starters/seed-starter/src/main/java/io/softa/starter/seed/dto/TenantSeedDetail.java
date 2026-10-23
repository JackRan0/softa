package io.softa.starter.seed.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Every tenant seed file a tenant is due, in load order.
 */
@Schema(name = "TenantSeedDetail", description = "Every tenant seed file a tenant is due, in load order")
public record TenantSeedDetail(

        @Schema(description = "The tenant")
        Long tenantId,

        @Schema(description = "Its due files, in manifest (load) order")
        List<TenantFileStatus> files
) {
}
