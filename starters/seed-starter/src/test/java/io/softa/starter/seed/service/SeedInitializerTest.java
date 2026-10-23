package io.softa.starter.seed.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.seed.dto.InitializeResult;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedManifest;
import io.softa.starter.seed.manifest.SeedManifestReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Initializing an environment, once: the tenant files tenants already have are recorded as run, and then the
 * platform files load — every one in a new environment, the named ones where platform data is already held.
 */
class SeedInitializerTest {

    private static final SeedManifest MANIFEST = SeedManifestReader.read(new ByteArrayInputStream("""
            platform:
              - data-system/Navigation.json
              - data-system/Permission.json
            """.getBytes(StandardCharsets.UTF_8)), "test", path -> true);
    private static final List<SeedFile> EVERY_FILE = MANIFEST.platformFiles();

    private SeedLoadLog loadLog;
    private PlatformSeedLoader platformLoader;
    private TenantSeedRunner tenantRunner;
    private SysPreDataService preDataService;
    private SeedInitializer initializer;

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @BeforeEach
    void setUp() {
        loadLog = mock(SeedLoadLog.class);
        platformLoader = mock(PlatformSeedLoader.class);
        tenantRunner = mock(TenantSeedRunner.class);
        preDataService = mock(SysPreDataService.class);
        initializer = new SeedInitializer(loadLog, platformLoader, tenantRunner, preDataService);
        when(tenantRunner.recordExistingFiles()).thenReturn(recorded(SeedLoadStatus.SUCCEEDED));
    }

    @Test
    void aNewEnvironmentRecordsItsTenantsAndThenLoadsEveryPlatformFile() {
        holdsPlatformData(false);
        when(platformLoader.pendingForInitialization(null)).thenReturn(EVERY_FILE);
        PlatformLoadResult loaded = new PlatformLoadResult(43L, true, List.of("Navigation.json", "Permission.json"),
                null, null);
        when(platformLoader.loadFiles(EVERY_FILE)).thenReturn(loaded);

        InitializeResult result = initializer.initialize(null);

        // The recording first: it is what initializes the environment.
        InOrder inOrder = inOrder(tenantRunner, platformLoader);
        inOrder.verify(tenantRunner).recordExistingFiles();
        inOrder.verify(platformLoader).loadFiles(EVERY_FILE);
        assertEquals(41L, result.batchId());
        assertEquals(SeedLoadStatus.SUCCEEDED, result.status());
        assertSame(loaded, result.platformLoad());
    }

    @Test
    void anEnvironmentThatHoldsPlatformDataNamesWhatToLoad() {
        // Every file loaded here would go over what was changed on the pages since the old loader ran.
        holdsPlatformData(true);

        BusinessException refused = assertThrows(BusinessException.class, () -> initializer.initialize(null));

        assertTrue(refused.getMessage().contains("Name the files to load"), refused.getMessage());
        verify(tenantRunner, never()).recordExistingFiles();
        verify(platformLoader, never()).loadFiles(anyList());
    }

    @Test
    void theNamedFilesLoadWherePlatformDataIsHeld() {
        holdsPlatformData(true);
        List<SeedFile> navigation = List.of(EVERY_FILE.get(0));
        when(platformLoader.pendingForInitialization(List.of("Navigation.json"))).thenReturn(navigation);

        initializer.initialize(List.of("Navigation.json"));

        verify(tenantRunner).recordExistingFiles();
        verify(platformLoader).loadFiles(navigation);
    }

    @Test
    void aNameThatIsNotPendingIsRefusedBeforeAnythingIsRecorded() {
        when(platformLoader.pendingForInitialization(List.of("Navigaton.json")))
                .thenThrow(new BusinessException("Nothing was loaded: Navigaton.json is not pending."));

        assertThrows(BusinessException.class, () -> initializer.initialize(List.of("Navigaton.json")));

        verify(tenantRunner, never()).recordExistingFiles();
    }

    @Test
    void anEnvironmentIsInitializedOnce() {
        when(loadLog.initialized()).thenReturn(true);

        assertThrows(BusinessException.class, () -> initializer.initialize(List.of("Navigation.json")));

        verify(tenantRunner, never()).recordExistingFiles();
        verify(platformLoader, never()).loadFiles(anyList());
    }

    @Test
    void aRecordingThatFailsOutrightLoadsNothingSoItCanBeDoneAgain() {
        holdsPlatformData(false);
        when(platformLoader.pendingForInitialization(null)).thenReturn(EVERY_FILE);
        when(tenantRunner.recordExistingFiles()).thenReturn(recorded(SeedLoadStatus.FAILED));

        InitializeResult result = initializer.initialize(null);

        assertEquals(SeedLoadStatus.FAILED, result.status());
        assertNull(result.platformLoad());
        verify(platformLoader, never()).loadFiles(anyList());
    }

    @Test
    void nothingPendingStillInitializes() {
        holdsPlatformData(true);
        when(platformLoader.pendingForInitialization(List.of("Navigation.json"))).thenReturn(List.of());

        InitializeResult result = initializer.initialize(List.of("Navigation.json"));

        assertEquals(SeedLoadStatus.SUCCEEDED, result.status());
        assertNull(result.platformLoad());
        verify(platformLoader, never()).loadFiles(anyList());
    }

    private void holdsPlatformData(boolean held) {
        when(preDataService.exist(any(Filters.class))).thenReturn(held);
    }

    private static TenantSeedRunner.BatchOutcome recorded(SeedLoadStatus status) {
        return new TenantSeedRunner.BatchOutcome(41L, status, 3, status == SeedLoadStatus.FAILED ? 3 : 0, 12);
    }
}
