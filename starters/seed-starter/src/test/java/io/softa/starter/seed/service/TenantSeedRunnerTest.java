package io.softa.starter.seed.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.seed.dto.FileState;
import io.softa.starter.seed.entity.SeedLoadRecord;
import io.softa.starter.seed.entity.SysPreData;
import io.softa.starter.seed.event.SeedFileLoadedEvent;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedManifest;
import io.softa.starter.seed.manifest.SeedManifestReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A tenant runs what is pending, in order, stopping at its first failure; initializing records what a tenant
 * already has and changes nothing.
 */
class TenantSeedRunnerTest {

    private static final Long TENANT = 7L;
    private static final SeedManifest MANIFEST = SeedManifestReader.read(new ByteArrayInputStream("""
            tenant:
              - files:
                  - data-tenant/SysSequence.json
                  - file: data-tenant/Role.Tenant.json
                    overwrite: true
                  - data-tenant/LeaveType.json
            """.getBytes(StandardCharsets.UTF_8)), "test", path -> true);

    private SeedLoadLog loadLog;
    private TenantSeedPlanner planner;
    private SysPreDataService preDataService;
    private CacheService cacheService;
    private ApplicationEventPublisher eventPublisher;
    private TenantSeedRunner runner;
    private final Map<String, FileState> states = new HashMap<>();

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @BeforeEach
    void setUp() {
        SeedCatalog catalog = mock(SeedCatalog.class);
        when(catalog.manifest()).thenReturn(MANIFEST);
        when(catalog.checksum(any(SeedFile.class))).thenReturn("v1");
        loadLog = mock(SeedLoadLog.class);
        when(loadLog.tenantRecords(anyList())).thenReturn(Map.of());
        when(loadLog.start(any(), any(), any())).thenReturn(42L);
        when(loadLog.state(any(SeedFile.class), any())).thenAnswer(call ->
                states.getOrDefault(((SeedFile) call.getArgument(0)).file(), FileState.NOT_LOADED));
        planner = mock(TenantSeedPlanner.class);
        when(planner.dueFiles(TENANT)).thenReturn(MANIFEST.tenantFiles());
        when(planner.tenantName(TENANT)).thenReturn("Acme");
        preDataService = mock(SysPreDataService.class);
        cacheService = mock(CacheService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        runner = new TenantSeedRunner(catalog, loadLog, planner, preDataService, cacheService,
                mock(PlatformTransactionManager.class), eventPublisher);
    }

    @Test
    void whatIsPendingRunsInOrderEachInItsMode() {
        states.put("LeaveType.json", FileState.LOADED);

        TenantSeedRunner.TenantOutcome outcome = runner.runTenant(42L, TENANT);

        assertFalse(outcome.failed());
        assertEquals(2, outcome.files());
        verify(preDataService).loadPreTenantData(List.of("SysSequence.json"), TENANT, LoadMode.ADDITIVE);
        verify(preDataService).loadPreTenantData(List.of("Role.Tenant.json"), TENANT, LoadMode.OVERWRITE);
        verify(preDataService, never()).loadPreTenantData(eq(List.of("LeaveType.json")), anyLong(), any());
        verify(loadLog).record(eq(42L), eq(TENANT), eq("Acme"), eq(file("SysSequence.json")),
                eq(SeedRecordStatus.SUCCEEDED), any(), any());
        // New roles change what the tenant's users may do.
        verify(cacheService).clearByPrefix("perm:7:");
    }

    @Test
    void aFileAnnouncesTheRowsItCreatedInTheTenantsContext() {
        states.put("Role.Tenant.json", FileState.LOADED);
        states.put("LeaveType.json", FileState.LOADED);
        // The load reports the rows it created — not the ones an earlier load had bound.
        when(preDataService.loadPreTenantData(List.of("SysSequence.json"), TENANT, LoadMode.ADDITIVE))
                .thenReturn(Map.of("SysSequence", List.of("102")));
        List<Long> tenantsSeenByListener = new ArrayList<>();
        doAnswer(call -> tenantsSeenByListener.add(ContextHolder.getContext().getTenantId()))
                .when(eventPublisher).publishEvent(any(Object.class));

        runner.runTenant(42L, TENANT);

        verify(eventPublisher).publishEvent(
                new SeedFileLoadedEvent(TENANT, "SysSequence.json", Map.of("SysSequence", List.of("102"))));
        assertEquals(List.of(TENANT), tenantsSeenByListener);
    }

    @Test
    void aFileThatCreatedNothingAnnouncesNothing() {
        runner.runTenant(42L, TENANT);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aTenantStopsAtItsFirstFailure() {
        doThrow(new IllegalStateException("duplicate key")).when(preDataService)
                .loadPreTenantData(List.of("Role.Tenant.json"), TENANT, LoadMode.OVERWRITE);

        TenantSeedRunner.TenantOutcome outcome = runner.runTenant(42L, TENANT);

        assertTrue(outcome.failed());
        assertTrue(outcome.error().startsWith("Role.Tenant.json: "), outcome.error());
        assertEquals(1, outcome.files());
        verify(loadLog).record(eq(42L), eq(TENANT), eq("Acme"), eq(file("Role.Tenant.json")),
                eq(SeedRecordStatus.FAILED), any(), any());
        // Later files may refer to the failed one's rows.
        verify(preDataService, never()).loadPreTenantData(eq(List.of("LeaveType.json")), anyLong(), any());
    }

    @Test
    void aTenantWithNothingPendingIsLeftAlone() {
        MANIFEST.tenantFiles().forEach(file -> states.put(file.file(), FileState.LOADED));

        TenantSeedRunner.TenantOutcome outcome = runner.runTenant(42L, TENANT);

        assertEquals(0, outcome.files());
        verify(preDataService, never()).loadPreTenantData(anyList(), anyLong(), any());
        verify(cacheService, never()).clearByPrefix(any());
    }

    @Test
    void aSetupThatFailsIsRecordedAndThrown() {
        doThrow(new IllegalStateException("boom")).when(preDataService)
                .loadPreTenantData(List.of("SysSequence.json"), TENANT, LoadMode.ADDITIVE);

        assertThrows(BusinessException.class, () -> runner.provision(TENANT));

        verify(loadLog).start(SeedLoadType.PROVISION, List.of(TENANT), 1);
        verify(loadLog).finish(eq(42L), eq(SeedLoadStatus.FAILED), anyInt(), any());
    }

    @Test
    void initializingRecordsTheLoadOnceFilesATenantAlreadyHas() {
        SysPreData binding = new SysPreData();
        binding.setModel("SysSequence");
        binding.setPreId("Employee.code");
        when(preDataService.searchList(any(Filters.class))).thenReturn(List.of(binding));
        when(preDataService.filePreIds("data-tenant/", "SysSequence.json"))
                .thenReturn(Map.of("SysSequence", List.of("Employee.code")));
        when(preDataService.filePreIds("data-tenant/", "LeaveType.json"))
                .thenReturn(Map.of("LeaveType", List.of("leave_type.annual")));

        TenantSeedRunner.TenantOutcome outcome = runner.recordExisting(42L, TENANT, new HashMap<>());

        assertEquals(1, outcome.files());
        verify(loadLog).record(eq(42L), eq(TENANT), eq("Acme"), eq(file("SysSequence.json")),
                eq(SeedRecordStatus.SUCCEEDED), any(), any());
        // A file the tenant has nothing of runs on the next run; an overwrite file runs anyway.
        verify(loadLog, never()).record(any(), any(), any(), eq(file("LeaveType.json")), any(), any(), any());
        verify(loadLog, never()).record(any(), any(), any(), eq(file("Role.Tenant.json")), any(), any(), any());
        // And no data is written.
        verify(preDataService, never()).loadPreTenantData(anyList(), anyLong(), any());
    }

    @Test
    void initializingSkipsAFileTheTenantAlreadyRan() {
        SeedLoadRecord ran = new SeedLoadRecord();
        ran.setStatus(SeedRecordStatus.SUCCEEDED);
        when(loadLog.tenantRecords(anyList())).thenReturn(
                Map.of(TENANT, Map.of("SysSequence.json", new SeedLoadLog.FileRecords(ran, ran))));
        SysPreData binding = new SysPreData();
        binding.setModel("SysSequence");
        binding.setPreId("Employee.code");
        when(preDataService.searchList(any(Filters.class))).thenReturn(List.of(binding));
        when(preDataService.filePreIds(any(), any())).thenReturn(Map.of("SysSequence", List.of("Employee.code")));

        runner.recordExisting(42L, TENANT, new HashMap<>());

        verify(loadLog, never()).record(any(), any(), any(), eq(file("SysSequence.json")), any(), any(), any());
    }

    @Test
    void anEmptyPickRunsNobodyRatherThanEverybody() {
        assertThrows(BusinessException.class, () -> runner.run(List.of()));
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void anEmptyEntryInThePickIsRefused() {
        BusinessException refused = assertThrows(BusinessException.class,
                () -> runner.run(Arrays.asList(TENANT, null)));

        assertTrue(refused.getMessage().contains("empty entry"), refused.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void noRunBeforeInitialize() {
        // Existing tenants have rows and no records yet: without the initialization, the run would load every
        // tenant file again over what they changed.
        when(loadLog.initialized()).thenReturn(false);

        BusinessException refused = assertThrows(BusinessException.class, () -> runner.run(null));

        assertTrue(refused.getMessage().contains("Load the platform files once"), refused.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void theInitializationsRecordingRunsInTheCallerAndSaysHowItWent() {
        // The initialization answers with what it recorded, so the recording is done by the time it returns.
        when(planner.provisionedTenantIds()).thenReturn(List.of(TENANT));
        SysPreData binding = new SysPreData();
        binding.setModel("SysSequence");
        binding.setPreId("Employee.code");
        when(preDataService.searchList(any(Filters.class))).thenReturn(List.of(binding));
        when(preDataService.filePreIds(any(), any())).thenReturn(Map.of("SysSequence", List.of("Employee.code")));

        TenantSeedRunner.BatchOutcome outcome = runner.recordExistingFiles();

        assertEquals(42L, outcome.batchId());
        assertEquals(SeedLoadStatus.SUCCEEDED, outcome.status());
        assertEquals(1, outcome.tenants());
        assertEquals(0, outcome.failedTenants());
        verify(loadLog).start(SeedLoadType.INITIALIZE, null, 1);
        verify(loadLog).finish(42L, SeedLoadStatus.SUCCEEDED, outcome.files(), null);
    }

    private static SeedFile file(String name) {
        return MANIFEST.file(name).orElseThrow();
    }
}
