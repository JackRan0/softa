package io.softa.starter.seed.service.impl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers which scope a seed binding is written to, and which scope a reference's preId is resolved
 * in — both derived from the model addressed, never from the load.
 *
 * <p>The derivation is invisible at runtime until it is wrong, and then it lies. Resolving in the
 * loading scope instead of the referenced model's scope leaves a tenant seed unable to find a shared
 * model's binding, and the error it produces — "preIDs … do not exist" — names data that is in fact
 * present, so it reads as a broken seed file rather than a broken lookup. The one unresolvable
 * combination is covered here for the same reason: it must fail saying what actually has no answer.
 */
class SysPreDataScopeTest {

    private static final Long TENANT = 7L;

    private final SysPreDataServiceImpl service = new SysPreDataServiceImpl(mock(ModelService.class));

    private static boolean previousMultiTenancy;

    @BeforeAll
    static void ensureSystemConfig() {
        // Framework IllegalArgumentException construction reaches I18n via BaseException, which
        // requires SystemConfig.env to be non-null. Raw unit tests must seed it.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        // Restored afterwards: the flag is global and the surefire JVM is shared with tests that
        // read it, so leaving it flipped would make their outcome depend on class ordering.
        previousMultiTenancy = SystemConfig.env.isEnableMultiTenancy();
        SystemConfig.env.setEnableMultiTenancy(true);
    }

    @AfterAll
    static void restoreSystemConfig() {
        SystemConfig.env.setEnableMultiTenancy(previousMultiTenancy);
    }

    // ---- binding scope ---------------------------------------------------

    @Test
    void aSharedModelBindsAtSystemScopeEvenUnderATenantLoad() {
        // The case the previous "scope = the load's tenant" rule got wrong: a tenant seed
        // referencing a currency has to look under tenant_id IS NULL, because that is where the
        // one globally visible currency binding lives — its own context tenant is irrelevant.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantModel("Currency")).thenReturn(false);
            inTenant(TENANT, () -> assertNull(service.bindingScopeOf("Currency")));
        }
    }

    @Test
    void aMultiTenantModelBindsInTheLoadingTenantScope() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantModel("Role")).thenReturn(true);
            inTenant(TENANT, () -> assertEquals(TENANT, service.bindingScopeOf("Role")));
        }
    }

    @Test
    void withMultiTenancyDisabledEverythingIsSystemScope() {
        // isMultiTenantModel already folds the switch in, so nothing here depends on the context
        // carrying a tenant — a single-tenant deployment has one scope and it is the system one.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantModel("Role")).thenReturn(false);
            inTenant(TENANT, () -> assertNull(service.bindingScopeOf("Role")));
        }
    }

    // ---- reference direction ---------------------------------------------

    @Test
    void aTenantSeedResolvesASharedModelsPreIdAtSystemScope() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantModel("Currency")).thenReturn(false);
            inTenant(TENANT, () -> assertNull(service.referenceScopeOf("Currency")));
        }
    }

    @Test
    void aTenantSeedResolvesAMultiTenantModelsPreIdInItsOwnTenant() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantModel("Role")).thenReturn(true);
            inTenant(TENANT, () -> assertEquals(TENANT, service.referenceScopeOf("Role")));
        }
    }

    @Test
    void aSystemSeedResolvesASharedModelsPreIdAtSystemScope() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantModel("Navigation")).thenReturn(false);
            inTenant(null, () -> assertNull(service.referenceScopeOf("Navigation")));
        }
    }

    @Test
    void aSystemSeedCannotResolveAMultiTenantModelsPreId() {
        // Not a policy about who may reference whom — a shared platform-side table pointing at a
        // tenant's row is legitimate, and by actual id it loads fine. What has no answer is the preId:
        // those bindings exist once per tenant and a system-scope load has no tenant to pick.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.isMultiTenantModel("Role")).thenReturn(true);
            inTenant(null, () -> {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> service.referenceScopeOf("Role"));
                assertTrue(e.getMessage().contains("Role"), e.getMessage());
                assertTrue(e.getMessage().contains("actual id"), e.getMessage());
            });
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static void inTenant(Long tenantId, Runnable action) {
        Context context = ContextHolder.cloneContext();
        context.setTenantId(tenantId);
        ContextHolder.runWith(context, action);
    }

}
