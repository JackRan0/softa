package io.softa.starter.user.service.impl;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.event.RoleGrantChangedEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link AbstractRoleGrantServiceImpl}, exercised through
 * the concrete {@link RoleDataScopeServiceImpl}. The base delegates persistence
 * to the inherited {@code EntityServiceImpl.modelService} (injected here as a
 * mock) and its own contribution is: publish exactly one
 * {@link RoleGrantChangedEvent} per DISTINCT touched roleId, carrying the tenant
 * from {@link ContextHolder}.
 *
 * <p>Covers: per-roleId coalescing, tenant propagation, the null-tenant warn
 * path, the deleteByFilters fast path (roleId pulled straight from the filter
 * AST — no read) vs slow path (fallback searchList), and the null/empty skips.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class AbstractRoleGrantServiceImplTest {

    private static final String MODEL = "RoleDataScope";
    private static final String ROLE_ID_FIELD = "roleId";

    private ApplicationEventPublisher events;
    private ModelService modelService;
    private RoleDataScopeServiceImpl service;

    @BeforeEach
    void setUp() {
        events = mock(ApplicationEventPublisher.class);
        modelService = mock(ModelService.class);
        service = new RoleDataScopeServiceImpl(events);
        // modelService is EntityServiceImpl's @Autowired field — inject the mock
        // so super.createList/deleteByFilters/searchList resolve against it.
        ReflectionTestUtils.setField(service, "modelService", modelService);
    }

    private static RoleDataScope scope(Long roleId) {
        RoleDataScope s = new RoleDataScope();
        s.setRoleId(roleId);
        return s;
    }

    private static void inTenant(Long tenantId, Runnable action) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ContextHolder.runWith(ctx, action);
    }

    // ─────────────────────────── createList ───────────────────────────

    @Test
    void createList_publishesOneEventPerDistinctRoleId_withTenant() {
        when(modelService.createList(eq(MODEL), anyList())).thenReturn(List.of(1L, 2L, 3L));

        inTenant(7L, () -> service.createList(List.of(scope(100L), scope(100L), scope(200L))));

        // Two DISTINCT roleIds → two events; the duplicate 100L is coalesced.
        verify(events).publishEvent(new RoleGrantChangedEvent(7L, 100L));
        verify(events).publishEvent(new RoleGrantChangedEvent(7L, 200L));
        verify(events, times(2)).publishEvent(any(RoleGrantChangedEvent.class));
    }

    @Test
    void createList_empty_publishesNothing() {
        when(modelService.createList(eq(MODEL), anyList())).thenReturn(List.of());

        inTenant(7L, () -> service.createList(List.of()));

        verifyNoInteractions(events);
    }

    @Test
    void createList_noBoundContext_publishesWithNullTenant() {
        when(modelService.createList(eq(MODEL), anyList())).thenReturn(List.of(1L));

        // No ContextHolder.runWith → getContext() yields a fresh Context (tenantId null).
        service.createList(List.of(scope(500L)));

        verify(events).publishEvent(new RoleGrantChangedEvent(null, 500L));
    }

    // ─────────────────────────── createOne ───────────────────────────

    @Test
    void createOne_nullRoleId_publishesNothing() {
        when(modelService.createOne(eq(MODEL), anyMap())).thenReturn(9L);

        inTenant(7L, () -> service.createOne(scope(null)));

        verifyNoInteractions(events);
    }

    // ───────────────────────── deleteByFilters ─────────────────────────

    @Test
    void deleteByFilters_fastPath_extractsRoleIdFromAst_noRead() {
        when(modelService.deleteByFilters(eq(MODEL), any(Filters.class))).thenReturn(true);

        Filters f = Filters.of(ROLE_ID_FIELD, Operator.EQUAL, 300L);
        inTenant(7L, () -> service.deleteByFilters(f));

        verify(events).publishEvent(new RoleGrantChangedEvent(7L, 300L));
        // Fast path must NOT fall back to a searchList read.
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class), any());
    }

    @Test
    void deleteByFilters_slowPath_fallsBackToSearchListWhenNoRoleIdLeaf() {
        // A filter with no `roleId = X` leaf → AST extraction misses → fallback read.
        when(modelService.searchList(eq(MODEL), any(FlexQuery.class), eq(RoleDataScope.class)))
                .thenReturn(List.of(scope(400L), scope(400L)));
        when(modelService.deleteByFilters(eq(MODEL), any(Filters.class))).thenReturn(true);

        Filters f = Filters.of("model", Operator.EQUAL, "Employee");
        inTenant(7L, () -> service.deleteByFilters(f));

        verify(modelService).searchList(eq(MODEL), any(FlexQuery.class), eq(RoleDataScope.class));
        // Resolved roleId (deduped) → single event.
        verify(events).publishEvent(new RoleGrantChangedEvent(7L, 400L));
        verify(events, times(1)).publishEvent(any(RoleGrantChangedEvent.class));
    }
}
