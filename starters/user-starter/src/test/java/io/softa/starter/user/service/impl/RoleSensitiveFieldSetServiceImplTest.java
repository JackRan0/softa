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
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.event.RoleGrantChangedEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirms the SECOND concrete {@link AbstractRoleGrantServiceImpl} subclass
 * wires its own model + {@code roleId} accessor/field correctly (the base
 * behaviour itself is covered by {@link AbstractRoleGrantServiceImplTest} via
 * {@link RoleDataScopeServiceImpl}).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class RoleSensitiveFieldSetServiceImplTest {

    private static final String MODEL = "RoleSensitiveFieldSet";
    private static final String ROLE_ID_FIELD = "roleId";

    private ApplicationEventPublisher events;
    private ModelService modelService;
    private RoleSensitiveFieldSetServiceImpl service;

    @BeforeEach
    void setUp() {
        events = mock(ApplicationEventPublisher.class);
        modelService = mock(ModelService.class);
        service = new RoleSensitiveFieldSetServiceImpl(events);
        ReflectionTestUtils.setField(service, "modelService", modelService);
    }

    private static RoleSensitiveFieldSet grant(Long roleId) {
        RoleSensitiveFieldSet g = new RoleSensitiveFieldSet();
        g.setRoleId(roleId);
        return g;
    }

    private static void inTenant(Long tenantId, Runnable action) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ContextHolder.runWith(ctx, action);
    }

    @Test
    void createList_publishesPerDistinctRoleId() {
        when(modelService.createList(eq(MODEL), anyList())).thenReturn(List.of(1L, 2L));

        inTenant(3L, () -> service.createList(List.of(grant(11L), grant(11L), grant(22L))));

        verify(events).publishEvent(new RoleGrantChangedEvent(3L, 11L));
        verify(events).publishEvent(new RoleGrantChangedEvent(3L, 22L));
        verify(events, times(2)).publishEvent(any(RoleGrantChangedEvent.class));
    }

    @Test
    void deleteByFilters_fastPath_extractsRoleIdFromAst() {
        when(modelService.deleteByFilters(eq(MODEL), any(Filters.class))).thenReturn(true);

        inTenant(3L, () -> service.deleteByFilters(Filters.of(ROLE_ID_FIELD, Operator.EQUAL, 44L)));

        verify(events).publishEvent(new RoleGrantChangedEvent(3L, 44L));
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class), any());
    }
}
