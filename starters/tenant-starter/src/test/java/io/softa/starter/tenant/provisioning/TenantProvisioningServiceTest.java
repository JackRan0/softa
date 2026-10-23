package io.softa.starter.tenant.provisioning;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscription;
import io.softa.starter.tenant.enums.SubscriptionStatus;
import io.softa.starter.tenant.enums.TenantStatus;
import io.softa.starter.tenant.service.SubscriptionPeriodPatch;
import io.softa.starter.tenant.service.TenantSubscriptionPeriodService;
import io.softa.starter.tenant.service.TenantSubscriptionService;
import io.softa.starter.tenant.service.impl.TenantInfoServiceImpl;
import io.softa.starter.tenant.service.impl.TenantProvisioningStatusService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Opening a tenant: the two rows, the order they have to be written in, and how a day-one sale is recorded.
 *
 * <p>Every customer goes through this once, and the failure modes are not gradual. If the subscription row is
 * missing, the tenant list and authorization both read a null projection. If the back-link is skipped, billing
 * cannot find its way from a subscription to its tenant. If the periods a sales rep entered on the create form
 * are dropped, the customer is created on the free plan having paid for Pro — and nothing reports an error,
 * because provisioning itself succeeded.
 *
 * <p>The class this replaces tested a subscription model that no longer exists (one interval and a lifecycle
 * column on the subscription row). What survives that rewrite is the sequencing, and that is what is pinned
 * here.
 */
class TenantProvisioningServiceTest {

    private static final long SUB_ID = 9001L;
    private static final long TENANT_ID = 1001L;

    private TenantInfoServiceImpl tenantInfoService;
    private TenantSubscriptionService subscriptionService;
    private TenantSubscriptionPeriodService periodService;
    private TenantProvisioningStatusService provisioningStatusService;
    private ApplicationEventPublisher eventPublisher;
    private ModelService<?> modelService;
    private TenantProvisioningService service;

    @BeforeEach
    void setUp() {
        tenantInfoService = mock(TenantInfoServiceImpl.class);
        subscriptionService = mock(TenantSubscriptionService.class);
        periodService = mock(TenantSubscriptionPeriodService.class);
        provisioningStatusService = mock(TenantProvisioningStatusService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        modelService = mock(ModelService.class);

        // The plan catalog, stubbed to the shape provisioning depends on: a lowest-tier plan exists, so
        // ensureFreePeriod has a baseline to write. Without it provisioning refuses outright — see its
        // javadoc for why a tenant with no period is worse than a tenant that failed to be created.
        when(modelService.searchList(eq("Plan"), any(FlexQuery.class), eq(Plan.class)))
                .thenReturn(List.of(plan("plan.free", 0), plan("plan.pro", 10)));
        // No period exists yet, so the free row gets written. Overridden per test where the point is that
        // one arrived with the request.
        when(periodService.searchList(any(FlexQuery.class))).thenReturn(List.of());

        when(subscriptionService.createOne(any(TenantSubscription.class))).thenReturn(SUB_ID);
        when(tenantInfoService.createOne(any(TenantInfo.class))).thenReturn(TENANT_ID);
        when(tenantInfoService.getById(TENANT_ID)).thenReturn(Optional.of(provisionedTenant()));

        service = new TenantProvisioningService(tenantInfoService, subscriptionService, periodService,
                eventPublisher, provisioningStatusService, modelService);
    }

    private static Plan plan(String id, int tier) {
        Plan p = new Plan();
        p.setId(id);
        p.setTier(tier);
        return p;
    }

    // ─── the two rows ───

    @Test
    @DisplayName("every tenant is born with a subscription row, empty rather than absent")
    void subscriptionRowCreatedAtBirth() {
        service.provision(request(null));

        ArgumentCaptor<TenantSubscription> captor = ArgumentCaptor.forClass(TenantSubscription.class);
        verify(subscriptionService).createOne(captor.capture());
        // The projected columns are left unset on purpose: the projection refresh is their only writer, and
        // it fills them from the period rows moments later. Seeding a status here would be a guess that the
        // refresh overwrites — and a wrong one for the window in between. A missing ROW would be the real
        // problem (the tenant list and the entitlement resolver would read null), which is why one is created.
        assertThat(captor.getValue().getSubscriptionStatus()).isNull();
        assertThat(captor.getValue().getPlanId()).isNull();
    }

    @Test
    @DisplayName("the subscription is created first so the tenant row can carry its id")
    void subscriptionBeforeTenant() {
        service.provision(request(null));

        // The framework's 1:1 puts the FK on the owner (TenantInfo.subscriptionId), so this order is forced —
        // reversing it would leave the tenant row with a null subscription.
        InOrder order = inOrder(subscriptionService, tenantInfoService);
        order.verify(subscriptionService).createOne(any(TenantSubscription.class));
        order.verify(tenantInfoService).createOne(any(TenantInfo.class));

        ArgumentCaptor<TenantInfo> captor = ArgumentCaptor.forClass(TenantInfo.class);
        verify(tenantInfoService).createOne(captor.capture());
        assertThat(captor.getValue().getSubscriptionId()).isEqualTo(SUB_ID);
        // DRAFT, not ACTIVE. Setup and operation share one status field, so the row a tenant is created with
        // must not already claim to be usable — beginProvisioning moves it on once the seeders are lined up.
        // Creating it ACTIVE left a window (and, if beginProvisioning failed, a permanent state) in which an
        // unbuilt tenant advertised itself as ready and its users could log in.
        assertThat(captor.getValue().getStatus()).isEqualTo(TenantStatus.DRAFT);
        assertThat(captor.getValue().getActivatedTime())
                .as("the activation stamp belongs to the moment it truly becomes ACTIVE, not to creation")
                .isNull();
    }

    @Test
    @DisplayName("the back-link writes only tenantId, with nulls ignored")
    void backLinkIgnoresNulls() {
        service.provision(request(null));

        ArgumentCaptor<TenantSubscription> captor = ArgumentCaptor.forClass(TenantSubscription.class);
        // `true` = ignore nulls. The full-overwrite variant would carry the entity's virtual `periods`
        // relation as null, which the framework reads as "clear the relation" — the same mechanism that once
        // deleted every period a refresh was meant to describe.
        verify(subscriptionService).updateOne(captor.capture(), eq(true));
        assertThat(captor.getValue().getId()).isEqualTo(SUB_ID);
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().getPlanId())
                .as("anything else set here would be written over the row just created")
                .isNull();
    }

    // ─── the day-one sale ───

    @Test
    @DisplayName("periods from the create form go through the guarded patch entry point")
    void initialPeriodsRecordedThroughThePatch() {
        SubscriptionPeriodPatch patch = new SubscriptionPeriodPatch();
        patch.setCreate(List.of(new SubscriptionPeriodPatch.PeriodInput()));

        service.provision(request(patch));

        // Not the framework's nested-relation pipeline: that writes through the generic ModelService, which
        // runs none of the period guards and refreshes no projection, leaving the tenant list reading
        // NEVER_SUBSCRIBED for a customer who just bought Pro.
        verify(periodService).applyPatch(SUB_ID, patch);
    }

    @Test
    @DisplayName("periods are recorded after the tenant row exists, so the refresh can read its timezone")
    void periodsRecordedAfterTenantRow() {
        SubscriptionPeriodPatch patch = new SubscriptionPeriodPatch();
        patch.setCreate(List.of(new SubscriptionPeriodPatch.PeriodInput()));

        service.provision(request(patch));

        // The patch ends in a projection refresh, and that resolves "today" in the tenant's own zone. Running
        // it before the tenant row existed would date the projection in the server's zone instead.
        InOrder order = inOrder(tenantInfoService, periodService);
        order.verify(tenantInfoService).createOne(any(TenantInfo.class));
        order.verify(periodService).applyPatch(anyLong(), any(SubscriptionPeriodPatch.class));
    }

    @Test
    @DisplayName("no periods sold — the free period is still written, open-ended and on the floor plan")
    void noPeriods_freePeriodStillWritten() {
        // Replaces the former "nothing recorded" rule. A tenant nobody sold anything to used to have no
        // periods at all and lean on the resolver's floor-plan fallback; it now owns a real free period, which
        // is what lets the fallback go away and makes its entitlement a fact about data.
        service.provision(request(null));

        ArgumentCaptor<SubscriptionPeriodPatch> captor =
                ArgumentCaptor.forClass(SubscriptionPeriodPatch.class);
        verify(periodService).applyPatch(eq(SUB_ID), captor.capture());
        assertThat(captor.getValue().getCreate()).hasSize(1);
        SubscriptionPeriodPatch.PeriodInput free = captor.getValue().getCreate().getFirst();
        assertThat(free.getPlanId()).as("the catalog's lowest tier, not a hard-coded id").isEqualTo("plan.free");
        assertThat(free.getPeriodType())
                .as("nobody paid for it, so TRIAL — PAID would misreport it in every revenue read")
                .isEqualTo(SubscriptionPeriodType.TRIAL);
        assertThat(free.getEffectiveEndDate()).as("open-ended = permanent until an operator ends it").isNull();
        assertThat(free.getEffectiveStartDate()).isNotNull();
    }

    @Test
    @DisplayName("a free period already in the request is used, not duplicated")
    void freePeriodAlreadyPresent_notDuplicated() {
        // The create form pre-populates the free row so an operator can set an expiry at creation time, so it
        // arrives through the patch. "Exactly one free period" is the invariant; finding the existing one is
        // what keeps the UI path from producing a second alongside the API and seed paths.
        TenantSubscriptionPeriod existingFree = new TenantSubscriptionPeriod();
        existingFree.setPlanId("plan.free");
        when(periodService.searchList(any(FlexQuery.class))).thenReturn(List.of(existingFree));

        service.provision(request(null));

        verify(periodService, never()).applyPatch(anyLong(), any(SubscriptionPeriodPatch.class));
    }

    @Test
    @DisplayName("an empty plan catalog refuses the create rather than making an unentitled tenant")
    void emptyPlanCatalog_refusesProvisioning() {
        // The alternative is a tenant with no period and no fallback: it resolves to zero modules, looks
        // created, admits its admin, and shows an empty product with nothing to point at.
        when(modelService.searchList(eq("Plan"), any(FlexQuery.class), eq(Plan.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.provision(request(null)))
                .hasMessageContaining("no plan with a tier");
    }

    // ─── what the rest of the system is told ───

    @Test
    @DisplayName("the initialization axis is marked before the event, not after")
    void statusMarkedBeforeBroadcast() {
        service.provision(request(null));

        // Listeners of the event report their own seeder completion; if the axis were opened after they ran,
        // a fast seeder's report would arrive before anything was waiting for it and the tenant would sit in
        // INITIALIZING forever.
        InOrder order = inOrder(provisioningStatusService, eventPublisher);
        order.verify(provisioningStatusService).beginProvisioning(TENANT_ID);
        order.verify(eventPublisher).publishEvent(any(TenantProvisionedEvent.class));
    }

    @Test
    @DisplayName("the provisioned event carries the id and the normalized code")
    void eventCarriesIdAndCode() {
        service.provision(request(null));

        ArgumentCaptor<TenantProvisionedEvent> captor = ArgumentCaptor.forClass(TenantProvisionedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().code()).isEqualTo("acme-corp");
    }

    // ─── retrying a failed setup ───

    @Test
    @DisplayName("a Draft tenant is announced again — after its axis reopens, under its own id, code and name")
    void retry_announcesADraftTenantAgain() {
        when(tenantInfoService.getById(TENANT_ID)).thenReturn(Optional.of(tenantIn(TenantStatus.DRAFT)));

        service.retrySetup(TENANT_ID);

        // The same order as provision(), for the same reason: a fast seeder's report must find the axis open.
        InOrder order = inOrder(provisioningStatusService, eventPublisher);
        order.verify(provisioningStatusService).beginProvisioning(TENANT_ID);
        ArgumentCaptor<TenantProvisionedEvent> captor = ArgumentCaptor.forClass(TenantProvisionedEvent.class);
        order.verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new TenantProvisionedEvent(TENANT_ID, "acme-corp", "Acme Corp"));
    }

    @Test
    @DisplayName("a retry writes nothing of its own: the tenant, its subscription and its periods stay as they are")
    void retry_writesNothingItself() {
        when(tenantInfoService.getById(TENANT_ID)).thenReturn(Optional.of(tenantIn(TenantStatus.DRAFT)));

        service.retrySetup(TENANT_ID);

        verify(tenantInfoService, never()).createOne(any(TenantInfo.class));
        verify(subscriptionService, never()).createOne(any(TenantSubscription.class));
        verifyNoInteractions(periodService);
    }

    @ParameterizedTest
    @EnumSource(value = TenantStatus.class, names = "DRAFT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("only a Draft tenant can retry: an Initializing one is still running, the others finished")
    void retry_refusesAnyOtherStatus(TenantStatus status) {
        when(tenantInfoService.getById(TENANT_ID)).thenReturn(Optional.of(tenantIn(status)));

        assertThatThrownBy(() -> service.retrySetup(TENANT_ID)).isInstanceOf(BusinessException.class);

        verify(provisioningStatusService, never()).beginProvisioning(anyLong());
        verify(eventPublisher, never()).publishEvent(any(TenantProvisionedEvent.class));
    }

    // ─── the code slug ───

    @Test
    @DisplayName("a blank code is slugged from the name")
    void blankCode_sluggedFromName() {
        ProvisionTenantRequest request = request(null);
        request.setName("  Acme Corp (APAC)!  ");
        request.setCode(null);

        assertThat(service.provision(request).code()).isEqualTo("acme-corp-apac");
    }

    @Test
    @DisplayName("a name with nothing sluggable still yields a usable code")
    void unsluggableName_fallsBackRatherThanEmpty() {
        // An empty code would collide with every other empty code on the unique index, so the second such
        // tenant could never be created.
        ProvisionTenantRequest request = request(null);
        request.setName("...");
        request.setCode(null);

        assertThat(service.provision(request).code()).isEqualTo("tenant");
    }

    @Test
    @DisplayName("a supplied code wins over the name")
    void suppliedCodeWins() {
        ProvisionTenantRequest request = request(null);
        request.setCode("Acme_APAC");

        assertThat(service.provision(request).code()).isEqualTo("acme-apac");
    }

    // ─── fixtures ───

    private ProvisionTenantRequest request(SubscriptionPeriodPatch periods) {
        ProvisionTenantRequest request = new ProvisionTenantRequest();
        request.setName("Acme Corp");
        if (periods != null) {
            ProvisionTenantRequest.SubscriptionInput input = new ProvisionTenantRequest.SubscriptionInput();
            input.setPeriods(periods);
            request.setSubscriptionId(input);
        }
        return request;
    }

    /** The tenant as re-read after creation, which is where the period recording gets the subscription id. */
    private TenantInfo provisionedTenant() {
        TenantInfo tenant = new TenantInfo();
        tenant.setId(TENANT_ID);
        tenant.setSubscriptionId(SUB_ID);
        return tenant;
    }

    /** An existing tenant in the given status. */
    private TenantInfo tenantIn(TenantStatus status) {
        TenantInfo tenant = provisionedTenant();
        tenant.setName("Acme Corp");
        tenant.setCode("acme-corp");
        tenant.setStatus(status);
        return tenant;
    }
}
