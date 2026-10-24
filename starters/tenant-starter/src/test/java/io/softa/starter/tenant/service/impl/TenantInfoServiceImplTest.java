package io.softa.starter.tenant.service.impl;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.enums.TenantStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tenant lifecycle gate primitives: {@link TenantInfoServiceImpl#isTenantActive} and the
 * {@link TenantInfoServiceImpl#deactivate} choke point (Model 1 — login + per-request gate).
 */
class TenantInfoServiceImplTest {

    private static TenantInfo tenant(TenantStatus status) {
        TenantInfo t = new TenantInfo();
        t.setId(1L);
        t.setStatus(status);
        return t;
    }

    @Test
    void isTenantActive_trueOnlyForActiveTenant() {
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        doReturn(tenant(TenantStatus.ACTIVE)).when(svc).getTenantInfo(1L);
        doReturn(tenant(TenantStatus.SUSPENDED)).when(svc).getTenantInfo(2L);
        doReturn(null).when(svc).getTenantInfo(3L);

        assertTrue(svc.isTenantActive(1L), "ACTIVE tenant is active");
        assertFalse(svc.isTenantActive(2L), "SUSPENDED tenant is not active");
        assertFalse(svc.isTenantActive(3L), "missing tenant is not active");
        assertFalse(svc.isTenantActive(null), "null tenantId is not active");
    }

    @Test
    void deactivate_suspendsTenantAndEvictsCaches() {
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        CacheService cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(svc, "cacheService", cacheService);

        TenantInfo tenant = tenant(TenantStatus.ACTIVE);
        doReturn(Optional.of(tenant)).when(svc).getById(1L);
        doReturn(true).when(svc).updateOne(any(TenantInfo.class));

        svc.deactivate(1L);

        assertEquals(TenantStatus.SUSPENDED, tenant.getStatus(), "deactivate sets status to SUSPENDED");
        assertNotNull(tenant.getSuspendedTime(), "deactivate stamps suspendedTime");
        verify(svc).updateOne(tenant);
        // Both tenant caches evicted so isTenantActive() / active-id filtering flip immediately.
        verify(cacheService).clear(RedisConstant.TENANT_INFO + 1L);
        verify(cacheService).clear(RedisConstant.TENANT_IDS);
    }

    // ─── the other two transitions on the same choke point ───

    @Test
    @DisplayName("activate restores a suspended tenant and clears the suspension stamp")
    void activate_reinstatesSuspendedTenantAndClearsStamp() {
        // Exactly one of the three timestamps is ever set, which keeps the trio a function of the status: an
        // ACTIVE tenant that still displayed a suspended time would read as suspended to anyone scanning the
        // list.
        TenantInfo tenant = tenant(TenantStatus.SUSPENDED);
        tenant.setSuspendedTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        TenantInfoServiceImpl svc = serviceFor(tenant);

        svc.activate(1L);

        assertEquals(TenantStatus.ACTIVE, tenant.getStatus());
        assertNotNull(tenant.getActivatedTime(), "activate stamps activatedTime");
        assertNull(tenant.getSuspendedTime(), "the stale suspension stamp has to be cleared");
    }

    @Test
    @DisplayName("close is reachable from both active and suspended")
    void close_reachableFromActiveAndSuspended() {
        // A customer can be shut down directly, or after a suspension that was never lifted. Allowing only
        // one of those would leave suspended tenants permanently unclosable.
        TenantInfo fromActive = tenant(TenantStatus.ACTIVE);
        serviceFor(fromActive).close(1L);
        assertEquals(TenantStatus.CLOSED, fromActive.getStatus());
        assertNotNull(fromActive.getClosedTime());

        TenantInfo fromSuspended = tenant(TenantStatus.SUSPENDED);
        serviceFor(fromSuspended).close(1L);
        assertEquals(TenantStatus.CLOSED, fromSuspended.getStatus());
        assertNull(fromSuspended.getSuspendedTime(), "only the closed stamp survives");
    }

    @Test
    @DisplayName("re-running a transition already in effect writes nothing")
    void transitionToCurrentStatus_isANoOp() {
        // These are reachable from an MQ consumer and from ops double-clicking. A no-op rather than an error:
        // the caller asked for a state the tenant is already in, and got it.
        TenantInfo tenant = tenant(TenantStatus.ACTIVE);
        TenantInfoServiceImpl svc = serviceFor(tenant);

        svc.activate(1L);

        verify(svc, never()).updateOne(any(TenantInfo.class));
        verify(cacheService, never()).clear(anyString());
    }

    @Test
    @DisplayName("the platform tenant's status cannot be changed by any of the three")
    void platformTenant_isNotTransitionable() {
        // Suspending or closing it locks the platform admins out permanently — they could not log in to undo
        // it, and there is no other path back. Checked before the row is even loaded.
        TenantInfoServiceImpl svc = serviceFor(tenant(TenantStatus.ACTIVE));

        assertThrows(IllegalArgumentException.class, () -> svc.deactivate(PLATFORM_TENANT_ID));
        assertThrows(IllegalArgumentException.class, () -> svc.close(PLATFORM_TENANT_ID));
        assertThrows(IllegalArgumentException.class, () -> svc.activate(PLATFORM_TENANT_ID));
    }

    /**
     * The tenant the platform itself runs under. Referenced, not mirrored: this test used to copy the
     * impl's private constant by hand, and hand-copying is what let the platform tier and the platform
     * tenant sit on two different ids for as long as they did.
     */
    private static final Long PLATFORM_TENANT_ID = BaseConstant.PLATFORM_TENANT_ID;

    private CacheService cacheService;

    /** A service whose {@code getById(1L)} returns this tenant, with the write and the cache stubbed out. */
    private TenantInfoServiceImpl serviceFor(TenantInfo tenant) {
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(svc, "cacheService", cacheService);
        doReturn(Optional.of(tenant)).when(svc).getById(1L);
        doReturn(true).when(svc).updateOne(any(TenantInfo.class));
        return svc;
    }

    // ─── built-vs-usable, now both on one field ───

    @Test
    @DisplayName("a tenant still being set up, or never set up, is not built")
    void notYetBuilt() {
        // Login has to refuse these, and the "discard the output and set it up again" remedy depends on no
        // human having written anything before the tenant went ACTIVE. DRAFT covers both "never started" and
        // "the last attempt failed" — the timeout guard sends a stalled tenant back here.
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        doReturn(withStatus(TenantStatus.INITIALIZING)).when(svc).getTenantInfo(1L);
        doReturn(withStatus(TenantStatus.DRAFT)).when(svc).getTenantInfo(2L);

        assertFalse(svc.isTenantProvisioned(1L), "mid setup");
        assertFalse(svc.isTenantProvisioned(2L), "not set up, or setup failed");
    }

    @Test
    @DisplayName("built is not the same question as usable — a suspended tenant is built")
    void builtButNotUsable() {
        // This is why one field still answers two methods rather than one. Creating a tenant's first admin
        // needs "has this workspace been built"; folding in "and may it be used" would refuse a suspended
        // tenant, which is fully built and whose admin may legitimately need repairing before reinstatement.
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        doReturn(withStatus(TenantStatus.SUSPENDED)).when(svc).getTenantInfo(1L);

        assertTrue(svc.isTenantProvisioned(1L), "suspended is built");
        assertFalse(svc.isTenantActive(1L), "but not usable");
    }

    @Test
    @DisplayName("ACTIVE, and a null status, both count as built")
    void activeOrUnset_built() {
        // Null is the important half: a row written before this axis existed has no status, and reading that
        // as "not built" would refuse every pre-existing customer on the deploy that introduced it.
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        doReturn(withStatus(TenantStatus.ACTIVE)).when(svc).getTenantInfo(1L);
        doReturn(withStatus(null)).when(svc).getTenantInfo(2L);
        doReturn(null).when(svc).getTenantInfo(3L);

        assertTrue(svc.isTenantProvisioned(1L));
        assertTrue(svc.isTenantProvisioned(2L), "a tenant predating the axis must not be refused");
        assertFalse(svc.isTenantProvisioned(3L), "a missing tenant is not built");
        assertFalse(svc.isTenantProvisioned(null));
    }

    @Test
    @DisplayName("a closed tenant can be reinstated — closing keeps the data")
    void activate_reachableFromClosed() {
        // Replaces the former "CLOSED is terminal" rule. Close changes the status and leaves every row in
        // place, so refusing to reverse it would strand a recoverable workspace behind "create a new tenant".
        TenantInfo closed = tenant(TenantStatus.CLOSED);
        closed.setClosedTime(LocalDateTime.of(2026, 7, 1, 9, 0));
        TenantInfoServiceImpl svc = serviceFor(closed);

        svc.activate(1L);

        assertEquals(TenantStatus.ACTIVE, closed.getStatus());
        assertNotNull(closed.getActivatedTime());
    }

    // ─── markStatus: the setup-driven write path ───

    @Test
    @DisplayName("setup completion does not reopen a closed tenant")
    void markStatus_leavesClosedAlone() {
        // The seed messages are at-least-once, so a completion redelivered after an operator closed the
        // tenant is ordinary. Both concerns share one field now, so without this the duplicate would move the
        // tenant back to ACTIVE and let its users log in again — silently, since nothing failed.
        TenantInfo closed = tenant(TenantStatus.CLOSED);
        TenantInfoServiceImpl svc = serviceFor(closed);

        svc.markStatus(1L, TenantStatus.ACTIVE);

        assertEquals(TenantStatus.CLOSED, closed.getStatus(), "an operator's decision outranks setup");
        verify(svc, never()).updateOne(any(TenantInfo.class));
    }

    @Test
    @DisplayName("setup completion does not reopen a suspended tenant either")
    void markStatus_leavesSuspendedAlone() {
        TenantInfo suspended = tenant(TenantStatus.SUSPENDED);
        TenantInfoServiceImpl svc = serviceFor(suspended);

        svc.markStatus(1L, TenantStatus.ACTIVE);

        assertEquals(TenantStatus.SUSPENDED, suspended.getStatus());
    }

    @Test
    @DisplayName("reaching ACTIVE through setup stamps activatedTime, same as an operator activation")
    void markStatus_stampsLikeTheOperatorPath() {
        // The three timestamps are meant to be a function of the status. When only transitionTo maintained
        // them, a tenant that became ACTIVE by finishing its setup carried no activatedTime while one an
        // operator activated did — the same state in two shapes, and a list column that was blank for most
        // tenants.
        TenantInfo building = tenant(TenantStatus.INITIALIZING);
        TenantInfoServiceImpl svc = serviceFor(building);

        svc.markStatus(1L, TenantStatus.ACTIVE);

        assertEquals(TenantStatus.ACTIVE, building.getStatus());
        assertNotNull(building.getActivatedTime(), "setup-driven activation is stamped too");
        assertNull(building.getSuspendedTime());
        assertNull(building.getClosedTime());
    }

    @Test
    @DisplayName("markStatus refuses the platform tenant, like transitionTo does")
    void markStatus_platformTenantRefused() {
        // Moving the platform tenant out of ACTIVE has no way back: the admins who would move it back log in
        // through it. transitionTo has always refused this; the setup path has to refuse it as well now that
        // it writes the same field.
        TenantInfoServiceImpl svc = serviceFor(tenant(TenantStatus.ACTIVE));

        assertThrows(IllegalArgumentException.class,
                () -> svc.markStatus(PLATFORM_TENANT_ID, TenantStatus.DRAFT));
    }

    private static TenantInfo withStatus(TenantStatus status) {
        TenantInfo t = new TenantInfo();
        t.setId(1L);
        t.setStatus(status);
        return t;
    }

    // ─── a failed setup: INITIALIZING to DRAFT, and from nowhere else ───

    @Test
    @DisplayName("a failed setup moves only a tenant still setting up, in one conditional update")
    void markSetupFailed_movesOnlyAnInitializingTenant() {
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        CacheService cache = mock(CacheService.class);
        ReflectionTestUtils.setField(svc, "cacheService", cache);
        org.mockito.ArgumentCaptor<io.softa.framework.orm.domain.Filters> filters =
                org.mockito.ArgumentCaptor.forClass(io.softa.framework.orm.domain.Filters.class);
        org.mockito.ArgumentCaptor<TenantInfo> patch = org.mockito.ArgumentCaptor.forClass(TenantInfo.class);
        doReturn(1).when(svc).updateByFilter(filters.capture(), patch.capture());

        assertTrue(svc.markSetupFailed(1L));

        // The condition is the whole point: the status is checked by the update itself, so a tenant that went
        // ACTIVE between a failure and its report is not matched and stays where it is.
        assertTrue(filters.getValue().toString().contains("Initializing"), filters.getValue().toString());
        assertEquals(TenantStatus.DRAFT, patch.getValue().getStatus());
        verify(cache).clear(RedisConstant.TENANT_INFO + 1L);
        verify(cache).clear(RedisConstant.TENANT_IDS);
    }

    @Test
    @DisplayName("a tenant no longer setting up is left as it is, its caches too")
    void markSetupFailed_leavesATenantPastSetup() {
        TenantInfoServiceImpl svc = Mockito.spy(new TenantInfoServiceImpl());
        CacheService cache = mock(CacheService.class);
        ReflectionTestUtils.setField(svc, "cacheService", cache);
        doReturn(0).when(svc).updateByFilter(any(io.softa.framework.orm.domain.Filters.class), any(TenantInfo.class));

        assertFalse(svc.markSetupFailed(1L));

        verify(svc, never()).updateOne(any(TenantInfo.class));
        verify(cache, never()).clear(anyString());
        assertThrows(IllegalArgumentException.class, () -> svc.markSetupFailed(BaseConstant.PLATFORM_TENANT_ID));
    }
}
