package io.softa.starter.user.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.event.RoleNavigationChangedEvent;
import io.softa.starter.user.service.RoleNavigationService;

/**
 * RoleNavigation Model Service Implementation.
 *
 * <p>Publishes {@link RoleNavigationChangedEvent} after every write so the
 * cache invalidator can evict PermissionInfo for every user holding the
 * affected role. Wizard saves (RoleController.saveWizard) wipe-and-rewrite
 * all rows for a role; we coalesce them under a single event per touched
 * roleId so the listener doesn't N+1 evict.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleNavigationServiceImpl extends EntityServiceImpl<RoleNavigation, Long> implements RoleNavigationService {

    private final ApplicationEventPublisher events;

    @Override
    public Long createOne(RoleNavigation entity) {
        Long id = super.createOne(entity);
        publish(Set.of(entity.getRoleId()));
        return id;
    }

    @Override
    public List<Long> createList(List<RoleNavigation> entities) {
        List<Long> ids = super.createList(entities);
        publish(collectRoleIds(entities));
        return ids;
    }

    @Override
    public boolean updateOne(RoleNavigation entity) {
        boolean ok = super.updateOne(entity);
        if (ok && entity != null) publish(Set.of(entity.getRoleId()));
        return ok;
    }

    @Override
    public boolean updateOne(RoleNavigation entity, boolean ignoreNull) {
        boolean ok = super.updateOne(entity, ignoreNull);
        if (ok && entity != null) publish(Set.of(entity.getRoleId()));
        return ok;
    }

    @Override
    public boolean updateList(List<RoleNavigation> entities) {
        boolean ok = super.updateList(entities);
        if (ok) publish(collectRoleIds(entities));
        return ok;
    }

    @Override
    public boolean deleteById(Long id) {
        Set<Long> roleIds = collectAffectedRoleIds(List.of(id));
        boolean ok = super.deleteById(id);
        publish(roleIds);
        return ok;
    }

    @Override
    public boolean deleteByIds(List<Long> ids) {
        Set<Long> roleIds = collectAffectedRoleIds(ids);
        boolean ok = super.deleteByIds(ids);
        publish(roleIds);
        return ok;
    }

    @Override
    public boolean deleteByFilters(Filters filters) {
        // Fast path: the wizard always passes a `roleId = X` leaf — extract
        // the roleId directly from the Filters AST so we can publish the
        // changed-event without an extra searchList. Slow path (rare:
        // ops scripts / tests with composite filters) falls back to
        // resolving via searchList and logs so we notice.
        Set<Long> roleIds = roleIdsFromFilters(filters);
        if (roleIds.isEmpty()) {
            log.debug("RoleNavigation.deleteByFilters — could not extract roleId from filter AST; "
                    + "falling back to searchList (slow path). filters={}", filters);
            List<RoleNavigation> affected = searchList(filters);
            roleIds = collectRoleIds(affected);
        }
        boolean ok = super.deleteByFilters(filters);
        publish(roleIds);
        return ok;
    }

    /**
     * Walk the {@link Filters} AST and return every {@code roleId = X}
     * leaf's literal value. Returns empty set when the filter uses
     * {@code roleId IN (...)} / OR-trees / no roleId at all — caller
     * then falls back to the search-based collection. We intentionally
     * keep this conservative: only EQUAL leaves on the {@code roleId}
     * field are recognised, because anything else risks publishing a
     * change event for roles that weren't actually touched.
     */
    private static Set<Long> roleIdsFromFilters(Filters filters) {
        Set<Long> out = new HashSet<>();
        collectRoleIdLiterals(filters, out);
        return out;
    }

    private static final String ROLE_ID_FIELD =
            LambdaUtils.getAttributeName(RoleNavigation::getRoleId);

    private static void collectRoleIdLiterals(Filters node, Set<Long> out) {
        if (node == null) return;
        FilterUnit unit = node.getFilterUnit();
        if (unit != null && Operator.EQUAL.equals(unit.getOperator())
                && ROLE_ID_FIELD.equals(unit.getField())
                && unit.getValue() instanceof Number n) {
            out.add(n.longValue());
        }
        if (node.getChildren() != null) {
            for (Filters child : node.getChildren()) collectRoleIdLiterals(child, out);
        }
    }

    /** Snapshot before delete — once rows are gone we can't recover roleIds. */
    private Set<Long> collectAffectedRoleIds(List<Long> rnIds) {
        if (rnIds == null || rnIds.isEmpty()) return Set.of();
        List<RoleNavigation> rows = searchList(new Filters().in(RoleNavigation::getId, rnIds));
        return collectRoleIds(rows);
    }

    private static Set<Long> collectRoleIds(List<RoleNavigation> rows) {
        if (rows == null || rows.isEmpty()) return Set.of();
        Set<Long> out = new HashSet<>(rows.size());
        for (RoleNavigation r : rows) if (r.getRoleId() != null) out.add(r.getRoleId());
        return out;
    }

    private void publish(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        Long tenantId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getTenantId();
        if (tenantId == null) {
            // Publisher without a bound tenant context.
            // Downstream evictByRole (see PermissionCacheInvalidatorImpl)
            // skips when tenantId is null → cache stays stale until 1h TTL.
            // Log loudly so operators can find the missing
            // ContextHolder.callWith(bootstrapCtx, ...) wrapper. Not a
            // throw — legitimate migration paths trigger this.
            log.warn("RoleNavigation change publisher — null tenantId; cache eviction "
                    + "will be skipped for roleIds={}. Wrap the caller in "
                    + "ContextHolder.callWith(bootstrapCtx, ...) to fix.", roleIds);
        }
        for (Long roleId : roleIds) {
            events.publishEvent(new RoleNavigationChangedEvent(tenantId, roleId));
        }
    }
}
