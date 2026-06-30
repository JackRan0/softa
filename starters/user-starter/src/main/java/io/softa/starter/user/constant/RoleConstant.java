package io.softa.starter.user.constant;

import io.softa.starter.user.entity.Role;

/**
 * Reserved {@code Role.code} values + related role-scoped constants and
 * pure-function helpers.
 *
 * <p>System-reserved roles have non-null {@code code} (machine identifier);
 * admin-created roles always have null code. Adding a new reserved code:
 * append a static here AND register it in
 * {@code PermissionRegistryValidator}'s reserved-code set so admins can't
 * squat on it.
 */
public final class RoleConstant {

    /**
     * Reserved code for the system Super-Admin role.
     *
     * <p>Holders short-circuit Layer A (PermissionInterceptor), Layer B
     * (ScopeFilterAspect), Layer C (FieldFilter) and Layer D
     * (FieldWriteGuardAspect) — every check consults
     * {@code PermissionInfo.isSuperAdmin(pi)} which tests this code.
     *
     * <p>The role row itself cannot be deleted / renamed / made inactive;
     * the service layer also rejects revoking the last Manual holder.
     */
    public static final String CODE_SUPER_ADMIN = "SUPER_ADMIN";

    /**
     * True when the given role is the system-reserved Super-Admin role.
     *
     * <p>Lives here (not on {@link Role}) so the entity stays a pure data
     * carrier — business predicates against {@code code} belong with the
     * reserved-code definition, not the JPA row class.
     *
     * <p>Null-safe: {@code role == null} → false.
     */
    public static boolean isSuperAdmin(Role role) {
        return role != null && CODE_SUPER_ADMIN.equals(role.getCode());
    }

    private RoleConstant() {
        // utility class — no instances
    }
}
