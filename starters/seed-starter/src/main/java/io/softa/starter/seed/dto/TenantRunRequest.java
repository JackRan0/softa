package io.softa.starter.seed.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The tenants to run.
 */
@Schema(name = "TenantRunRequest", description = "The tenants to run")
public record TenantRunRequest(

        @Schema(description = "Set-up tenants (Active, Suspended or Closed) whose pending files to run. Omit for every "
                + "set-up tenant; an empty list is refused")
        List<Long> tenantIds
) {
}
