package io.softa.starter.seed.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.TenantInfoService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which tenants a run reaches.
 */
class TenantSeedPlannerTest {

    @Test
    void thePlatformTenantIsNeverRun() {
        // Planted active by the bootstrap script, so the tenant registry counts it as set up.
        TenantInfoService tenants = mock(TenantInfoService.class);
        when(tenants.getProvisionedTenantIds()).thenReturn(List.of(-1L, 7L, 8L));

        assertEquals(List.of(7L, 8L), planner(tenants).provisionedTenantIds());
    }

    @Test
    void withoutATenantRegistryThereIsNoTenantToRun() {
        assertEquals(List.of(), planner(null).provisionedTenantIds());
    }

    private static TenantSeedPlanner planner(TenantInfoService tenants) {
        return new TenantSeedPlanner(mock(SeedCatalog.class), provider(tenants),
                TenantSeedPlannerTest.<EntitlementService>provider(null), mock(ModelService.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
