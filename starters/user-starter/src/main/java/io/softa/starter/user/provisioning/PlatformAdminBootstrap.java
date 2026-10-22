package io.softa.starter.user.provisioning;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.config.PlatformAdminProperties;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.UserRoleSource;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserRoleRelService;

import static io.softa.framework.base.constant.BaseConstant.PLATFORM_TENANT_ID;
import static io.softa.framework.base.context.ContextUtils.inTenantContext;

/**
 * Creates the first platform super-administrator from {@link PlatformAdminProperties} once the application
 * has started.
 *
 * <p>Why at startup rather than through an endpoint: every endpoint that can create an account needs a
 * signed-in caller, and a new database has nobody to sign in as. This used to be a SQL script run by hand
 * after the first boot, carrying a password hash every reader of the repository could see. Here the password
 * comes from the environment, the work runs after the boot DDL has built the schema, and every row goes
 * through the same services as any other account — so a change to the user model cannot leave it behind.
 *
 * <p>Creates, never repairs. An account with this address already in the platform tenant is left exactly as
 * it is — above all its password, or this configuration would quietly reset the super-administrator's
 * password on every restart; and a revoked role is not re-granted, because revoking it was someone's
 * decision.
 *
 * <p>An address that already signs a person in is refused too. Credentials belong to the person, not to the
 * account: creating the account would link it to that person, and setting the configured password would
 * replace theirs in every tenant they belong to.
 */
@Slf4j
@Component
public class PlatformAdminBootstrap {

    private static final String ROLE = "Role";

    private final PlatformAdminProperties properties;
    private final UserAccountService accountService;
    private final UserIdentityService identityService;
    private final RoleService roleService;
    private final UserRoleRelService userRoleRelService;
    private final ModelService<Long> modelService;
    private final TransactionTemplate transactionTemplate;

    /** Absent without tenant-starter; then there is no platform tenant to create, see {@link #platformTenant}. */
    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    public PlatformAdminBootstrap(PlatformAdminProperties properties,
                                  UserAccountService accountService,
                                  UserIdentityService identityService,
                                  RoleService roleService,
                                  UserRoleRelService userRoleRelService,
                                  ModelService<Long> modelService,
                                  TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.accountService = accountService;
        this.identityService = identityService;
        this.roleService = roleService;
        this.userRoleRelService = userRoleRelService;
        this.modelService = modelService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Runs after the application is ready — after the boot DDL, so every table it writes exists. A failure
     * here stops the application: an operator who switched this on and gets no administrator should hear
     * about it at once, not when they try to sign in.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            return;
        }
        String email = require(properties.getEmail(), "softa.user.platform-admin.email").trim();
        String password = require(properties.getPassword(), "softa.user.platform-admin.password");
        Long tenantId = platformTenant();
        try {
            // One transaction: a role with no account, or an account with no password, is worse than nothing.
            transactionTemplate.executeWithoutResult(
                    status -> inTenantContext(tenantId, () -> createIfAbsent(email, password)));
        } catch (RuntimeException e) {
            // Several replicas starting together against a fresh database all see no admin and race to create
            // it; the losers' inserts hit the account's unique email. Not a failure if the admin exists now.
            if (inTenantContext(tenantId, () -> accountExists(email))) {
                log.info("Platform admin {} was created concurrently by another instance.", email);
                return;
            }
            throw e;
        }
    }

    /**
     * The tenant the admin belongs to: the platform tenant under multi-tenancy, made sure of first; none in a
     * single-tenant deployment, which has no tenant row and no tenant check at login.
     */
    private Long platformTenant() {
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return null;
        }
        if (tenantInfoService == null) {
            throw new IllegalStateException("Multi-tenancy is enabled but there is no TenantInfoService: the "
                    + "platform admin belongs to the platform tenant, which needs the tenant model to exist.");
        }
        tenantInfoService.ensurePlatformTenant();
        if (!tenantInfoService.isTenantActive(PLATFORM_TENANT_ID)) {
            // Left alone on purpose (ensurePlatformTenant never repairs), but every platform account will be
            // refused at login until it is active again, so say so now rather than at the first sign-in.
            log.warn("The platform tenant (id {}) exists but is not active; platform accounts cannot sign in "
                    + "until it is.", PLATFORM_TENANT_ID);
        }
        return PLATFORM_TENANT_ID;
    }

    void createIfAbsent(String email, String password) {
        if (accountExists(email)) {
            log.info("Platform admin {} already exists; leaving it as it is.", email);
            return;
        }
        if (identityService.findByLoginIdentifier(email).isPresent()) {
            log.warn("Not creating platform admin {}: the address already signs a person in, and the account "
                    + "would be linked to them — the configured password would then replace theirs everywhere. "
                    + "Configure an address no one uses yet.", email);
            return;
        }
        Long roleId = superAdminRoleId();

        UserInfo user = accountService.registerInvitedUser(email, null, null);
        UserAccount account = accountService.getById(user.getUserId()).orElseThrow(() ->
                new IllegalStateException("Account " + user.getUserId() + " is missing right after its creation."));
        // Goes through the password policy; a weak configured password fails here and rolls everything back.
        identityService.setPassword(account, password);
        // Active from the start. An invited account is activated by accepting a mailed link, and a fresh
        // deployment may not have mail configured yet — this account is how it gets configured.
        account.setStatus(AccountStatus.ACTIVE);
        account.setActivationTime(LocalDateTime.now());
        accountService.updateOne(account);

        UserRoleRel grant = new UserRoleRel();
        grant.setUserId(account.getId());
        grant.setRoleId(roleId);
        // MANUAL, so the dynamic role sync never mistakes it for one of its own and revokes it.
        grant.setSource(UserRoleSource.MANUAL);
        userRoleRelService.createOne(grant);
        log.info("Created platform admin {}.", email);
    }

    /**
     * Whether the admin's account exists in the current (platform) tenant. Not {@code getUserByEmail}: that
     * one is cross-tenant, and the same address on an account in some other tenant does not mean this admin
     * exists. Under the platform tenant's context the model layer narrows this to that tenant.
     */
    private boolean accountExists(String email) {
        return accountService.exist(new Filters().eq(UserAccount::getEmail, email));
    }

    /**
     * The SUPER_ADMIN role of the platform tenant, created when missing. Holders bypass every permission
     * check, so it needs no grants of its own.
     *
     * <p>Written through the model service, not {@link RoleService}: the typed create refuses every role
     * that has a code, so that an administrator can never mint a built-in one, and has no notion of the
     * framework doing it. The write root's own guard lets this through for the same reason it lets the
     * built-in role seeds through — the permission check is skipped, which only system paths do.
     */
    private Long superAdminRoleId() {
        return roleService.searchOne(new Filters().eq(Role::getCode, RoleConstant.CODE_SUPER_ADMIN))
                .map(Role::getId)
                .orElseGet(() -> {
                    Map<String, Object> role = new HashMap<>();
                    role.put(LambdaUtils.getAttributeName(Role::getCode), RoleConstant.CODE_SUPER_ADMIN);
                    role.put(LambdaUtils.getAttributeName(Role::getName), "Super Admin");
                    role.put(LambdaUtils.getAttributeName(Role::getDescription),
                            "Platform super-administrator. Bypasses every permission check, so it needs no grants.");
                    role.put(LambdaUtils.getAttributeName(Role::getActive), true);
                    Serializable id = modelService.createOne(ROLE, role);
                    return ((Number) id).longValue();
                });
    }

    private static String require(String value, String property) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException(property + " is required when softa.user.platform-admin.enabled is true.");
        }
        return value;
    }
}
