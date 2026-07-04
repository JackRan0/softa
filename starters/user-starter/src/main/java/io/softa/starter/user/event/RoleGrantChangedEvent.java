package io.softa.starter.user.event;

/**
 * Fired when a role's DATA-dimension grants change — i.e. its
 * {@code role_data_scope} (row scope per model) or
 * {@code role_sensitive_field_set} (SFS grants) rows. Publishers:
 * {@code RoleController.saveWizard} (rewrites all rows for the role) and the
 * direct CRUD paths on those two services.
 *
 * <p>Listener: {@link io.softa.starter.user.service.PermissionCacheInvalidator}
 * evicts every user holding this role (via {@code evictByRole}).
 *
 * <p>Semantically parallel to {@link RoleNavigationChangedEvent} (which covers
 * the nav/permission dimension); kept as a distinct event so the two write
 * paths stay decoupled, but both drive the same per-role cache eviction.
 *
 * @param tenantId tenant the role belongs to
 * @param roleId   role whose data-scope / SFS grants changed
 */
public record RoleGrantChangedEvent(Long tenantId, Long roleId) {
}
