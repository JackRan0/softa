package io.softa.starter.tenant.provisioning;

import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscription;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import io.softa.starter.tenant.enums.TenantStatus;
import io.softa.starter.tenant.service.PlanCatalog;
import io.softa.starter.tenant.service.SubscriptionPeriodPatch;
import io.softa.starter.tenant.service.TenantSubscriptionPeriodService;
import io.softa.starter.tenant.service.TenantSubscriptionService;
import io.softa.starter.tenant.service.impl.TenantInfoServiceImpl;
import io.softa.starter.tenant.service.impl.TenantProvisioningStatusService;

import static io.softa.framework.base.context.ContextUtils.inSystemContext;

/**
 * Tenant provisioning — a reusable tenant-starter feature. Creates the tenant registry row + its owned
 * 1:1 {@link TenantSubscription} (the version: planId + lifecycle + effective dates) in a <b>system
 * context</b> (crossTenant + skip-permission; both rows are shared / non-tenant-scoped), then publishes
 * {@link TenantProvisionedEvent} so the app can react.
 *
 * <p><b>Why an event, not direct calls:</b> per-tenant seeding lives in seed-starter and the first
 * admin lives in user-starter — both are ⊥ to tenant-starter, so tenant-starter must not call them.
 * The app (which depends on all of them) listens for the event and does the seeding / admin creation.
 * The event fires <b>synchronously inside this transaction</b>, so those app-side reactions stay atomic
 * with tenant creation — a listener that throws rolls the whole provisioning back.
 */
@Slf4j
@Service
public class TenantProvisioningService {

    private final TenantInfoServiceImpl tenantInfoService;
    private final TenantSubscriptionService subscriptionService;
    private final TenantSubscriptionPeriodService periodService;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantProvisioningStatusService provisioningStatusService;
    /** Only for the plan-catalog reads behind {@link PlanCatalog} — this service owns no models of its own. */
    private final ModelService<?> modelService;

    public TenantProvisioningService(TenantInfoServiceImpl tenantInfoService,
                                     TenantSubscriptionService subscriptionService,
                                     TenantSubscriptionPeriodService periodService,
                                     ApplicationEventPublisher eventPublisher,
                                     TenantProvisioningStatusService provisioningStatusService,
                                     ModelService<?> modelService) {
        this.tenantInfoService = tenantInfoService;
        this.subscriptionService = subscriptionService;
        this.periodService = periodService;
        this.eventPublisher = eventPublisher;
        this.provisioningStatusService = provisioningStatusService;
        this.modelService = modelService;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProvisionResult provision(ProvisionTenantRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getName(), "name must not be blank");
        String code = normalizeCode(request.getCode(), request.getName());

        // System context — persist the subscription row + the registry row that links it.
        Long tenantId = inSystemContext(() -> {
            // Every tenant gets a subscription row at birth: it is the projection carrier the tenant list
            // and authorization read. Its projected columns are left unset — ensureBasePeriod writes the
            // base period below and the projection refresh fills them in from the period rows, which is the
            // only writer of this row's projected state. Seeding a status here would be a guess that the
            // refresh immediately overwrites, and a wrong one for the window in between.
            TenantSubscription subscription = new TenantSubscription();
            Long subscriptionId = subscriptionService.createOne(subscription);

            TenantInfo tenant = new TenantInfo();
            tenant.setName(request.getName().trim());
            tenant.setCode(code);
            // DRAFT, not ACTIVE. Setup and operation share one field now, so "created" must not read as
            // "built and usable" — beginProvisioning below moves it on (INITIALIZING while seeders run, or
            // straight to ACTIVE when none are expected). Creating it ACTIVE left a window, and a failure
            // before beginProvisioning, in which an unbuilt tenant advertised itself as ready.
            //
            // No activatedTime either: the stamp belongs to the moment the tenant actually becomes ACTIVE,
            // which markStatus now writes. Stamping it here would date every tenant's activation to its
            // creation, including ones whose setup never finished.
            tenant.setStatus(TenantStatus.DRAFT);
            tenant.setDefaultLanguage(request.getDefaultLanguage());
            tenant.setDefaultTimezone(request.getDefaultTimezone());
            tenant.setDefaultCurrency(request.getDefaultCurrency());
            tenant.setDefaultCountry(request.getDefaultCountry());
            tenant.setDataRegion(request.getDataRegion());
            tenant.setSubscriptionId(subscriptionId);
            Long newTenantId = tenantInfoService.createOne(tenant);

            // Back-link, and it has to be a second write: the subscription is created first so the tenant
            // can carry its id (the framework's 1:1 puts the FK on the owner), so the tenant id does not
            // exist yet at that point. See TenantSubscription.tenantId for why the back-link is worth it.
            // `updateOne(entity, true)` — nulls ignored — so this touches only tenantId. The full-overwrite
            // variant would carry the entity's virtual `periods` relation as null, which the framework reads
            // as "clear the relation".
            TenantSubscription backLink = new TenantSubscription();
            backLink.setId(subscriptionId);
            backLink.setTenantId(newTenantId);
            subscriptionService.updateOne(backLink, true);
            return newTenantId;
        });

        // Initial periods are optional: a tenant sold nothing yet simply has none and runs on the floor
        // plan. Recorded after the tenant row exists so the projection refresh can read its timezone.
        recordInitialPeriods(tenantId, request.getSubscriptionId());
        // After the operator's periods, not before: the create form pre-populates the base row, so if one
        // arrived in the patch this finds it and adds nothing. Running first would create a second one.
        ensureBasePeriod(tenantId);

        // Mark the initialization axis before broadcasting: no expected seeders (single-tenant / no-MQ)
        // → ACTIVE immediately; otherwise INITIALIZING until the expected seeders report done.
        provisioningStatusService.beginProvisioning(tenantId);

        // Synchronous, same-transaction: app-side listeners (seed per-tenant data, create first admin)
        // run before commit, so any failure there rolls back tenant creation too.
        eventPublisher.publishEvent(new TenantProvisionedEvent(tenantId, code, request.getName().trim()));

        log.info("Provisioned tenant id={} code={}", tenantId, code);
        return new ProvisionResult(tenantId, code);
    }

    /**
     * Run a failed setup again: announce the tenant once more, exactly as {@link #provision} did, so every
     * seeder takes it up where it stopped.
     *
     * <p>Nothing is discarded, and nothing needs to be. The announcement is delivered at least once anyway, so
     * every seeder already treats a second one as normal: seed-starter runs only the seed files the tenant has
     * not run successfully, and the business seeders skip what they find in place. Their progress rows stay
     * as well — a seeder that had finished reports done again as it re-runs, and the tenant becomes ACTIVE
     * when the one that failed does.
     *
     * <p><b>Draft only.</b> A tenant still Initializing has seeders running, and announcing it again next to
     * them would run two rounds of the same seeds at once. One whose setup finished has nothing to retry.
     *
     * @param tenantId the tenant whose setup failed
     */
    @Transactional(rollbackFor = Exception.class)
    public void retrySetup(Long tenantId) {
        TenantInfo tenant = tenantInfoService.getById(tenantId)
                .orElseThrow(() -> new BusinessException("Tenant {0} does not exist.", String.valueOf(tenantId)));
        if (!TenantStatus.DRAFT.equals(tenant.getStatus())) {
            throw new BusinessException("Tenant {0} is {1}. Only a tenant whose setup failed (Draft) can retry "
                    + "it; one that is Initializing still has its setup running.", tenant.getName(),
                    tenant.getStatus() == null ? "without a status" : tenant.getStatus().getStatus());
        }
        // Same order as provision(): the axis reopens before the announcement goes out.
        provisioningStatusService.beginProvisioning(tenantId);
        eventPublisher.publishEvent(new TenantProvisionedEvent(tenantId, tenant.getCode(), tenant.getName()));
        log.info("Tenant {} ({}) setup retried", tenantId, tenant.getCode());
    }

    /**
     * Record the subscription periods the create request carried, so a customer buying Pro on day one is
     * done in one submit rather than created on the floor plan and upgraded afterwards.
     *
     * <p>Optional by design: a tenant nobody has sold anything to yet simply has no period and runs on the
     * floor plan.
     *
     * <p>Handed to the period service's patch entry point — the same one the detail form's edits go through,
     * so create and edit share one write path with the same guards and the same projection refresh. What
     * must not happen is letting the framework's nested-relation pipeline persist this: it writes via the
     * generic {@code ModelService}, which runs no guards and leaves the tenant list reading NEVER_SUBSCRIBED.
     */
    private void recordInitialPeriods(Long tenantId, ProvisionTenantRequest.SubscriptionInput input) {
        if (input == null || input.getPeriods() == null) {
            return;
        }
        TenantInfo tenant = tenantInfoService.getById(tenantId).orElse(null);
        if (tenant == null || tenant.getSubscriptionId() == null) {
            return;
        }
        inSystemContext(() -> {
            periodService.applyPatch(tenant.getSubscriptionId(), input.getPeriods());
            return null;
        });
    }

    /**
     * Guarantee the tenant has its base period — the row that makes "what is this tenant entitled to" a
     * question about data rather than about a fallback rule.
     *
     * <p>Every tenant gets exactly one, and it is the reason the entitlement resolver no longer needs a
     * floor-plan fallback: a tenant always has at least one period, so its standing is always derivable
     * from dates. That also makes {@code NEVER_SUBSCRIBED} unreachable.
     *
     * <p><b>Idempotent by search, not by flag.</b> The create form pre-populates the base row so the
     * operator can set an expiry at creation time, so the row may already have arrived through
     * {@link #recordInitialPeriods}. Checking for it is what keeps the UI path and the API / seed paths
     * from producing two.
     *
     * <p><b>Fails when the catalog has no plan.</b> Refusing to create the tenant is the lesser evil: the
     * alternative is a tenant with no period and no fallback, which resolves to zero modules — it would
     * look created, admit its admin, and show an empty product with nothing to point at. A deployment that
     * sells versions has a catalog; one that does not should not have tenant-starter's period machinery
     * writing rows at all.
     */
    private void ensureBasePeriod(Long tenantId) {
        TenantInfo tenant = tenantInfoService.getById(tenantId).orElse(null);
        if (tenant == null || tenant.getSubscriptionId() == null) {
            return;
        }
        Plan floor = PlanCatalog.floorPlan(modelService);
        Assert.notNull(floor, "Cannot provision a tenant: the plan catalog has no plan with a tier, so there "
                + "is no baseline plan to start it on.");
        inSystemContext(() -> {
            boolean alreadyThere = periodService.searchList(new FlexQuery(
                            Filters.of("subscriptionId", Operator.EQUAL, tenant.getSubscriptionId())))
                    .stream()
                    .anyMatch(period -> floor.getId().equals(period.getPlanId()));
            if (alreadyThere) {
                return null;
            }
            SubscriptionPeriodPatch.PeriodInput base = new SubscriptionPeriodPatch.PeriodInput();
            base.setPlanId(floor.getId());
            // TRIAL, not PAID: nobody paid for it. The projection turns this into "on trial" while the row
            // is open, and into "expired" if an operator later sets an end date — which is how a base-only tenant
            // can be cut off without deleting anything.
            base.setPeriodType(SubscriptionPeriodType.TRIAL);
            // The tenant's own today, not the server's: a tenant whose day has not started yet would
            // otherwise get a period beginning "tomorrow" and spend its first hours uncovered.
            base.setEffectiveStartDate(LocalDate.now(Timezone.zoneIdOrUtc(tenant.getDefaultTimezone())));
            // Open-ended = permanent. An operator may set an end date afterwards; that is the whole
            // mechanism for time-boxing a base-only tenant (a competitor evaluating the product, say).
            base.setEffectiveEndDate(null);

            SubscriptionPeriodPatch patch = new SubscriptionPeriodPatch();
            patch.setCreate(List.of(base));
            periodService.applyPatch(tenant.getSubscriptionId(), patch);
            return null;
        });
    }

    /** Use the supplied code when present, else slug the name; lower-kebab, ≤64 chars. */
    private String normalizeCode(String code, String name) {
        String raw = (code != null && !code.isBlank()) ? code : name;
        String slug = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > 64) {
            slug = slug.substring(0, 64).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "tenant" : slug;
    }
}
