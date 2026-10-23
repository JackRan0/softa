package io.softa.starter.seed.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.seed.dto.InitializeResult;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.entity.SysPreData;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.manifest.SeedFile;

/**
 * Initializes an environment for seed loading: once, through the API, before anything else is loaded or run.
 *
 * <p>It records as run, in every set-up tenant, the tenant files the tenant already has rows of, so the first
 * tenant run does not load them again over what the tenants changed since; then it loads the platform files —
 * the environment's first platform load. Platform loads and tenant runs are refused until it has run; setting
 * up a new tenant is not.
 *
 * <p>A new environment names no file: every platform file loads, and no tenant has rows to record. An
 * environment that already holds platform seed data, loaded before the records existed, has to name the files
 * to load now — loading every one would put each over what was changed on the pages since. It names the files
 * the Seed Data page itself needs (its menu and permissions), restarts, and loads the rest from that page,
 * where it can leave out the files changed on the pages.
 *
 * <p>The recording comes first and decides, for it is what initializes the environment. When it fails
 * outright, nothing is loaded and the call can be made again. When the platform load fails after it, the
 * environment is initialized, and the files load from the page like any other time.
 */
@Slf4j
@Service
public class SeedInitializer {

    private final SeedLoadLog loadLog;
    private final PlatformSeedLoader platformLoader;
    private final TenantSeedRunner tenantRunner;
    private final SysPreDataService preDataService;

    public SeedInitializer(SeedLoadLog loadLog, PlatformSeedLoader platformLoader, TenantSeedRunner tenantRunner,
                           SysPreDataService preDataService) {
        this.loadLog = loadLog;
        this.platformLoader = platformLoader;
        this.tenantRunner = tenantRunner;
        this.preDataService = preDataService;
    }

    /**
     * Initialize this environment, in the caller.
     *
     * @param fileNames the platform files to load, which must be pending; null for every pending one, refused
     *                  where platform seed data is already held. An empty list is refused
     * @return what was recorded and loaded
     */
    public InitializeResult initialize(List<String> fileNames) {
        if (loadLog.initialized()) {
            throw new BusinessException("This environment was initialized already. It is done once; load platform "
                    + "files and run tenants from the Seed Data page.");
        }
        if (fileNames == null && holdsPlatformSeedData()) {
            throw new BusinessException("Nothing was done: this environment already holds platform seed data, and "
                    + "loading every platform file would put each one over what was changed on the pages since. "
                    + "Name the files to load now — the ones the Seed Data page's menu and permissions are in — "
                    + "then restart and load the rest from that page.");
        }
        // The names are checked before anything is written, so a typo costs nothing.
        List<SeedFile> platformFiles = platformLoader.pendingForInitialization(fileNames);
        loadLog.requireIdle();
        TenantSeedRunner.BatchOutcome recorded = tenantRunner.recordExistingFiles();
        PlatformLoadResult platformLoad = null;
        if (recorded.status() == SeedLoadStatus.FAILED) {
            log.error("Initialization batch {} recorded nothing it could rely on; no platform file was loaded. "
                    + "Fix the cause and initialize again.", recorded.batchId());
        } else if (!platformFiles.isEmpty()) {
            platformLoad = platformLoader.loadFiles(platformFiles);
        }
        return new InitializeResult(recorded.batchId(), recorded.status(), recorded.tenants(),
                recorded.failedTenants(), recorded.files(), platformLoad);
    }

    /** Whether platform seed rows were loaded here before: bindings at system scope. */
    private boolean holdsPlatformSeedData() {
        return ContextUtils.inSystemContext(() -> preDataService.exist(new Filters().isNotSet(SysPreData::getTenantId)));
    }
}
