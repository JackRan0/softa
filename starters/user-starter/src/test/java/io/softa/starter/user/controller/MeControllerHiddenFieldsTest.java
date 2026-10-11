package io.softa.starter.user.controller;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.user.dto.UiContext;
import io.softa.starter.user.service.impl.UiContextBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /me/uiContext} carries the sensitive fields the caller can never see, asked of the
 * permission engine per request — never the set catalogue behind them.
 */
class MeControllerHiddenFieldsTest {

    @SuppressWarnings("unchecked")
    private static UiContext uiContext(PermissionService permissionService) {
        CacheService cache = mock(CacheService.class);
        UiContext cached = new UiContext();
        cached.setRoleCodes(Set.of("HR"));
        when(cache.get(anyString(), any(Class.class))).thenReturn(cached);
        ObjectProvider<EntitlementService> entitlement = mock(ObjectProvider.class);
        ObjectProvider<PermissionService> permission = mock(ObjectProvider.class);
        when(permission.getIfAvailable()).thenReturn(permissionService);
        MeController controller = new MeController(cache, mock(UiContextBuilder.class), entitlement, permission);
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(2L);
        return ContextHolder.callWith(ctx, () -> controller.uiContext().getData());
    }

    @Test
    void theHiddenFieldsComeFromThePermissionEngine() {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getHiddenSensitiveFields()).thenReturn(Map.of("Employee", Set.of("salary")));

        UiContext ui = uiContext(permissionService);

        assertThat(ui.getModelHiddenFieldsMap()).containsExactly(Map.entry("Employee", Set.of("salary")));
    }

    @Test
    void withoutAPermissionEngineNothingIsHidden() {
        assertThat(uiContext(null).getModelHiddenFieldsMap()).isEmpty();
    }
}
