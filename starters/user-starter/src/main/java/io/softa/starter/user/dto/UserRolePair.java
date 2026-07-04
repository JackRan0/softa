package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One pair in POST /admin/user-role/bulk request body.
 * Frontend filters pairs (by classifyRoleForUser + bulk decision) before submit.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "User-role pair for bulk assignment")
public class UserRolePair {

    @NotNull
    @Schema(description = "User ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @NotNull
    @Schema(description = "Role ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long roleId;
}
