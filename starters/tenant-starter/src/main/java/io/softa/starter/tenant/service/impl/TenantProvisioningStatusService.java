package io.softa.starter.tenant.service.impl;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.tenant.config.TenantProvisioningProperties;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSeedProgress;
import io.softa.starter.tenant.enums.SeederStatus;
import io.softa.starter.tenant.enums.TenantStatus;

import static io.softa.framework.base.context.ContextUtils.inSystemContext;

/**
 * Provisioning-status coordinator — the per-tenant "completion latch". Owns the {@link TenantSeedProgress}
 * ledger and folds per-seeder completions into the tenant's {@link TenantStatus}. Framework-side
 * and business-agnostic: it only ever sees opaque {@code seederKey} strings + the app's expected-seeders set;
 * it never imports or switches on a business module.
 *
 * <p><b>Idempotency</b>: progress is upserted by {@code (tenantId, seederKey)} — redelivery re-writes DONE
 * without churn; readiness is a set-containment query over DONE rows (never a counter), so it is repeat-safe.
 *
 * <p><b>FAILED comes from {@link #markSeederFailed(Long, String, int)}</b>, called by each seed consumer from
 * its catch block, so a failure is recorded in seconds with the seeder named. The tenant stays INITIALIZING
 * while MQ still has deliveries left to try, and becomes DRAFT only when the last one fails — so DRAFT means
 * "setup failed, someone has to act", and Retry Setup, which only takes a DRAFT tenant, never runs beside a
 * redelivery of the same setup.
 *
 * <p><b>Context</b>: every public entry point runs {@code inSystemContext} because it is driven from a Pulsar
 * consumer (no ambient tenant context) and touches shared, non-multiTenant models ({@link TenantSeedProgress}
 * + {@code TenantInfo}, both tenant-scoped by an explicit column).
 */
@Slf4j
@Service
public class TenantProvisioningStatusService extends EntityServiceImpl<TenantSeedProgress, Long> {

    /** The statuses past setup, where a seeder's failure can only come from a duplicate message. */
    private static final Set<TenantStatus> SETUP_FINISHED =
            EnumSet.of(TenantStatus.ACTIVE, TenantStatus.SUSPENDED, TenantStatus.CLOSED);

    private final TenantInfoServiceImpl tenantInfoService;
    private final TenantProvisioningProperties props;
    private final ObjectProvider<DeadLetterPolicy> retryPolicy;

    public TenantProvisioningStatusService(TenantInfoServiceImpl tenantInfoService,
                                           TenantProvisioningProperties props,
                                           @Qualifier("tenantSeedRetryPolicy") ObjectProvider<DeadLetterPolicy> retryPolicy) {
        this.tenantInfoService = tenantInfoService;
        this.props = props;
        this.retryPolicy = retryPolicy;
    }

    /**
     * Called at the end of {@code provision()}. No expected seeders (empty config — single-tenant / no-MQ,
     * or rollout Step 1) → straight to ACTIVE; otherwise INITIALIZING until the expected set reports done.
     */
    public void beginProvisioning(Long tenantId) {
        TenantStatus initial = props.getExpectedSeeders().isEmpty()
                ? TenantStatus.ACTIVE
                : TenantStatus.INITIALIZING;
        inSystemContext(() -> {
            tenantInfoService.markStatus(tenantId, initial);
            return null;
        });
    }

    /** A seeder finished for this tenant: record DONE (idempotent), flip to ACTIVE once all expected are done. */
    @Transactional
    public void markSeederReady(Long tenantId, String seederKey) {
        inSystemContext(() -> {
            upsertProgress(tenantId, seederKey, SeederStatus.DONE);
            Set<String> done = doneKeys(tenantId);
            if (done.containsAll(props.getExpectedSeeders())) {
                tenantInfoService.markStatus(tenantId, TenantStatus.ACTIVE);
                log.info("Tenant {} setup complete, now ACTIVE (done seeders {})", tenantId, done);
            }
            return null;
        });
    }

    /**
     * A seeder's attempt failed: record per-seeder FAILED, and fail the setup only when MQ will not deliver the
     * message again.
     *
     * <p><b>Called by each seed consumer from its catch block</b>, before it rethrows, with the redelivery count
     * of the message it failed on. While deliveries remain the tenant stays INITIALIZING: a transient cause
     * heals on its own, and the setup is still running, so offering Retry Setup then would start a second round
     * beside the redelivery — both deciding from the same records which seed files are left, both loading the
     * same ones. The attempt is still recorded, so the failing seeder is named within seconds.
     *
     * <p>The last delivery's failure moves the tenant to DRAFT ({@link #markSeederFailed(Long, String)}).
     *
     * @param redeliveryCount how many times the failed message had been redelivered before this attempt
     *                        (Pulsar's {@code pulsar_message_redelivery_count}); 0 on the first delivery
     */
    @Transactional
    public void markSeederFailed(Long tenantId, String seederKey, int redeliveryCount) {
        if (isLastAttempt(redeliveryCount)) {
            markSeederFailed(tenantId, seederKey);
            return;
        }
        inSystemContext(() -> {
            if (setupFinished(tenantId)) {
                return null;
            }
            upsertProgress(tenantId, seederKey, SeederStatus.FAILED);
            log.warn("Tenant {} seeder {} failed on delivery {}; it is redelivered and the tenant stays {}",
                    tenantId, seederKey, redeliveryCount + 1, TenantStatus.INITIALIZING);
            return null;
        });
    }

    /**
     * The setup failed for good: record per-seeder FAILED and move the tenant from INITIALIZING to DRAFT, where
     * Retry Setup picks it up.
     *
     * <p>Only a tenant still setting up moves. The failure may come from a duplicate message that ran beside
     * the round which set the tenant up, and a tenant that has become ACTIVE meanwhile — or been suspended or
     * closed — is left exactly as it is, its progress rows included; see
     * {@link TenantInfoServiceImpl#markSetupFailed}.
     *
     * <p><b>What is not covered</b>: a seed that never reaches a catch block — message undelivered or consumer
     * down — leaves the tenant in INITIALIZING indefinitely. The time-driven sweep that used to catch that was
     * removed with its cron; such a tenant is found by querying INITIALIZING rows and set to DRAFT by hand.
     */
    @Transactional
    public void markSeederFailed(Long tenantId, String seederKey) {
        inSystemContext(() -> {
            if (setupFinished(tenantId)) {
                return null;
            }
            upsertProgress(tenantId, seederKey, SeederStatus.FAILED);
            if (tenantInfoService.markSetupFailed(tenantId)) {
                log.error("Tenant {} provisioning FAILED at seeder {}", tenantId, seederKey);
            } else {
                log.error("Tenant {} seeder {} failed; the tenant was not setting up, so its status is unchanged",
                        tenantId, seederKey);
            }
            return null;
        });
    }

    /**
     * Whether a failure on this delivery is the last: MQ moves the message to the dead-letter topic instead of
     * redelivering it. Without a cap there is no last delivery to wait for, so every failure counts as final —
     * otherwise a tenant would stay INITIALIZING through endless retries.
     */
    boolean isLastAttempt(int redeliveryCount) {
        DeadLetterPolicy policy = retryPolicy.getIfAvailable();
        return policy == null || policy.getMaxRedeliverCount() <= 0
                || redeliveryCount >= policy.getMaxRedeliverCount();
    }

    /** A failure reported for a tenant past setup comes from a duplicate message, and changes nothing. */
    private boolean setupFinished(Long tenantId) {
        TenantStatus status = tenantInfoService.getById(tenantId).map(TenantInfo::getStatus).orElse(null);
        if (status != null && SETUP_FINISHED.contains(status)) {
            log.info("Tenant {} is {}; a seeder failure on a duplicate message is ignored", tenantId, status);
            return true;
        }
        return false;
    }

    /**
     * Dependency gate for a downstream seeder that must wait for one or more upstream seeders. Returns true
     * once every key in {@code dependsOn} is DONE for this tenant — a set-containment check, so it is
     * <b>order-independent</b> (upstreams may complete in any order) and repeat-safe. {@code dependsOn}
     * empty/null → always satisfied (no dependency). This is the generic form: a single-upstream seeder
     * passes a one-element set; a multi-upstream seeder passes the full set and only proceeds once all are in.
     *
     * <p>{@code justCompletedKey} — the seederKey of the message that triggered the check — is folded into the
     * DONE set before comparing. The coordinator and the downstream consumer both subscribe to
     * {@code seeder-completed} (fan-out), so when the downstream checks off the very message that reported an
     * upstream done, the coordinator may not have upserted that row yet; folding it in closes that race for the
     * triggering upstream. (Several mutually-independent upstreams completing concurrently is not fully covered
     * — but the current graph is a chain (pre-data → corehr) where the last upstream's message arrives after
     * earlier ones are already DONE; downstream seed is idempotent and Pulsar redelivery is the backstop.)
     *
     * @param dependsOn        upstream seeder keys this seeder waits for
     * @param justCompletedKey the seederKey just reported done (may be null)
     */
    public boolean dependenciesSatisfied(Long tenantId, Set<String> dependsOn, String justCompletedKey) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return true;
        }
        return inSystemContext(() -> {
            Set<String> done = doneKeys(tenantId);
            if (justCompletedKey != null) {
                done.add(justCompletedKey);
            }
            return done.containsAll(dependsOn);
        });
    }

    /** Idempotent upsert of one (tenantId, seederKey) row. Runs inside the caller's system context. */
    private void upsertProgress(Long tenantId, String seederKey, SeederStatus status) {
        Filters filters = new Filters()
                .eq(TenantSeedProgress::getTenantId, tenantId)
                .eq(TenantSeedProgress::getSeederKey, seederKey);
        TenantSeedProgress existing = this.searchOne(filters).orElse(null);
        if (existing == null) {
            TenantSeedProgress row = new TenantSeedProgress();
            row.setTenantId(tenantId);
            row.setSeederKey(seederKey);
            row.setStatus(status);
            this.createOne(row);
        } else if (existing.getStatus() != status) {
            existing.setStatus(status);
            this.updateOne(existing);
        }
    }

    /** The set of seeder keys currently DONE for this tenant. Runs inside the caller's system context. */
    private Set<String> doneKeys(Long tenantId) {
        Filters filters = new Filters()
                .eq(TenantSeedProgress::getTenantId, tenantId)
                .eq(TenantSeedProgress::getStatus, SeederStatus.DONE);
        return this.searchList(filters).stream()
                .map(TenantSeedProgress::getSeederKey)
                .collect(Collectors.toSet());
    }
}
