package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Source of a user_role assignment.
 * - MANUAL:  Admin-granted via UI or REST API. Deleted only by explicit admin action.
 * - DYNAMIC: Auto-assigned by DynamicRoleSyncJob based on role.dynamic_filter.
 *            Re-evaluated each sync run; removed when user no longer matches filter.
 */
@Getter
@AllArgsConstructor
public enum RoleSource {
    MANUAL("Manual", "Admin-granted"),
    DYNAMIC("Dynamic", "Auto-synced by DynamicRoleSyncJob")
    ;

    @JsonValue
    private final String code;

    private final String description;
}
