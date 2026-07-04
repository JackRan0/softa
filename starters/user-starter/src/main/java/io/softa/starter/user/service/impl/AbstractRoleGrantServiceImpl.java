package io.softa.starter.user.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.event.RoleGrantChangedEvent;

/**
 * Shared base for the two role data-dimension grant services
 * ({@code RoleDataScopeService}, {@code RoleSensitiveFieldSetService}).
 *
 * <p>Every write publishes a {@link RoleGrantChangedEvent} for the affected
 * role(s) so {@code PermissionCacheInvalidator} evicts the PermissionInfo of
 * every user holding the role. Wizard saves wipe-and-rewrite all rows for a
 * role; events are coalesced per touched roleId (via a Set) so the listener
 * doesn't N+1 evict. Mirrors {@code RoleNavigationServiceImpl}, generalised so
 * both grant tables share one implementation.
 *
 * @param <T> the concrete grant entity (carries a {@code roleId})
 */
@Slf4j
public abstract class AbstractRoleGrantServiceImpl<T extends AbstractModel>
        extends EntityServiceImpl<T, Long> {

    protected final ApplicationEventPublisher events;

    protected AbstractRoleGrantServiceImpl(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** The {@code roleId} carried by a concrete entity (null-safe). */
    protected abstract Long roleIdOf(T entity);

    /** Attribute name of the {@code roleId} field, for {@link Filters} AST walking. */
    protected abstract String roleIdField();

    @Override
    public Long createOne(T entity) {
        Long id = super.createOne(entity);
        if (entity != null && roleIdOf(entity) != null) publish(Set.of(roleIdOf(entity)));
        return id;
    }

    @Override
    public List<Long> createList(List<T> entities) {
        List<Long> ids = super.createList(entities);
        publish(collectRoleIds(entities));
        return ids;
    }

    @Override
    public boolean updateOne(T entity) {
        boolean ok = super.updateOne(entity);
        if (ok && entity != null && roleIdOf(entity) != null) publish(Set.of(roleIdOf(entity)));
        return ok;
    }

    @Override
    public boolean updateOne(T entity, boolean ignoreNull) {
        boolean ok = super.updateOne(entity, ignoreNull);
        if (ok && entity != null && roleIdOf(entity) != null) publish(Set.of(roleIdOf(entity)));
        return ok;
    }

    @Override
    public boolean updateList(List<T> entities) {
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
        // Fast path: the wizard always passes a `roleId = X` leaf — pull it
        // straight from the AST so we can emit the event without an extra
        // read. Slow path (composite filters from ops scripts / tests) falls
        // back to resolving via searchList.
        Set<Long> roleIds = roleIdsFromFilters(filters);
        if (roleIds.isEmpty()) {
            log.debug("{}.deleteByFilters — could not extract roleId from filter AST; "
                    + "falling back to searchList (slow path). filters={}",
                    getClass().getSimpleName(), filters);
            roleIds = collectRoleIds(searchList(filters));
        }
        boolean ok = super.deleteByFilters(filters);
        publish(roleIds);
        return ok;
    }

    // ─────────────────────── helpers ───────────────────────

    private Set<Long> roleIdsFromFilters(Filters filters) {
        Set<Long> out = new HashSet<>();
        collectRoleIdLiterals(filters, out);
        return out;
    }

    private void collectRoleIdLiterals(Filters node, Set<Long> out) {
        if (node == null) return;
        FilterUnit unit = node.getFilterUnit();
        if (unit != null && Operator.EQUAL.equals(unit.getOperator())
                && roleIdField().equals(unit.getField())
                && unit.getValue() instanceof Number n) {
            out.add(n.longValue());
        }
        if (node.getChildren() != null) {
            for (Filters child : node.getChildren()) collectRoleIdLiterals(child, out);
        }
    }

    /** Snapshot roleIds before delete — once rows are gone we can't recover them. */
    private Set<Long> collectAffectedRoleIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        return collectRoleIds(searchList(new Filters().in(ModelConstant.ID, ids)));
    }

    private Set<Long> collectRoleIds(List<T> rows) {
        if (rows == null || rows.isEmpty()) return Set.of();
        Set<Long> out = new HashSet<>(rows.size());
        for (T r : rows) {
            Long rid = roleIdOf(r);
            if (rid != null) out.add(rid);
        }
        return out;
    }

    private void publish(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        Long tenantId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getTenantId();
        if (tenantId == null) {
            // Downstream evictByRole skips when tenantId is null → cache stays
            // stale until the 1h TTL. Log loudly so the missing
            // ContextHolder.callWith(bootstrapCtx, ...) wrapper is findable.
            log.warn("Role grant change publisher — null tenantId; cache eviction will be "
                    + "skipped for roleIds={}. Wrap the caller in ContextHolder.callWith(...).", roleIds);
        }
        for (Long roleId : roleIds) {
            if (roleId != null) events.publishEvent(new RoleGrantChangedEvent(tenantId, roleId));
        }
    }
}
