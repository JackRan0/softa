package io.softa.starter.user.event;

import java.util.Set;

/**
 * Fired when {@code user_role_rel} rows change for one or more users.
 * Publishers: BulkUserRoleService.bulkAdd, UserRoleRelService.delete*,
 * RoleController.saveWizard (wipe-and-rewrite of MANUAL rows),
 * DynamicRoleSyncJob (when DYNAMIC rows change).
 *
 * <p>Listener: {@link io.softa.starter.user.service.PermissionCacheInvalidator}
 * evicts {@code perm:{tenantId}:user:{userId}} for every affected user — their
 * next request rebuilds PermissionInfo from the new role set.
 *
 * @param tenantId  tenant whose users are affected (single tenant per event;
 *                  cross-tenant batches must publish multiple events)
 * @param userIds   user ids whose user_role_rel rows changed. Empty set means
 *                  "fan-out unknown" — listener should be conservative
 *                  (typically tenant-wide evict).
 */
public record UserRoleRelChangedEvent(Long tenantId, Set<Long> userIds) {

    public static UserRoleRelChangedEvent forUser(Long tenantId, Long userId) {
        return new UserRoleRelChangedEvent(tenantId, Set.of(userId));
    }

    public static UserRoleRelChangedEvent forUsers(Long tenantId, Set<Long> userIds) {
        return new UserRoleRelChangedEvent(tenantId, Set.copyOf(userIds));
    }
}
