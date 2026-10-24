package io.softa.starter.tenant.service.impl;

import java.util.Optional;
import java.util.Set;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.tenant.config.TenantProvisioningProperties;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSeedProgress;
import io.softa.starter.tenant.enums.SeederStatus;
import io.softa.starter.tenant.enums.TenantStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A seeder's failure fails the setup only on the last delivery, and never fails a tenant that is no longer
 * setting up.
 *
 * <p>Failing it on the first attempt made the tenant DRAFT while MQ still had redeliveries to make, and DRAFT
 * is what Retry Setup accepts: a second round then ran beside the redelivery, both reading the same records to
 * decide which seed files were left. And a failure reported from a duplicate message sent a tenant that had
 * just become ACTIVE back to DRAFT, refusing its users at login.
 */
class TenantProvisioningStatusServiceTest {

    private static final Long TENANT = 7L;

    private final TenantInfoServiceImpl tenants = mock(TenantInfoServiceImpl.class);

    @SuppressWarnings("unchecked")
    private TenantProvisioningStatusService serviceWith(Integer maxRedeliver, TenantStatus current) {
        ObjectProvider<DeadLetterPolicy> policy = mock(ObjectProvider.class);
        when(policy.getIfAvailable()).thenReturn(maxRedeliver == null ? null
                : DeadLetterPolicy.builder().maxRedeliverCount(maxRedeliver).build());
        TenantProvisioningProperties props = new TenantProvisioningProperties();
        TenantInfo tenant = new TenantInfo();
        tenant.setId(TENANT);
        tenant.setStatus(current);
        when(tenants.getById(TENANT)).thenReturn(Optional.of(tenant));
        when(tenants.markSetupFailed(anyLong())).thenReturn(true);
        TenantProvisioningStatusService svc = Mockito.spy(new TenantProvisioningStatusService(tenants, props, policy));
        doReturn(Optional.empty()).when(svc).searchOne(any(Filters.class));
        doReturn(1L).when(svc).createOne(any(TenantSeedProgress.class));
        return svc;
    }

    @Test
    @DisplayName("while deliveries remain, the failure is recorded and the tenant stays Initializing")
    void anEarlierAttemptRecordsTheFailureOnly() {
        TenantProvisioningStatusService svc = serviceWith(3, TenantStatus.INITIALIZING);

        svc.markSeederFailed(TENANT, "pre-data", 0);
        svc.markSeederFailed(TENANT, "pre-data", 2);

        org.mockito.ArgumentCaptor<TenantSeedProgress> row = org.mockito.ArgumentCaptor.forClass(TenantSeedProgress.class);
        verify(svc, Mockito.atLeastOnce()).createOne(row.capture());
        assertEquals(SeederStatus.FAILED, row.getValue().getStatus());
        assertEquals("pre-data", row.getValue().getSeederKey());
        verify(tenants, never()).markSetupFailed(anyLong());
    }

    @Test
    @DisplayName("the last delivery's failure fails the setup")
    void theLastAttemptFailsTheSetup() {
        TenantProvisioningStatusService svc = serviceWith(3, TenantStatus.INITIALIZING);

        svc.markSeederFailed(TENANT, "corehr", 3);

        verify(tenants).markSetupFailed(TENANT);
    }

    @Test
    @DisplayName("without a redelivery cap there is no last delivery to wait for, so the first failure is final")
    void withoutACapEveryFailureIsFinal() {
        TenantProvisioningStatusService svc = serviceWith(null, TenantStatus.INITIALIZING);

        svc.markSeederFailed(TENANT, "pre-data", 0);

        verify(tenants).markSetupFailed(TENANT);
    }

    @Test
    @DisplayName("a failure reported for a tenant past setup comes from a duplicate and changes nothing")
    void aTenantPastSetupIsLeftAlone() {
        for (TenantStatus past : Set.of(TenantStatus.ACTIVE, TenantStatus.SUSPENDED, TenantStatus.CLOSED)) {
            Mockito.clearInvocations(tenants);
            TenantProvisioningStatusService svc = serviceWith(3, past);

            svc.markSeederFailed(TENANT, "pre-data", 3);
            svc.markSeederFailed(TENANT, "pre-data", 0);

            verify(tenants, never()).markSetupFailed(anyLong());
            verify(svc, never()).createOne(any(TenantSeedProgress.class));
            verify(svc, never()).updateOne(any(TenantSeedProgress.class));
        }
    }
}
