package io.softa.starter.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * Request body for {@code POST /admin/user/{userId}/roles/bulk} — the
 * Wizard's entry A (single user × N roles). Convenience wrapper that
 * the controller flattens into {@link UserRolePair} list and forwards
 * to {@code BulkUserRoleService.bulkAdd}.
 */
@Schema(description = "Entry A body — single user attaches multiple roles")
public record UserRolesDTO(
        @NotEmpty
        @Schema(description = "Role IDs to attach")
        List<Long> roleIds
) {
}
