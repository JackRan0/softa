package io.softa.starter.user.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * Binding an admin role is a bypass handed out, so it is checked on every write path: the Super Admin
 * role never through a request, the Tenant Admin role only by someone who already has admin reach.
 */
class BuiltinRoleBindValidatorTest {

    private static final long SUPER_ADMIN = 1L;
    private static final long TENANT_ADMIN = 2L;
    private static final long ORDINARY = 3L;

    private BuiltinRoleBindValidator validator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ModelService<?> modelService = mock(ModelService.class);
        when(modelService.searchList(eq("Role"), any(FlexQuery.class))).thenAnswer(inv -> {
            String filters = inv.getArgument(1, FlexQuery.class).getFilters().toString();
            if (filters.contains(String.valueOf(SUPER_ADMIN))) return List.of(Map.of("id", SUPER_ADMIN, "code", "SUPER_ADMIN"));
            if (filters.contains(String.valueOf(TENANT_ADMIN))) return List.of(Map.of("id", TENANT_ADMIN, "code", "TENANT_ADMIN"));
            Map<String, Object> ordinary = new HashMap<>();
            ordinary.put("id", ORDINARY);
            ordinary.put("code", null);
            return List.of(ordinary);
        });
        validator = new BuiltinRoleBindValidator(modelService);
    }

    @Test
    void refusesBindingSuperAdminEvenForATenantAdmin() {
        WriteContext ctx = create(SUPER_ADMIN);

        as(Set.of("TENANT_ADMIN"), () -> validator.validateCreate(ctx));

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void refusesBindingTenantAdminForARoleManagerWithoutAdminReach() {
        WriteContext ctx = create(TENANT_ADMIN);

        as(Set.of(), () -> validator.validateCreate(ctx));

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void letsAnAdminBindTenantAdmin() {
        WriteContext byTenantAdmin = create(TENANT_ADMIN);
        WriteContext byConsultant = create(TENANT_ADMIN);

        as(Set.of("TENANT_ADMIN"), () -> validator.validateCreate(byTenantAdmin));
        as(Set.of("CONSULTANT"), () -> validator.validateCreate(byConsultant));

        assertThat(byTenantAdmin.hasErrors()).isFalse();
        assertThat(byConsultant.hasErrors()).isFalse();
    }

    @Test
    void letsAnyoneBindAnOrdinaryRole() {
        WriteContext ctx = create(ORDINARY);

        as(Set.of(), () -> validator.validateCreate(ctx));

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void refusesMovingARowOntoAnAdminRole() {
        WriteContext ctx = update(Map.of("roleId", TENANT_ADMIN), stored(ORDINARY, 50L));

        as(Set.of(), () -> validator.validateUpdate(ctx));

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void refusesHandingAnExistingAdminGrantToAnotherUser() {
        // The same grant, given to someone else: the role does not change, the holder does.
        WriteContext ctx = update(Map.of("userId", 99L), stored(TENANT_ADMIN, 50L));

        as(Set.of(), () -> validator.validateUpdate(ctx));

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void letsAnUpdateThatDoesNotMoveTheGrantThrough() {
        WriteContext ctx = update(Map.of("source", "MANUAL", "userId", "50"), stored(TENANT_ADMIN, 50L));

        as(Set.of(), () -> validator.validateUpdate(ctx));

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void letsSystemPathsBind() {
        // Provisioning binds the first tenant admin under skipPermissionCheck.
        Context system = new Context();
        system.setSkipPermissionCheck(true);
        WriteContext ctx = create(TENANT_ADMIN);

        ContextHolder.runWith(system, () -> validator.validateCreate(ctx));

        assertThat(ctx.hasErrors()).isFalse();
    }

    private static void as(Set<String> roleCodes, Runnable action) {
        Context context = new Context();
        context.setRoleCodes(roleCodes);
        ContextHolder.runWith(context, action);
    }

    private static WriteContext create(long roleId) {
        Map<String, Object> row = new HashMap<>();
        row.put("userId", 50L);
        row.put("roleId", roleId);
        return WriteContext.of("UserRoleRel", AccessType.CREATE, row, null);
    }

    private static WriteContext update(Map<String, Object> patch, Map<String, Object> original) {
        Map<String, Object> request = new HashMap<>(patch);
        request.put("id", 7L);
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(request);
        return WriteContext.of("UserRoleRel", AccessType.UPDATE, merged, request, original);
    }

    private static Map<String, Object> stored(long roleId, long userId) {
        return Map.of("id", 7L, "roleId", roleId, "userId", userId);
    }
}
