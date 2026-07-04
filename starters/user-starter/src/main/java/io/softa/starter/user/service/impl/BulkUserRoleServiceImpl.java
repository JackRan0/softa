package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.BulkAddResult;
import io.softa.starter.user.dto.UserRolePair;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.RoleSource;
import io.softa.starter.user.service.BulkUserRoleService;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * Bulk user-role assignment service. Loads existing rows for the (userIds,
 * roleIds) cross-product once, then inserts only the (userId, roleId) tuples
 * that don't already have a row for the requested source. Skipped tuples
 * carry reason=ALREADY_ASSIGNED.
 *
 * <p>Cache invalidation: every userId that ended up with a NEW row gets
 * fan-out via {@link PermissionCacheInvalidator#evictBatch} after commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkUserRoleServiceImpl implements BulkUserRoleService {

    private final UserRoleRelService userRoleRelService;
    private final PermissionCacheInvalidator permissionCacheInvalidator;
    /** Load UserAccount / Role rows scoped by caller's tenant to reject
     *  cross-tenant user_role_rel writes. Both services
     *  are multi-tenant models — their {@code searchList} auto-appends
     *  {@code tenant_id = caller.tenantId} via WhereBuilder, so a probe
     *  {@code IN(userIds)} returns only ids native to caller's tenant. */
    private final UserAccountService userAccountService;
    private final RoleService roleService;

    @Override
    @Transactional
    public BulkAddResult bulkAdd(List<UserRolePair> pairs, RoleSource source) {
        List<BulkAddResult.AddedItem> added = new ArrayList<>();
        List<BulkAddResult.SkippedItem> skipped = new ArrayList<>();
        int requested = pairs == null ? 0 : pairs.size();
        if (pairs == null || pairs.isEmpty()) {
            return BulkAddResult.builder()
                    .added(added)
                    .skipped(skipped)
                    .summary(BulkAddResult.Summary.builder()
                            .requested(0).added(0).skipped(0).build())
                    .build();
        }
        RoleSource src = source == null ? RoleSource.MANUAL : source;

        // Distinct user / role id sets — used for the existing-row probe.
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> roleIds = new LinkedHashSet<>();
        for (UserRolePair p : pairs) {
            if (p == null || p.getUserId() == null || p.getRoleId() == null) continue;
            userIds.add(p.getUserId());
            roleIds.add(p.getRoleId());
        }

        // Tenant validation: the caller's payload can carry
        // arbitrary userIds / roleIds. Without this check, tenant A admin
        // could POST a tenant B userId and land a user_role row in tenant A
        // that references a foreign-tenant user — next time that foreign
        // user logs in they inherit tenant A's role grants. Probe both
        // reference models scoped by caller.tenantId (auto-appended by
        // WhereBuilder); any id absent from the probe result is cross-tenant
        // (or non-existent, which we don't distinguish to avoid info-leak).
        Set<Long> validUserIds = new HashSet<>();
        if (!userIds.isEmpty()) {
            List<UserAccount> accounts = userAccountService.searchList(
                    new Filters().in(UserAccount::getId, new ArrayList<>(userIds)));
            for (UserAccount a : accounts) {
                if (a.getId() != null) validUserIds.add(a.getId());
            }
        }
        Set<Long> validRoleIds = new HashSet<>();
        if (!roleIds.isEmpty()) {
            List<Role> rolesInTenant = roleService.searchList(
                    new Filters().in(Role::getId, new ArrayList<>(roleIds)));
            for (Role r : rolesInTenant) {
                if (r.getId() != null) validRoleIds.add(r.getId());
            }
        }

        // Probe for rows that ALREADY exist with this source. The schema
        // unique key is (tenant_id, user_id, role_id, source); we re-insert
        // only when the (user, role, source) triple is missing. Dedupe key
        // here mirrors that.
        Set<String> existingKeys = new HashSet<>();
        if (!userIds.isEmpty() && !roleIds.isEmpty()) {
            List<UserRoleRel> existing = userRoleRelService.searchList(new Filters()
                    .in(UserRoleRel::getUserId, new ArrayList<>(userIds))
                    .in(UserRoleRel::getRoleId, new ArrayList<>(roleIds))
                    .eq(UserRoleRel::getSource, src.getCode()));
            for (UserRoleRel r : existing) {
                if (r.getUserId() == null || r.getRoleId() == null) continue;
                existingKeys.add(keyOf(r.getUserId(), r.getRoleId()));
            }
        }

        Long tenantId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getTenantId();

        // Build the insert batch. Within the request itself, fold
        // duplicate pairs to a single row so a sloppy client payload
        // doesn't trip the unique key after our pre-check passes.
        Map<String, UserRoleRel> toInsert = new HashMap<>();
        for (UserRolePair p : pairs) {
            if (p == null || p.getUserId() == null || p.getRoleId() == null) {
                skipped.add(BulkAddResult.SkippedItem.builder()
                        .userId(p == null ? null : p.getUserId())
                        .roleId(p == null ? null : p.getRoleId())
                        .reason("INVALID_PAIR")
                        .build());
                continue;
            }
            // H2: cross-tenant / non-existent user or role → reject silently.
            // "Silently" = same "NOT_FOUND" reason for both cases so the caller
            // can't distinguish "user exists in another tenant" from "user
            // doesn't exist at all" (info-leak hygiene per issue writeup).
            if (!validUserIds.contains(p.getUserId()) || !validRoleIds.contains(p.getRoleId())) {
                skipped.add(BulkAddResult.SkippedItem.builder()
                        .userId(p.getUserId())
                        .roleId(p.getRoleId())
                        .reason("NOT_FOUND")
                        .build());
                continue;
            }
            String key = keyOf(p.getUserId(), p.getRoleId());
            if (existingKeys.contains(key)) {
                skipped.add(BulkAddResult.SkippedItem.builder()
                        .userId(p.getUserId())
                        .roleId(p.getRoleId())
                        .reason("ALREADY_ASSIGNED")
                        .build());
                continue;
            }
            if (toInsert.containsKey(key)) {
                // Same pair appeared earlier in this payload — folded to one
                // row, but recorded so requested == added + skipped holds and
                // the client can account for every submitted pair.
                skipped.add(BulkAddResult.SkippedItem.builder()
                        .userId(p.getUserId())
                        .roleId(p.getRoleId())
                        .reason("DUPLICATE_IN_REQUEST")
                        .build());
                continue;
            }
            UserRoleRel row = new UserRoleRel();
            row.setTenantId(tenantId);
            row.setUserId(p.getUserId());
            row.setRoleId(p.getRoleId());
            row.setSource(src);
            toInsert.put(key, row);
        }

        Set<Long> evictUsers = new HashSet<>();
        if (!toInsert.isEmpty()) {
            List<UserRoleRel> rows = new ArrayList<>(toInsert.values());
            List<Long> ids = userRoleRelService.createList(rows);
            // createList returns ids in the same order as the input list.
            int i = 0;
            for (UserRoleRel row : rows) {
                Long id = (ids != null && i < ids.size()) ? ids.get(i) : null;
                added.add(BulkAddResult.AddedItem.builder()
                        .userId(row.getUserId())
                        .roleId(row.getRoleId())
                        .userRoleId(id)
                        .build());
                if (row.getUserId() != null) evictUsers.add(row.getUserId());
                i++;
            }
        }

        // Cache fan-out — single batch call so we get one Redis pipeline
        // round-trip even with hundreds of affected users.
        if (!evictUsers.isEmpty()) {
            permissionCacheInvalidator.evictBatch(tenantId, evictUsers);
        }

        log.info("BulkUserRoleService.bulkAdd — source={}, requested={}, added={}, skipped={}",
                src, requested, added.size(), skipped.size());
        return BulkAddResult.builder()
                .added(added)
                .skipped(skipped)
                .summary(BulkAddResult.Summary.builder()
                        .requested(requested)
                        .added(added.size())
                        .skipped(skipped.size())
                        .build())
                .build();
    }

    private static String keyOf(Long userId, Long roleId) {
        return userId + ":" + roleId;
    }
}
