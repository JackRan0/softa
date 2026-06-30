package io.softa.starter.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * Request body for {@code POST /admin/role/{roleId}/users/bulk} — the
 * Wizard's entry B (single role × N users). Convenience wrapper that
 * the controller flattens into {@link UserRolePair} list and forwards
 * to {@code BulkUserRoleService.bulkAdd}.
 */
@Schema(description = "Entry B body — single role assigns multiple users")
public record RoleUsersDTO(
        @NotEmpty
        @Schema(description = "User IDs to attach")
        List<Long> userIds
) {
}
