package io.softa.starter.tenant.service.impl;

import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.tenant.config.TenantProvisioningProperties;
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
 * <p><b>FAILED comes from {@link #markSeederFailed}</b>, called by each seed consumer from its catch block,
 * so a failure is recorded in seconds with the seeder named. Not terminal: {@link #markSeederReady} flips a
 * tenant back once the seed completes, so a transient cause that MQ redelivery heals corrects itself.
 *
 * <p><b>Context</b>: every public entry point runs {@code inSystemContext} because it is driven from a Pulsar
 * consumer (no ambient tenant context) and touches shared, non-multiTenant models ({@link TenantSeedProgress}
 * + {@code TenantInfo}, both tenant-scoped by an explicit column).
 */
@Slf4j
@Service
public class TenantProvisioningStatusService extends EntityServiceImpl<TenantSeedProgress, Long> {

    private final TenantInfoServiceImpl tenantInfoService;
    private final TenantProvisioningProperties props;

    public TenantProvisioningStatusService(TenantInfoServiceImpl tenantInfoService,
                                           TenantProvisioningProperties props) {
        this.tenantInfoService = tenantInfoService;
        this.props = props;
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
     * A seeder failed: record per-seeder FAILED and flag the tenant.
     *
     * <p><b>Called by each seed consumer from its catch block</b>, before it rethrows. Marking on the first
     * failure is safe because this is not a terminal state: the rethrow keeps MQ redelivering, and a later
     * {@link #markSeederReady} flips the tenant back. A transient cause therefore shows as a brief DRAFT and
     * heals itself, while a permanent one is visible within seconds — and names the failing seeder, which a
     * tenant-level status alone cannot tell you.
     *
     * <p><b>What is no longer covered</b>: a seed that never reaches a catch block — message undelivered,
     * consumer down, or redelivery exhausted into the dead-letter topic — leaves the tenant in INITIALIZING
     * indefinitely. The time-driven sweep that used to catch that was removed with its cron; such a tenant is
     * found by querying INITIALIZING rows, not by a status flip.
     */
    @Transactional
    public void markSeederFailed(Long tenantId, String seederKey) {
        inSystemContext(() -> {
            upsertProgress(tenantId, seederKey, SeederStatus.FAILED);
            tenantInfoService.markStatus(tenantId, TenantStatus.DRAFT);
            log.error("Tenant {} provisioning FAILED at seeder {}", tenantId, seederKey);
            return null;
        });
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
