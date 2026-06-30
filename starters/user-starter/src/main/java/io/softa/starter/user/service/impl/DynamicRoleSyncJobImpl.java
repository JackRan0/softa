package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.RoleSource;
import io.softa.starter.user.event.EmployeeChangedEvent;
import io.softa.starter.user.service.DynamicRoleSyncJob;
import io.softa.starter.user.service.EmployeeRelationsService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.util.ModelRefIds;

/**
 * DynamicRoleSyncJob — single source of truth for syncing the DYNAMIC rows
 * in user_role. Called from three places:
 *   1. RoleController wizard save (inline, per-role) — admins see synced
 *      members on the detail page immediately.
 *   2. {@link #syncAll()} via a `sys_cron` row + Pulsar consumer in the
 *      assembly module (default name: "DynamicRoleSync") — tenant-wide
 *      rescan that catches employee data changes between role saves.
 *   3. {@link #onEmployeeChanged} event listener — re-evaluates after a
 *      hire / transfer / status change so the affected user's roles update
 *      without waiting for the next cron tick.
 *
 * MANUAL rows are never touched. Each pass:
 *   - DELETE WHERE role_id = R AND source = 'Dynamic'
 *   - SELECT employee.userId WHERE rule AND userId IS SET AND userId.status = 'Active'
 *   - INSERT one (userId, R, 'Dynamic') per match
 *
 * The same (user, role) can also have a MANUAL row — schema unique key is
 * (tenant, user, role, source). The two rows coexist; permission evaluation
 * dedupes by (user, role).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicRoleSyncJobImpl implements DynamicRoleSyncJob {

    private final RoleService roleService;
    private final UserRoleRelService userRoleRelService;
    private final ModelService<?> modelService;
    /** Optional — present when corehr is on the classpath. Used by
     *  {@link #onEmployeeChanged} to resolve employeeId → (userId, tenantId)
     *  so the per-employee re-evaluation is scoped to the right tenant.
     *  When absent, the event listener degrades to a no-op (the next
     *  scheduled {@link #syncAll()} pass picks up the change). */
    private final ObjectProvider<EmployeeRelationsService> employeeRelations;

    @Override
    @Transactional
    public int syncRole(Long tenantId, Long roleId) {
        if (roleId == null) return 0;
        Optional<Role> roleOpt = roleService.getById(roleId);
        if (roleOpt.isEmpty()) {
            log.warn("DynamicRoleSyncJob.syncRole — role {} not found", roleId);
            return 0;
        }
        return syncRoleInternal(roleOpt.get());
    }

    /**
     * Sync one role from an already-loaded entity — saves the redundant
     * getById when callers (controller / event listener) already have the
     * Role in hand.
     */
    @Transactional
    private int syncRoleEntity(Role role) {
        return syncRoleInternal(role);
    }

    private int syncRoleInternal(Role role) {
        // 1. Snapshot the manual user-id set BEFORE the delete so we can
        //    fold both look-ups into the same round-trip pattern (manual
        //    + dynamic share the same role filter; ordering of these two
        //    relative to the delete doesn't matter for either correctness
        //    or visibility). Manual takes precedence — skip users that
        //    already have a MANUAL row for this role. By application
        //    convention at most one row per (user, role) exists, so this
        //    keeps Manual grants intact and avoids the UK collision case.
        Set<Long> manualUserIds = userRoleRelService.searchList(
                new Filters().eq(UserRoleRel::getRoleId, role.getId())
                        .eq(UserRoleRel::getSource, RoleSource.MANUAL.getCode())
        ).stream().map(UserRoleRel::getUserId).collect(java.util.stream.Collectors.toSet());

        // 2. Wipe existing DYNAMIC rows. When admin clears the rule
        //    entirely, the wipe is the only effect; otherwise it makes
        //    the subsequent insert idempotent (no diff-and-merge gymnastics).
        userRoleRelService.deleteByFilters(
                new Filters().eq(UserRoleRel::getRoleId, role.getId())
                        .eq(UserRoleRel::getSource, RoleSource.DYNAMIC.getCode()));

        JsonNode rule = role.getDynamicFilter();
        if (rule == null || rule.isNull() || !rule.isArray()) return 0;

        Object filterList = JsonUtils.jsonNodeToObject(rule);
        if (!(filterList instanceof List<?> list)) return 0;
        Filters filters = Filters.of(list);
        if (filters == null) return 0;

        // 3. AND in the user-account safety clauses (same as wizard's live
        //    preview) — pure-employee rows or disabled accounts never get
        //    a dynamic grant.
        filters.and(Filters.of("userId", Operator.IS_SET, null))
                .and(Filters.of("userId.status", Operator.EQUAL, "Active"));

        List<Map<String, Object>> employees = modelService.searchList(
                "Employee", new FlexQuery(List.of("userId"), filters));

        Set<Long> userIds = new LinkedHashSet<>();
        for (Map<String, Object> emp : employees) {
            Long uid = ModelRefIds.extractLongId(emp.get("userId"));
            if (uid != null) userIds.add(uid);
        }
        if (userIds.isEmpty()) return 0;

        List<UserRoleRel> rows = new ArrayList<>();
        for (Long uid : userIds) {
            if (manualUserIds.contains(uid)) continue;
            UserRoleRel ur = new UserRoleRel();
            ur.setRoleId(role.getId());
            ur.setUserId(uid);
            ur.setSource(RoleSource.DYNAMIC);
            rows.add(ur);
        }
        if (!rows.isEmpty()) userRoleRelService.createList(rows);
        log.info("DynamicRoleSyncJob.syncRole — role {} now has {} DYNAMIC members (skipped {} that already have Manual)",
                role.getId(), rows.size(), userIds.size() - rows.size());
        return rows.size();
    }

    @Override
    public void syncAll() {
        // Walk every role with a non-null dynamicFilter. Active flag is NOT
        // checked here — inactive roles still get their user_role rows kept
        // in sync; PermissionInfoEnricher skips inactive roles during ACL
        // evaluation.
        List<Role> roles = roleService.searchList(
                new Filters().isSet(Role::getDynamicFilter));
        log.info("DynamicRoleSyncJob.syncAll — {} role(s) with a dynamic rule", roles.size());
        int totalGrants = 0;
        for (Role role : roles) {
            try {
                totalGrants += syncRoleEntity(role);
            } catch (Exception e) {
                log.error("DynamicRoleSyncJob.syncAll — role {} sync failed; continuing", role.getId(), e);
            }
        }
        log.info("DynamicRoleSyncJob.syncAll — done, {} total DYNAMIC grants across all roles", totalGrants);
    }

    /**
     * Event entry point — re-evaluate role membership after a hire /
     * transfer / status change. Scope: this tenant only, and within this
     * tenant, only re-evaluate dynamic roles whose filter could affect
     * THIS employee.
     *
     * <p>Filter introspection is expensive, so we settle for "re-evaluate
     * every dynamic role in this tenant for this employee" (vs. the old
     * full cross-tenant {@code syncAll()}). For each role we ask the
     * Employee model whether this employeeId currently matches the
     * dynamic filter; if it does we ensure a (userId, roleId, DYNAMIC)
     * row exists, otherwise we delete one if present. Manual rows are
     * never touched. The scheduled {@link #syncAll()} job remains the
     * catch-all for anything an event missed.
     */
    @EventListener
    @Transactional
    public void onEmployeeChanged(EmployeeChangedEvent event) {
        if (event == null || event.employeeId() == null) return;
        log.debug("DynamicRoleSyncJob.onEmployeeChanged — employeeId={}, kind={}",
                event.employeeId(), event.kind());

        EmployeeRelationsService rel = employeeRelations.getIfAvailable();
        if (rel == null) {
            log.debug("DynamicRoleSyncJob.onEmployeeChanged — no EmployeeRelationsService on classpath; deferring to scheduled syncAll");
            return;
        }
        EmployeeRelationsService.UserHandle handle = rel.findUserByEmployeeId(event.employeeId());
        if (handle == null || handle.userId() == null) {
            log.debug("DynamicRoleSyncJob.onEmployeeChanged — employeeId={} has no linked user; nothing to evaluate",
                    event.employeeId());
            return;
        }
        Long tenantId = handle.tenantId() != null
                ? handle.tenantId()
                : (ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId());
        Long userId = handle.userId();

        // Load every dynamic role in this tenant. Cross-tenant roles
        // can't apply to this employee — their tenantId scopes them out.
        Filters roleFilter = new Filters().isSet(Role::getDynamicFilter);
        if (tenantId != null) roleFilter.eq(Role::getTenantId, tenantId);
        List<Role> roles = roleService.searchList(roleFilter);
        if (roles.isEmpty()) return;

        int added = 0;
        int removed = 0;
        for (Role role : roles) {
            try {
                if (applyPerEmployee(role, event.employeeId(), userId)) added++;
                else removed += removeDynamicIfPresent(role.getId(), userId);
            } catch (Exception e) {
                log.error("DynamicRoleSyncJob.onEmployeeChanged — role {} eval failed for employeeId={}; continuing",
                        role.getId(), event.employeeId(), e);
            }
        }
        log.info("DynamicRoleSyncJob.onEmployeeChanged — tenantId={}, employeeId={}, userId={}, +{} / -{} dynamic grants across {} role(s)",
                tenantId, event.employeeId(), userId, added, removed, roles.size());
    }

    /**
     * Returns true iff the employee currently matches this role's dynamic
     * filter (including the standard active-userId safety clauses). When
     * true, ensure a (userId, roleId, DYNAMIC) row exists — unless the
     * user already has a MANUAL row for this role (Manual takes precedence,
     * unique-key collision would result otherwise).
     */
    private boolean applyPerEmployee(Role role, Long employeeId, Long userId) {
        JsonNode rule = role.getDynamicFilter();
        if (rule == null || rule.isNull() || !rule.isArray()) return false;
        Object filterList = JsonUtils.jsonNodeToObject(rule);
        if (!(filterList instanceof List<?> list)) return false;
        Filters filters = Filters.of(list);
        if (filters == null) return false;
        // AND in the same safety clauses syncRoleInternal uses, plus the
        // single-employee anchor — we're asking "does THIS employee match".
        filters.and(Filters.of("userId", Operator.IS_SET, null))
                .and(Filters.of("userId.status", Operator.EQUAL, "Active"))
                .and(Filters.of("id", Operator.EQUAL, employeeId));
        long matches = modelService.count("Employee", filters);
        if (matches <= 0) return false;

        // Manual row check — if one exists, the schema's unique key
        // (tenant_id, user_id, role_id, source) still permits a separate
        // Dynamic row, but the wizard convention is one row per (user,
        // role). Skip the Dynamic insert when Manual is already present.
        boolean manualExists = userRoleRelService.exist(
                new Filters().eq(UserRoleRel::getRoleId, role.getId())
                        .eq(UserRoleRel::getUserId, userId)
                        .eq(UserRoleRel::getSource, RoleSource.MANUAL.getCode()));
        if (manualExists) return true; // matches the filter; just don't add a duplicate Dynamic row

        boolean dynamicExists = userRoleRelService.exist(
                new Filters().eq(UserRoleRel::getRoleId, role.getId())
                        .eq(UserRoleRel::getUserId, userId)
                        .eq(UserRoleRel::getSource, RoleSource.DYNAMIC.getCode()));
        if (dynamicExists) return true;

        UserRoleRel row = new UserRoleRel();
        row.setRoleId(role.getId());
        row.setUserId(userId);
        row.setSource(RoleSource.DYNAMIC);
        userRoleRelService.createOne(row);
        return true;
    }

    /** Remove the (userId, roleId, DYNAMIC) row if one exists. Manual rows
     *  are intentionally left intact. Returns count actually deleted. */
    private int removeDynamicIfPresent(Long roleId, Long userId) {
        List<UserRoleRel> existing = userRoleRelService.searchList(
                new Filters().eq(UserRoleRel::getRoleId, roleId)
                        .eq(UserRoleRel::getUserId, userId)
                        .eq(UserRoleRel::getSource, RoleSource.DYNAMIC.getCode()));
        if (existing.isEmpty()) return 0;
        userRoleRelService.deleteByIds(existing.stream().map(UserRoleRel::getId).toList());
        return existing.size();
    }

}
