package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.mail.support.MailConfigCache;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Implementation of {@link MailSendServerConfigService}.
 */
@Slf4j
@Service
public class MailSendServerConfigServiceImpl extends EntityServiceImpl<MailSendServerConfig, Long>
        implements MailSendServerConfigService {

    /**
     * Self-reference to allow {@code @CrossTenant} AOP advice to be applied
     * when calling the cross-tenant query methods from within the same bean.
     */
    @Lazy
    @Autowired
    private MailSendServerConfigService self;

    @Autowired
    private SmtpMailTransport smtpMailTransport;

    @Autowired
    private MailConfigCache configCache;

    @Override
    public Optional<MailSendServerConfig> findTenantDefault() {
        // active = true is appended by the framework's active control.
        Filters filters = new Filters()
                .eq(MailSendServerConfig::getIsDefault, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailSendServerConfig::getSequence));
        List<MailSendServerConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    @CrossTenant
    public Optional<MailSendServerConfig> findVisibleById(Long id) {
        // Disabled configs stay resolvable by id: an accepted record replays
        // through the very config it was accepted with, and disabling a config
        // must not turn in-flight retries into CONFIG_NOT_RESOLVABLE.
        FlexQuery flexQuery = new FlexQuery(new Filters()
                .eq(MailSendServerConfig::getId, id)
                .in(MailSendServerConfig::getTenantId, TenantScopes.currentPlusPlatform()));
        flexQuery.setFilterControl(FilterControl.bypassActiveControl());
        return searchOne(flexQuery);
    }

    @Override
    @CrossTenant
    public Optional<MailSendServerConfig> findPlatformDefault() {
        Filters filters = new Filters()
                .eq(MailSendServerConfig::getTenantId, TenantScopes.PLATFORM)
                .eq(MailSendServerConfig::getIsDefault, true);
        FlexQuery flexQuery = new FlexQuery(filters,
                Orders.ofAsc(MailSendServerConfig::getSequence));
        List<MailSendServerConfig> results = this.searchList(flexQuery);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public void assertWritableInCurrentScope(Long id) {
        if (id == null || getById(id).isPresent()) {
            return;
        }
        // Visible but not own-scope readable = a platform row seen from a tenant.
        if (self.findVisibleById(id).isPresent()) {
            throw new BusinessException(
                    "Mail send server config {0} is platform-owned and cannot be edited or deleted "
                    + "from a tenant scope — it is managed by the platform operator.", id);
        }
    }

    /**
     * ORM-scoped search sees exactly the rows of the scope being written
     * (the write path's tenant context), so "other defaults" can never leak
     * across tenants. Demotion goes through {@link #updateOne} so the config
     * cache is evicted for every demoted row.
     */
    @Override
    public void demoteOtherDefaults(Long keptId) {
        // Includes disabled rows: a demotion that skipped them would leave a
        // second isDefault=true row waiting to re-appear when it is re-enabled.
        FlexQuery flexQuery = new FlexQuery(new Filters().eq(MailSendServerConfig::getIsDefault, true));
        flexQuery.setFilterControl(FilterControl.bypassActiveControl());
        for (MailSendServerConfig previous : this.searchList(flexQuery)) {
            if (Objects.equals(previous.getId(), keptId)) {
                continue;
            }
            MailSendServerConfig demoted = new MailSendServerConfig();
            demoted.setId(previous.getId());
            demoted.setIsDefault(false);
            this.updateOne(demoted);
        }
    }

    @Override
    public boolean updateOne(MailSendServerConfig entity) {
        boolean result = super.updateOne(entity);
        if (result) {
            // Only the Redis config cache needs eviction: SmtpMailTransport keys its connection
            // pools by the connection settings, so the edited config opens fresh ones on its next send.
            configCache.evictById(entity.getId());
        } else if (entity != null) {
            // The ORM silently drops writes to rows outside the caller's scope —
            // name the platform-row case instead of returning a mute false.
            assertWritableInCurrentScope(entity.getId());
        }
        return result;
    }

    @Override
    public boolean deleteById(Long id) {
        boolean result = super.deleteById(id);
        if (result) {
            configCache.evictById(id);
        } else {
            assertWritableInCurrentScope(id);
        }
        return result;
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(Long id) {
        MailSendServerConfig config = this.getById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Mail send server config with ID {0} not found.", id));
        return testConnectivity(config);
    }

    @Override
    public ConnectivityTestResultDTO testConnectivity(MailSendServerConfig config) {
        ConnectivityTestResultDTO result = new ConnectivityTestResultDTO();
        long start = System.currentTimeMillis();
        try {
            JavaMailSenderImpl sender = smtpMailTransport.buildSender(config);
            sender.testConnection();
            result.setSuccess(true);
            result.setServerGreeting("SMTP connection successful to " + config.getHost() + ":" + config.getPort());
        } catch (MessagingException e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            log.warn("SMTP connectivity test failed for [{}:{}]: {}", config.getHost(), config.getPort(), e.getMessage());
        }
        result.setLatencyMs(System.currentTimeMillis() - start);
        return result;
    }
}
