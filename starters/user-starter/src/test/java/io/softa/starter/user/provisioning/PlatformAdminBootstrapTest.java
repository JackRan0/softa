package io.softa.starter.user.provisioning;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.config.PlatformAdminProperties;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.UserRoleSource;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserRoleRelService;

import static io.softa.framework.base.constant.BaseConstant.PLATFORM_TENANT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The first platform super-administrator, made from configuration at startup. What matters most is what it
 * refuses to touch: an existing admin's password, and a person who already signs in with the address.
 */
class PlatformAdminBootstrapTest {

    private static final String EMAIL = "ops@example.com";
    private static final String PASSWORD = "Str0ng-Bootstrap";

    private SystemConfig previousConfig;
    private PlatformAdminProperties properties;
    private UserAccountService accountService;
    private UserIdentityService identityService;
    private RoleService roleService;
    private UserRoleRelService userRoleRelService;
    private ModelService<Long> modelService;
    private TenantInfoService tenantInfoService;
    private PlatformAdminBootstrap bootstrap;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousConfig = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;

        properties = new PlatformAdminProperties();
        properties.setEnabled(true);
        properties.setEmail(EMAIL);
        properties.setPassword(PASSWORD);

        accountService = mock(UserAccountService.class);
        identityService = mock(UserIdentityService.class);
        roleService = mock(RoleService.class);
        userRoleRelService = mock(UserRoleRelService.class);
        modelService = mock(ModelService.class);
        tenantInfoService = mock(TenantInfoService.class);
        when(tenantInfoService.isTenantActive(PLATFORM_TENANT_ID)).thenReturn(true);

        // A real template over a manager that does nothing: the code runs inside execute exactly as in
        // production, and a throw from inside comes back out the same way.
        TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));
        bootstrap = new PlatformAdminBootstrap(properties, accountService, identityService, roleService,
                userRoleRelService, modelService, transactions);
        ReflectionTestUtils.setField(bootstrap, "tenantInfoService", tenantInfoService);
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = previousConfig;
    }

    @Test
    void doesNothingUnlessEnabled() {
        properties.setEnabled(false);

        bootstrap.onApplicationReady();

        verifyNoInteractions(accountService, identityService, roleService, userRoleRelService, modelService,
                tenantInfoService);
    }

    @Test
    void anEnabledBootstrapWithoutAPasswordFailsTheBoot() {
        // No weak fallback: an operator who switched this on must not end up with a guessable admin.
        properties.setPassword(" ");

        IllegalStateException e = assertThrows(IllegalStateException.class, bootstrap::onApplicationReady);
        assertTrue(e.getMessage().contains("softa.user.platform-admin.password"), e.getMessage());
        verifyNoInteractions(accountService);
    }

    @Test
    void createsTheAdminActiveWithTheConfiguredPasswordAndTheSuperAdminRole() {
        when(accountService.exist(any(Filters.class))).thenReturn(false);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());
        when(roleService.searchOne(any(Filters.class))).thenReturn(Optional.empty());
        when(modelService.createOne(eq("Role"), anyMap())).thenReturn(77L);
        UserAccount account = new UserAccount();
        account.setId(5L);
        account.setStatus(AccountStatus.PENDING);
        when(accountService.registerInvitedUser(EMAIL, null, null)).thenReturn(userInfo(5L));
        when(accountService.getById(5L)).thenReturn(Optional.of(account));
        // Everything below must happen inside the platform tenant, which is where the ORM stamps the rows.
        doAnswer(inv -> {
            assertEquals(PLATFORM_TENANT_ID, ContextHolder.getContext().getTenantId());
            assertTrue(ContextHolder.getContext().isSkipPermissionCheck(),
                    "the SUPER_ADMIN role can only be written on a system path");
            return null;
        }).when(identityService).setPassword(any(UserAccount.class), anyString());

        bootstrap.onApplicationReady();

        verify(tenantInfoService).ensurePlatformTenant();
        ArgumentCaptor<Map<String, Object>> role = ArgumentCaptor.forClass(Map.class);
        verify(modelService).createOne(eq("Role"), role.capture());
        assertEquals(RoleConstant.CODE_SUPER_ADMIN, role.getValue().get("code"));
        verify(identityService).setPassword(account, PASSWORD);
        assertEquals(AccountStatus.ACTIVE, account.getStatus(), "no mail exists yet to activate it the usual way");
        assertNotNull(account.getActivationTime());
        verify(accountService).updateOne(account);
        Map<String, Object> grant = grant();
        assertEquals(5L, grant.get("userId"));
        assertEquals(77L, grant.get("roleId"));
        assertEquals(UserRoleSource.MANUAL, grant.get("source"), "or the dynamic role sync revokes it");
    }

    @Test
    void reusesAnExistingSuperAdminRole() {
        when(accountService.exist(any(Filters.class))).thenReturn(false);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());
        Role existing = new Role();
        existing.setId(9L);
        when(roleService.searchOne(any(Filters.class))).thenReturn(Optional.of(existing));
        UserAccount account = new UserAccount();
        account.setId(5L);
        when(accountService.registerInvitedUser(EMAIL, null, null)).thenReturn(userInfo(5L));
        when(accountService.getById(5L)).thenReturn(Optional.of(account));

        bootstrap.onApplicationReady();

        verify(modelService, never()).createOne(eq("Role"), anyMap());
        assertEquals(9L, grant().get("roleId"));
    }

    @Test
    void doesNothingOnceThePlatformHasASuperAdminWhateverTheConfiguredAddress() {
        // The configured address has no account, but someone already holds SUPER_ADMIN: changing an
        // environment variable must not mint a second account that bypasses every permission check.
        when(roleService.searchOne(any(Filters.class))).thenReturn(Optional.of(superAdminRole()));
        when(userRoleRelService.exist(any(Filters.class))).thenReturn(true);
        when(accountService.exist(any(Filters.class))).thenReturn(false);

        bootstrap.onApplicationReady();

        verify(accountService, never()).registerInvitedUser(anyString(), any(), any());
        verify(identityService, never()).setPassword(any(UserAccount.class), anyString());
        verifyNoInteractions(modelService);
    }

    @Test
    void aPlatformLeftWithNoSuperAdminGetsOneBack() {
        // The role exists but nobody holds it any more: the bootstrap is the way back in.
        when(roleService.searchOne(any(Filters.class))).thenReturn(Optional.of(superAdminRole()));
        when(userRoleRelService.exist(any(Filters.class))).thenReturn(false);
        when(accountService.exist(any(Filters.class))).thenReturn(false);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());
        UserAccount account = new UserAccount();
        account.setId(5L);
        when(accountService.registerInvitedUser(EMAIL, null, null)).thenReturn(userInfo(5L));
        when(accountService.getById(5L)).thenReturn(Optional.of(account));

        bootstrap.onApplicationReady();

        assertEquals(9L, grant().get("roleId"));
        verify(modelService, never()).createOne(eq("Role"), anyMap());
    }

    @Test
    void leavesAnExistingAdminAndItsPasswordAlone() {
        // Otherwise this configuration would reset the super-administrator's password on every restart.
        when(accountService.exist(any(Filters.class))).thenReturn(true);

        bootstrap.onApplicationReady();

        verify(accountService, never()).registerInvitedUser(anyString(), any(), any());
        verify(identityService, never()).setPassword(any(UserAccount.class), anyString());
        verifyNoInteractions(userRoleRelService, modelService);
    }

    @Test
    void refusesAnAddressThatAlreadySignsAPersonIn() {
        // Credentials belong to the person: linking an account to them and setting the configured password
        // would change their password in every tenant they belong to.
        when(accountService.exist(any(Filters.class))).thenReturn(false);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(new UserIdentity()));

        bootstrap.onApplicationReady();

        verify(accountService, never()).registerInvitedUser(anyString(), any(), any());
        verify(identityService, never()).setPassword(any(UserAccount.class), anyString());
        verifyNoInteractions(userRoleRelService, modelService);
    }

    @Test
    void anotherInstanceCreatingTheAdminFirstIsNotAFailure() {
        when(accountService.exist(any(Filters.class))).thenReturn(false, true);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());
        when(roleService.searchOne(any(Filters.class))).thenReturn(Optional.of(superAdminRole()));
        when(accountService.registerInvitedUser(EMAIL, null, null))
                .thenThrow(new IllegalStateException("duplicate key value violates unique constraint"));

        bootstrap.onApplicationReady();   // does not throw: the admin exists now

        // The swallowed failure is the one from registering the account, not something earlier.
        verify(accountService).registerInvitedUser(EMAIL, null, null);
        verify(modelService, never()).createOne(anyString(), anyMap());
    }

    @Test
    void aRealFailureStillStopsTheBoot() {
        when(accountService.exist(any(Filters.class))).thenReturn(false);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());
        when(roleService.searchOne(any(Filters.class))).thenReturn(Optional.of(superAdminRole()));
        UserAccount account = new UserAccount();
        account.setId(5L);
        when(accountService.registerInvitedUser(EMAIL, null, null)).thenReturn(userInfo(5L));
        when(accountService.getById(5L)).thenReturn(Optional.of(account));
        IllegalStateException weak = new IllegalStateException("Password does not meet the requirements");
        doAnswer(inv -> { throw weak; }).when(identityService).setPassword(any(UserAccount.class), anyString());

        assertEquals(weak, assertThrows(IllegalStateException.class, bootstrap::onApplicationReady));
    }

    @Test
    void aSingleTenantDeploymentHasNoPlatformTenantToCreate() {
        SystemConfig.env.setEnableMultiTenancy(false);
        when(accountService.exist(any(Filters.class))).thenReturn(true);

        bootstrap.onApplicationReady();

        verify(tenantInfoService, never()).ensurePlatformTenant();
    }

    @Test
    void multiTenancyWithoutTheTenantModelIsAConfigurationError() {
        ReflectionTestUtils.setField(bootstrap, "tenantInfoService", null);

        assertThrows(IllegalStateException.class, bootstrap::onApplicationReady);
        verifyNoInteractions(accountService);
    }

    /** An existing role, with an id — without one the code would take the create-the-role branch instead. */

    /**
     * The SUPER_ADMIN grant, written through the model service. The typed service refuses every such
     * binding, whoever asks — a real boot failed on exactly that while the mocks here let it through.
     */
    private Map<String, Object> grant() {
        verify(userRoleRelService, never()).createOne(any(UserRoleRel.class));
        ArgumentCaptor<Map<String, Object>> grant = ArgumentCaptor.forClass(Map.class);
        verify(modelService).createOne(eq("UserRoleRel"), grant.capture());
        return grant.getValue();
    }
    private static Role superAdminRole() {
        Role role = new Role();
        role.setId(9L);
        role.setCode(RoleConstant.CODE_SUPER_ADMIN);
        return role;
    }

    private static UserInfo userInfo(Long userId) {
        UserInfo info = new UserInfo();
        info.setUserId(userId);
        return info;
    }
}
