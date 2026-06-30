package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.event.EmployeeChangedEvent;
import io.softa.starter.user.event.RoleNavigationChangedEvent;
import io.softa.starter.user.event.UserRoleRelChangedEvent;
import io.softa.starter.user.service.EmployeeRelationsService;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.PermissionInfoEnricher;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * Redis-cache invalidator + event listeners that drive it.
 *
 * <p>{@code evict*} methods compute the canonical cache key
 * ({@link PermissionInfoEnricher#cacheKey}) and call
 * {@link CacheService#clear} — single or batch — to delete the entry.
 * The cache then misses on next request and re-fills via the underlying
 * {@link PermissionInfoEnricher} (DB read).
 *
 * <p>No tenant-wide eviction exists: {@link CacheService} doesn't expose
 * SCAN-style pattern delete, and the failure modes for "evict every user
 * in this tenant" all reduce to either (a) feed an explicit user-id set
 * through {@link #evictBatch}, or (b) wait out the 1h Redis TTL. Bulk
 * admin ops do the former; system-level seed data (navigation /
 * permission / sensitive_field_set) only changes via redeployment so a
 * restart flushes the in-memory indexes and the TTLs cover the rest.
 *
 * <h3>Event → eviction fan-out</h3>
 * <ul>
 *   <li>{@link UserRoleRelChangedEvent}    → {@link #evictBatch} for the users
 *       whose role set changed.</li>
 *   <li>{@link RoleNavigationChangedEvent} → {@link #evictByRole} for the role
 *       whose nav/permission/scope grant changed.</li>
 *   <li>{@link EmployeeChangedEvent}       → evict the affected user's snapshot
 *       (employeeContext may have changed).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionCacheInvalidatorImpl implements PermissionCacheInvalidator {

    private final UserRoleRelService userRoleRelService;
    private final CacheService cacheService;
    /** Optional — present when corehr is on the classpath. Used by
     *  {@link #onEmployeeChanged} to resolve employeeId → (userId, tenantId)
     *  so we can target the right cache key. When absent, EmployeeChanged
     *  fan-out degrades to a no-op (cache will TTL out). */
    private final ObjectProvider<EmployeeRelationsService> employeeRelations;

    // ────────────────────── public evict API ──────────────────────

    @Override
    public void evictOne(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) return;
        String key = PermissionInfoEnricher.cacheKey(tenantId, userId);
        try {
            cacheService.clear(key);
            log.debug("PermissionInfo cache evict — key={}", key);
        } catch (Throwable t) {
            log.warn("PermissionInfo cache evict failed — key={}", key, t);
        }
    }

    @Override
    public void evictBatch(Long tenantId, Set<Long> userIds) {
        if (tenantId == null || userIds == null || userIds.isEmpty()) return;
        List<String> keys = new ArrayList<>(userIds.size());
        for (Long uid : userIds) {
            if (uid != null) keys.add(PermissionInfoEnricher.cacheKey(tenantId, uid));
        }
        if (keys.isEmpty()) return;
        try {
            cacheService.clear(keys);
            log.debug("PermissionInfo cache evict batch — tenantId={}, count={}", tenantId, keys.size());
        } catch (Throwable t) {
            log.warn("PermissionInfo cache evict batch failed — tenantId={}, count={}",
                    tenantId, keys.size(), t);
        }
    }

    @Override
    public void evictByRole(Long tenantId, Long roleId) {
        if (tenantId == null || roleId == null) return;
        Set<Long> userIds = usersHoldingRole(roleId);
        if (userIds.isEmpty()) {
            log.debug("PermissionInfo cache evict by role — no users hold roleId={}; nothing to do", roleId);
            return;
        }
        evictBatch(tenantId, userIds);
    }

    // ────────────────────── event listeners ──────────────────────

    @EventListener
    public void onUserRoleRelChanged(UserRoleRelChangedEvent ev) {
        if (ev.userIds() == null || ev.userIds().isEmpty()) {
            // Empty fan-out — nothing actionable. Publishers must include
            // a concrete user-id set; relying on tenant-wide invalidation
            // here was a fail-loud crutch that the cache TTL (1h) covers
            // in practice. Log so the offending publisher is identifiable.
            log.warn("PermissionCacheInvalidator — UserRoleRelChangedEvent (tenantId={}) carried no userIds; "
                    + "no eviction performed (relying on 1h cache TTL)", ev.tenantId());
            return;
        }
        evictBatch(ev.tenantId(), ev.userIds());
    }

    @EventListener
    public void onRoleNavigationChanged(RoleNavigationChangedEvent ev) {
        if (ev.roleId() == null) return;
        // evictByRole resolves the user set + batch-clears internally.
        evictByRole(ev.tenantId(), ev.roleId());
    }

    @EventListener
    public void onEmployeeChanged(EmployeeChangedEvent ev) {
        // DynamicRoleSyncJob already listens for this to re-sync user_role_rel
        // rows; that in turn publishes UserRoleRelChangedEvent which evicts.
        // Here we additionally evict the affected user directly because the
        // EmpInfo payload (deptId / companyId / managedDeptIds) baked into
        // the cached PermissionInfo's Principal.extensions["employee"] may
        // have changed without any user_role_rel mutation.
        if (ev.employeeId() == null) return;
        EmployeeRelationsService rel = employeeRelations.getIfAvailable();
        if (rel == null) {
            // No corehr on classpath → no way to resolve userId. Cache will
            // TTL out within the hour; log so ops can spot if this matters.
            log.debug("PermissionCacheInvalidator — EmployeeChangedEvent (employeeId={}, kind={}) — no EmployeeRelationsService; relying on TTL",
                    ev.employeeId(), ev.kind());
            return;
        }
        EmployeeRelationsService.UserHandle handle = rel.findUserByEmployeeId(ev.employeeId());
        if (handle == null) {
            log.debug("PermissionCacheInvalidator — EmployeeChangedEvent (employeeId={}) has no linked user; nothing to evict",
                    ev.employeeId());
            return;
        }
        evictOne(handle.tenantId(), handle.userId());
    }

    // ────────────────────── helpers ──────────────────────

    /** Look up the users currently holding a role. Bounded by typical role
     *  cardinality (a few hundred holders); pulls user ids only. */
    private Set<Long> usersHoldingRole(Long roleId) {
        List<UserRoleRel> rels = userRoleRelService.searchList(
                new Filters().eq(UserRoleRel::getRoleId, roleId));
        Set<Long> userIds = new HashSet<>(rels.size());
        for (UserRoleRel r : rels) {
            if (r.getUserId() != null) userIds.add(r.getUserId());
        }
        return userIds;
    }

}
