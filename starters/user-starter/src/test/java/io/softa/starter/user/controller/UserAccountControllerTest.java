package io.softa.starter.user.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.user.service.PermissionCacheInvalidator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the roles-eviction shadow on {@link UserAccountController}. Editing the
 * {@code roles} ManyToMany via a UserAccount update cascades into
 * {@code user_role_rel} through the generic ORM write, which does NOT publish
 * {@code UserRoleRelChangedEvent} — so the controller must evict that user's
 * cached PermissionInfo itself, and ONLY when the payload actually touched
 * roles.
 *
 * <p>{@code IdUtils.formatMapId} is a static that consults model metadata (not
 * loaded in a unit test), so it is mocked to a no-op; the {@code @DataMask}
 * aspect is inert when the method is invoked directly (no Spring proxy).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class UserAccountControllerTest {

    private UserAccountController controller;
    private ModelService modelService;
    private PermissionCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        controller = new UserAccountController();
        modelService = mock(ModelService.class);
        invalidator = mock(PermissionCacheInvalidator.class);
        ReflectionTestUtils.setField(controller, "modelService", modelService);
        ReflectionTestUtils.setField(controller, "permissionCacheInvalidator", invalidator);
    }

    private static Map<String, Object> row(Object id, boolean withRoles) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("nickname", "Alice");
        if (withRoles) row.put("roles", List.of("r1", "r2"));
        return row;
    }

    @Test
    void updateOne_rolesChanged_evictsThatUser() {
        when(modelService.updateOne(eq("UserAccount"), any())).thenReturn(true);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOne(row(7L, true)));
        }

        verify(invalidator).evictBatch(9L, Set.of(7L));
    }

    @Test
    void updateOne_stringId_coercedAndEvicted() {
        when(modelService.updateOne(eq("UserAccount"), any())).thenReturn(true);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOne(row("7", true)));
        }

        verify(invalidator).evictBatch(9L, Set.of(7L));
    }

    @Test
    void updateOne_noRolesInPayload_doesNotEvict() {
        when(modelService.updateOne(eq("UserAccount"), any())).thenReturn(true);

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOne(row(7L, false)));
        }

        verify(invalidator, never()).evictBatch(any(), any());
    }

    @Test
    void updateOneAndFetch_rolesChanged_evictsThatUser() {
        when(modelService.updateOneAndFetch(eq("UserAccount"), any(), any()))
                .thenReturn(new HashMap<>());

        try (MockedStatic<IdUtils> ignored = Mockito.mockStatic(IdUtils.class)) {
            inTenant(9L, () -> controller.updateOneAndFetch(row(7L, true)));
        }

        verify(invalidator).evictBatch(9L, Set.of(7L));
    }

    private static void inTenant(Long tenantId, Runnable action) {
        Context ctx = new Context();
        ctx.setTenantId(tenantId);
        ContextHolder.runWith(ctx, action);
    }
}
