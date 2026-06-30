package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * Role — per-tenant business role with optional dynamic membership rule.
 *
 * <p>{@code code} is the stable machine identifier (NULL for admin-created
 * roles; non-null for system-reserved roles). Reserved code:
 * <ul>
 *   <li>{@code SUPER_ADMIN} — short-circuit bypass at permission / data-scope
 *       evaluation. Cannot be deleted / renamed / made inactive.
 *       Revoking the last Manual holder is rejected at the service layer.</li>
 * </ul>
 *
 * <p>(tenant_id, name) UNIQUE serves as the human business identifier.
 */
@Data
@Schema(name = "Role")
@EqualsAndHashCode(callSuper = true)
public class Role extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Role name (also business identifier; UNIQUE per tenant)")
    private String name;

    @Schema(description = "Optional description")
    private String description;

    @Schema(description = "Stable machine code (e.g. SUPER_ADMIN). NULL for admin-created roles.")
    private String code;

    @Schema(description = "Active flag; inactive roles are skipped during PermissionInfo enrich")
    private Boolean active;

    @Schema(description = "Dynamic membership filter (JSON FilterCondition). DynamicRoleSyncJob auto-assigns matching users (source=DYNAMIC)")
    private JsonNode dynamicFilter;
}
