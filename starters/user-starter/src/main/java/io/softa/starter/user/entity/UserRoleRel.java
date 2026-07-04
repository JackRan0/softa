package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.user.enums.RoleSource;

/**
 * UserRoleRel — user-to-role assignment join row. The same (user, role)
 * pair MAY have BOTH a MANUAL row (admin-added) and a DYNAMIC row
 * (auto-synced by DynamicRoleSyncJob) at the schema level — the UNIQUE
 * constraint is (tenant_id, user_id, role_id, source). The wizard's
 * convention enforces a single row per pair (Manual takes precedence),
 * but the schema allows both for compatibility / future audit needs.
 *
 * Deleting a MANUAL row leaves the DYNAMIC row intact (prevents accidental
 * de-authorization). Dynamic rows only refresh on cron sync, not on direct
 * delete.
 */
@Data
@Schema(name = "UserRoleRel")
@EqualsAndHashCode(callSuper = true)
public class UserRoleRel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "User ID (FK user_account.id)")
    private Long userId;

    @Schema(description = "Role ID (FK role.id)")
    private Long roleId;

    @Schema(description = "Source: MANUAL (admin grant) or DYNAMIC (DynamicRoleSyncJob auto-assign)")
    private RoleSource source;
}
