package io.softa.framework.orm.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.SecurityException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tenant check on a multi-tenant write, given the tenantId a row arrives with.
 *
 * <p>The web sends every id as a string, so a form copied from an existing row carries the source's
 * tenantId as one. It used to be cast straight to {@code Long} and fail the write with a
 * ClassCastException; it is the same tenant, and the check has to read it as such.
 */
class ModelServiceTenantIdTest {

    private static final long TENANT = 885555992207564814L;

    private void check(Object tenantId) {
        Map<String, Object> row = new HashMap<>();
        row.put("label", "x");
        row.put(ModelConstant.TENANT_ID, tenantId);
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantControl("Item")).thenReturn(true);
            ContextHolder.runWith(ctx, () ->
                    ReflectionTestUtils.invokeMethod(new ModelServiceImpl<Long>(), "checkTenantId", "Item", List.of(row)));
        }
        // Whatever it arrived as, the caller's tenant is the one stamped; the row's own value is dropped.
        assertThat(row).doesNotContainKey(ModelConstant.TENANT_ID);
    }

    @Test
    void theCallersOwnTenantIdAsAStringIsAccepted() {
        check(String.valueOf(TENANT));
    }

    @Test
    void theCallersOwnTenantIdAsANumberIsAccepted() {
        check(TENANT);
    }

    @Test
    void anotherTenantIsRefusedWhicheverFormItArrivesIn() {
        assertThatThrownBy(() -> check("123")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> check(123L)).isInstanceOf(SecurityException.class);
    }

    @Test
    void aTenantIdThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> check("not-a-tenant")).isInstanceOf(SecurityException.class);
    }

    @Test
    void aBlankTenantIdIsIgnored() {
        check("");
        check(null);
    }
}
