package io.softa.starter.user.service;

/**
 * Re-evaluates role.dynamic_filter for every active role and synchronizes
 * the user_role table (source=DYNAMIC rows only). Runs on a Spring cron
 * schedule (default: every night at 02:00).
 *
 * Algorithm (per role with non-null dynamic_filter):
 *   1. Run the filter as ORM query → set of currently-matching user IDs.
 *   2. Diff against existing user_role rows where role_id=R AND source=DYNAMIC.
 *   3. INSERT new matches, DELETE rows no longer matching.
 *   4. Manual rows (source=MANUAL) are never touched.
 *   5. Affected user IDs are batch-evicted from PermissionInfo cache.
 *
 * INNER JOIN employee in the underlying SQL means pure users (no employee
 * record) are NEVER matched by dynamic rules — they can only be assigned
 * roles MANUALLY.
 */
public interface DynamicRoleSyncJob {

    /**
     * Sync a specific role's dynamic membership immediately (admin-triggered).
     * @return count of inserts + deletes performed
     */
    int syncRole(Long tenantId, Long roleId);

    /**
     * Sync all roles in all tenants. Invoked by the Spring scheduler.
     */
    void syncAll();
}
