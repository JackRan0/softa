package io.softa.starter.seed.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.seed.dto.FileState;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.entity.SeedLoadRecord;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedManifest;
import io.softa.starter.seed.manifest.SeedManifestReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Platform files load whole, all pending ones or the ones picked, in manifest order and one transaction. An
 * environment's first load initializes it first: the tenant files tenants already have are recorded as run. The
 * bootstrap files load on their own where they never loaded, without initializing.
 */
class PlatformSeedLoaderTest {

    private static final SeedManifest MANIFEST = SeedManifestReader.read(new ByteArrayInputStream("""
            platform:
              - file: data-system/Navigation.json
                bootstrap: true
              - file: data-system/Permission.json
                bootstrap: true
              - data-platform/MailTemplate.Platform.json
            """.getBytes(StandardCharsets.UTF_8)), "test", path -> true);

    private SeedCatalog catalog;
    private SeedLoadLog loadLog;
    private SysPreDataService preDataService;
    private CacheService cacheService;
    private TenantSeedRunner tenantRunner;
    private PlatformSeedLoader loader;

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @BeforeEach
    void setUp() {
        catalog = mock(SeedCatalog.class);
        when(catalog.manifest()).thenReturn(MANIFEST);
        when(catalog.checksum(any(SeedFile.class))).thenReturn("v2");
        loadLog = mock(SeedLoadLog.class);
        // An initialized environment; the tests of the first load say otherwise.
        when(loadLog.initialized()).thenReturn(true);
        when(loadLog.platformRecords()).thenReturn(Map.of());
        when(loadLog.start(any(), any(), any())).thenReturn(42L);
        preDataService = mock(SysPreDataService.class);
        cacheService = mock(CacheService.class);
        tenantRunner = mock(TenantSeedRunner.class);
        when(tenantRunner.recordExistingFiles()).thenReturn(recorded(SeedLoadStatus.SUCCEEDED));
        loader = new PlatformSeedLoader(catalog, loadLog, preDataService, cacheService, tenantRunner,
                mock(PlatformTransactionManager.class));
    }

    @Test
    void everyPendingFileLoadsInManifestOrder() {
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(null);

        assertTrue(result.succeeded());
        assertEquals(List.of("Navigation.json", "Permission.json", "MailTemplate.Platform.json"), result.loadedFiles());
        InOrder inOrder = inOrder(preDataService);
        inOrder.verify(preDataService).loadPreSystemData(List.of("Navigation.json"), LoadMode.OVERWRITE);
        inOrder.verify(preDataService).loadPreSystemData(List.of("Permission.json"), LoadMode.OVERWRITE);
        // The platform tenant's file goes through the platform-tier loader.
        inOrder.verify(preDataService).loadPrePlatformData(List.of("MailTemplate.Platform.json"), LoadMode.OVERWRITE);
        verify(loadLog).start(SeedLoadType.PLATFORM, null, null);
        verify(loadLog).finish(42L, SeedLoadStatus.SUCCEEDED, 3, null);
        // The permission snapshots and entitlements cached from the old rows go.
        verify(cacheService).clearByPrefix("perm:");
        verify(cacheService).clearByPrefix("entl:");
    }

    @Test
    void onlyThePickedFilesLoadAndTheRestStayPending() {
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(List.of("MailTemplate.Platform.json", "Navigation.json"));

        // Manifest order, whatever order they were picked in.
        assertEquals(List.of("Navigation.json", "MailTemplate.Platform.json"), result.loadedFiles());
        verify(preDataService, never()).loadPreSystemData(eq(List.of("Permission.json")), any());
    }

    @Test
    void aFileThatIsNotPendingIsRefusedBeforeAnythingLoads() {
        pending("Navigation.json");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> loader.load(List.of("Navigation.json", "Permission.json")));

        assertTrue(failure.getMessage().contains("Permission.json"), failure.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void aFileANewerBuildLoadedIsRefusedByName() {
        pending("Navigation.json");
        when(loadLog.state(eq(file("Permission.json")), any())).thenReturn(FileState.LOADED_BY_NEWER_BUILD);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> loader.load(List.of("Navigation.json", "Permission.json")));

        assertTrue(failure.getMessage().contains("Permission.json"), failure.getMessage());
        assertTrue(failure.getMessage().contains("newer build"), failure.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void anEmptyPickLoadsNothingRatherThanEverything() {
        pending("Navigation.json", "Permission.json");

        assertThrows(BusinessException.class, () -> loader.load(List.of()));
        verify(loadLog, never()).start(any(), any(), any());
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void nothingPendingIsSaidSo() {
        pending();

        assertThrows(BusinessException.class, () -> loader.load(null));
    }

    @Test
    void aFailureNamesTheFileAndLoadsNothing() {
        pending("Navigation.json", "Permission.json");
        doThrow(new IllegalStateException("preIDs [navigation.x] do not exist"))
                .when(preDataService).loadPreSystemData(List.of("Permission.json"), LoadMode.OVERWRITE);

        PlatformLoadResult result = loader.load(null);

        assertFalse(result.succeeded());
        assertEquals("Permission.json", result.failedFile());
        assertTrue(result.errorMessage().contains("do not exist"), result.errorMessage());
        verify(loadLog).record(eq(42L), isNull(), isNull(), eq(file("Permission.json")), eq(SeedRecordStatus.FAILED),
                any(), any());
        verify(loadLog).finish(eq(42L), eq(SeedLoadStatus.FAILED), anyInt(), any());
        verify(cacheService, never()).clearByPrefix(any());
    }

    // ─────────────────────────── the first load initializes ───────────────────────────

    @Test
    void theFirstLoadRecordsTheTenantsAndThenLoads() {
        firstLoad(false);
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(null);

        // The recording first: it is what initializes the environment.
        InOrder inOrder = inOrder(tenantRunner, preDataService);
        inOrder.verify(tenantRunner).recordExistingFiles();
        inOrder.verify(preDataService).loadPreSystemData(List.of("Navigation.json"), LoadMode.OVERWRITE);
        assertTrue(result.succeeded());
        assertEquals(42L, result.batchId());
        assertEquals(41L, result.initialization().batchId());
        assertEquals(SeedLoadStatus.SUCCEEDED, result.initialization().status());
        assertEquals(12, result.initialization().recordedFiles());
    }

    @Test
    void theFirstLoadWherePlatformDataIsHeldPicksItsFiles() {
        // Every file loaded here would go over what was changed on the pages since the old loader ran.
        firstLoad(true);
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        BusinessException refused = assertThrows(BusinessException.class, () -> loader.load(null));

        assertTrue(refused.getMessage().contains("Pick the files to load"), refused.getMessage());
        verify(tenantRunner, never()).recordExistingFiles();
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void thePickedFilesLoadOnTheFirstLoadWherePlatformDataIsHeld() {
        firstLoad(true);
        pending("Navigation.json", "Permission.json", "MailTemplate.Platform.json");

        PlatformLoadResult result = loader.load(List.of("Navigation.json", "Permission.json"));

        verify(tenantRunner).recordExistingFiles();
        assertEquals(List.of("Navigation.json", "Permission.json"), result.loadedFiles());
        verify(preDataService, never()).loadPrePlatformData(anyList(), any());
    }

    @Test
    void aNameThatIsNotPendingIsRefusedBeforeAnythingIsRecorded() {
        firstLoad(true);
        pending("Navigation.json");

        assertThrows(BusinessException.class, () -> loader.load(List.of("Navigaton.json")));

        verify(tenantRunner, never()).recordExistingFiles();
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void aRecordingThatFailsOutrightLoadsNothingSoTheNextLoadTriesAgain() {
        firstLoad(false);
        pending("Navigation.json");
        when(tenantRunner.recordExistingFiles()).thenReturn(recorded(SeedLoadStatus.FAILED));

        PlatformLoadResult result = loader.load(null);

        assertFalse(result.succeeded());
        assertNull(result.batchId());
        assertEquals(SeedLoadStatus.FAILED, result.initialization().status());
        verify(loadLog, never()).start(eq(SeedLoadType.PLATFORM), any(), any());
        verify(preDataService, never()).loadPreSystemData(anyList(), any());
    }

    @Test
    void aLaterLoadRecordsNothing() {
        pending("Navigation.json");

        PlatformLoadResult result = loader.load(null);

        assertNull(result.initialization());
        verify(tenantRunner, never()).recordExistingFiles();
    }

    @Test
    void aTransactionThatCannotStartClosesTheBatchWithItsCause() {
        pending("Navigation.json");
        PlatformTransactionManager unavailable = mock(PlatformTransactionManager.class);
        when(unavailable.getTransaction(any())).thenThrow(new CannotCreateTransactionException("pool exhausted"));
        PlatformSeedLoader noTransaction = new PlatformSeedLoader(catalog, loadLog, preDataService, cacheService,
                tenantRunner, unavailable);

        PlatformLoadResult result = noTransaction.load(null);

        // No file started, so none is blamed — and the batch is closed, not left Running until it reads as stale.
        assertFalse(result.succeeded());
        assertNull(result.failedFile());
        assertTrue(result.errorMessage().contains("pool exhausted"), result.errorMessage());
        verify(loadLog).finish(eq(42L), eq(SeedLoadStatus.FAILED), eq(0), any());
        verify(loadLog, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEmptyEntryInThePickIsRefused() {
        pending("Navigation.json");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> loader.load(Arrays.asList("Navigation.json", null)));

        assertTrue(failure.getMessage().contains("empty entry"), failure.getMessage());
        verify(loadLog, never()).start(any(), any(), any());
    }

    @Test
    void theBootstrapFilesThatNeverLoadedLoadWithoutInitializing() {
        firstLoad(false);

        PlatformLoadResult result = loader.loadBootstrapFiles().orElseThrow();

        assertTrue(result.succeeded());
        assertEquals(List.of("Navigation.json", "Permission.json"), result.loadedFiles());
        verify(preDataService, never()).loadPrePlatformData(anyList(), any());
        // Initializing is the first load from the page, after what the deployment runs once the build is up.
        verify(tenantRunner, never()).recordExistingFiles();
        verify(loadLog).requireIdle();
        verify(cacheService).clearByPrefix("perm:");
    }

    @Test
    void aBootstrapFileThatLoadedHereOnceIsLeftAlone() {
        // Navigation loaded once and has changed since: the page loads that. Permission only ever failed.
        when(loadLog.platformRecords()).thenReturn(Map.of(
                "Navigation.json", new SeedLoadLog.FileRecords(record(SeedRecordStatus.SUCCEEDED),
                        record(SeedRecordStatus.SUCCEEDED)),
                "Permission.json", new SeedLoadLog.FileRecords(record(SeedRecordStatus.FAILED), null)));

        PlatformLoadResult result = loader.loadBootstrapFiles().orElseThrow();

        assertEquals(List.of("Permission.json"), result.loadedFiles());
        verify(preDataService, never()).loadPreSystemData(eq(List.of("Navigation.json")), any());
    }

    @Test
    void nothingLoadsOnceEveryBootstrapFileHasLoadedHere() {
        when(loadLog.platformRecords()).thenReturn(Map.of(
                "Navigation.json", new SeedLoadLog.FileRecords(record(SeedRecordStatus.SUCCEEDED),
                        record(SeedRecordStatus.SUCCEEDED)),
                "Permission.json", new SeedLoadLog.FileRecords(record(SeedRecordStatus.SUCCEEDED),
                        record(SeedRecordStatus.SUCCEEDED))));

        assertTrue(loader.loadBootstrapFiles().isEmpty());
        verify(loadLog, never()).requireIdle();
        verify(loadLog, never()).start(any(), any(), any());
    }

    private static SeedLoadRecord record(SeedRecordStatus status) {
        SeedLoadRecord record = new SeedLoadRecord();
        record.setStatus(status);
        return record;
    }

    /** An environment not initialized yet, which held platform seed data before the records, or did not. */
    private void firstLoad(boolean holdsPlatformData) {
        when(loadLog.initialized()).thenReturn(false);
        when(preDataService.exist(any(Filters.class))).thenReturn(holdsPlatformData);
    }

    private static TenantSeedRunner.BatchOutcome recorded(SeedLoadStatus status) {
        return new TenantSeedRunner.BatchOutcome(41L, status, 3, status == SeedLoadStatus.FAILED ? 3 : 0, 12);
    }

    /** The named platform files are pending; the others are loaded. */
    private void pending(String... names) {
        List<String> pending = List.of(names);
        for (SeedFile file : MANIFEST.platformFiles()) {
            when(loadLog.state(eq(file), any())).thenReturn(pending.contains(file.file())
                    ? FileState.NOT_LOADED : FileState.LOADED);
        }
    }

    private static SeedFile file(String name) {
        return MANIFEST.file(name).orElseThrow();
    }
}
