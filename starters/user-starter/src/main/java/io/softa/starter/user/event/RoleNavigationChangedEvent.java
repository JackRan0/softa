package io.softa.starter.user.event;

/**
 * Fired when a role's nav/permission/scope grants change. Publishers:
 * RoleController.saveWizard (rewrites all rows for the role), RoleNavigation
 * direct CRUD endpoints, Role.active flip (effective grants vanish/return).
 *
 * <p>Listener: {@link io.softa.starter.user.service.PermissionCacheInvalidator}
 * evicts every user holding this role (typically via {@code evictByRole}).
 *
 * <p>This event is per-role, not per-row — a wizard save that rewrites N
 * rows still emits one event.
 *
 * @param tenantId tenant the role belongs to
 * @param roleId   role whose grant set changed
 */
public record RoleNavigationChangedEvent(Long tenantId, Long roleId) {
}
