package io.softa.starter.seed.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.seed.dto.FileState;
import io.softa.starter.seed.dto.PlatformFileStatus;
import io.softa.starter.seed.dto.PlatformLoadResult;
import io.softa.starter.seed.dto.PlatformSeedStatus;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;
import io.softa.starter.seed.enums.SeedRecordStatus;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedLevel;

/**
 * Loads the platform seed files — the shared rows every tenant reads, and the platform tenant's own.
 *
 * <p>A platform file is the data, so it is loaded whole whenever it was never loaded or has changed since. The
 * pending files load in one transaction, in manifest order: either all of them or none. Afterwards the
 * permission snapshots and entitlements cached in Redis are cleared; what each instance built in memory at
 * startup (endpoint index, sensitive field sets, navigation, cron schedules) is rebuilt by restarting it.
 */
@Slf4j
@Service
public class PlatformSeedLoader {

    /** Where the permission engine caches each user's snapshot: {@code perm:{tenant}:user:{user}}. */
    static final String PERMISSION_CACHE_PREFIX = "perm:";

    private final SeedCatalog catalog;
    private final SeedLoadLog loadLog;
    private final SysPreDataService preDataService;
    private final CacheService cacheService;
    private final TransactionTemplate transaction;

    public PlatformSeedLoader(SeedCatalog catalog, SeedLoadLog loadLog, SysPreDataService preDataService,
                              CacheService cacheService, PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.loadLog = loadLog;
        this.preDataService = preDataService;
        this.cacheService = cacheService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The platform files of the running build against what the platform last loaded. */
    public PlatformSeedStatus status() {
        Map<String, SeedLoadLog.FileRecords> records = loadLog.platformRecords();
        List<PlatformFileStatus> files = catalog.manifest().platformFiles().stream().map(file -> {
            SeedLoadLog.FileRecords fileRecords = records.get(file.file());
            var success = fileRecords == null ? null : fileRecords.latestSuccess();
            var latest = fileRecords == null ? null : fileRecords.latest();
            return new PlatformFileStatus(file.file(), file.level(), loadLog.state(file, fileRecords),
                    catalog.checksum(file),
                    success == null ? null : success.getChecksum(),
                    success == null ? null : success.getEndTime(),
                    success == null ? null : success.getCreatedBy(),
                    latest != null && latest.getStatus() == SeedRecordStatus.FAILED ? latest.getErrorMessage() : null);
        }).toList();
        LocalDateTime lastLoadedAt = loadLog.lastPlatformLoad();
        return new PlatformSeedStatus(catalog.appVersion(), catalog.buildTime(), catalog.startedAt(), lastLoadedAt,
                lastLoadedAt != null && lastLoadedAt.isAfter(catalog.startedAt()), loadLog.initialized(),
                loadLog.running().orElse(null), files);
    }

    /**
     * Load the pending platform files — all of them, or the ones named.
     *
     * <p>Refused until the environment is initialized: its first platform load is part of initializing it
     * ({@link SeedInitializer}), where an environment that already holds platform data names what to load.
     *
     * @param fileNames pending files to load, in manifest order whatever order they are named in, the others
     *                  staying pending; null for every pending one. An empty list is refused: whoever sends
     *                  one picked nothing, which is the opposite of everything
     * @return how it went; on a failure nothing is loaded
     */
    public PlatformLoadResult load(List<String> fileNames) {
        if (!loadLog.initialized()) {
            throw new BusinessException("Nothing was loaded: this environment has not been initialized. Its first "
                    + "platform load is part of that: call POST /SeedData/initialize once, then restart.");
        }
        loadLog.requireIdle();
        return loadFiles(selectPending(fileNames, true));
    }

    /**
     * The platform files initializing an environment loads: the named ones, which must be pending, or every
     * pending one — none, when nothing is. Checked before the initialization writes anything.
     */
    List<SeedFile> pendingForInitialization(List<String> fileNames) {
        return selectPending(fileNames, false);
    }

    /**
     * Load these platform files, in manifest order and one transaction, and record them. The caller has made
     * sure nothing else runs.
     *
     * @return how it went; on a failure nothing is loaded
     */
    PlatformLoadResult loadFiles(List<SeedFile> files) {
        Long batchId = loadLog.start(SeedLoadType.PLATFORM, null, null);
        SeedFile[] current = new SeedFile[1];
        LocalDateTime[] started = new LocalDateTime[1];
        try {
            transaction.executeWithoutResult(status -> {
                for (SeedFile file : files) {
                    current[0] = file;
                    started[0] = LocalDateTime.now();
                    if (file.level() == SeedLevel.PLATFORM_TENANT) {
                        preDataService.loadPrePlatformData(List.of(file.file()), LoadMode.OVERWRITE);
                    } else {
                        preDataService.loadPreSystemData(List.of(file.file()), LoadMode.OVERWRITE);
                    }
                    loadLog.record(batchId, null, null, file, SeedRecordStatus.SUCCEEDED, null, started[0]);
                }
            });
        } catch (RuntimeException e) {
            String error = SeedErrors.messageOf(e);
            if (current[0] == null) {
                // Before the first file — the transaction could not even start: no file has a record to write,
                // and the batch carries the cause alone, rather than staying Running until it reads as stale.
                log.error("Platform seed load {} failed before loading any file", batchId, e);
                loadLog.finish(batchId, SeedLoadStatus.FAILED, 0, error);
                return new PlatformLoadResult(batchId, false, List.of(), null, error);
            }
            log.error("Platform seed load {} failed on {}", batchId, current[0].file(), e);
            loadLog.record(batchId, null, null, current[0], SeedRecordStatus.FAILED, error, started[0]);
            loadLog.finish(batchId, SeedLoadStatus.FAILED, 0, current[0].file() + ": " + error);
            return new PlatformLoadResult(batchId, false, List.of(), current[0].file(), error);
        }
        loadLog.finish(batchId, SeedLoadStatus.SUCCEEDED, files.size(), null);
        clearCachedPermissions();
        log.info("Platform seed load {}: {} file(s) loaded; restart the instances to rebuild their in-memory indexes",
                batchId, files.size());
        return new PlatformLoadResult(batchId, true, files.stream().map(SeedFile::file).toList(), null, null);
    }

    /**
     * The pending platform files, or the named ones among them — all of which must be pending.
     *
     * @param requireSome whether nothing pending is refused when no file is named
     */
    private List<SeedFile> selectPending(List<String> fileNames, boolean requireSome) {
        if (fileNames != null && fileNames.isEmpty()) {
            throw new BusinessException("Nothing was loaded: no platform file was picked.");
        }
        if (fileNames != null && fileNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new BusinessException("Nothing was loaded: the list of platform files holds an empty entry.");
        }
        Map<String, SeedLoadLog.FileRecords> records = loadLog.platformRecords();
        Map<SeedFile, FileState> states = new LinkedHashMap<>();
        catalog.manifest().platformFiles()
                .forEach(file -> states.put(file, loadLog.state(file, records.get(file.file()))));
        List<SeedFile> pending = states.entrySet().stream()
                .filter(entry -> entry.getValue().pending())
                .map(Map.Entry::getKey)
                .toList();
        if (fileNames == null) {
            if (pending.isEmpty() && requireSome) {
                throw new BusinessException("Every platform seed file is loaded already.");
            }
            return pending;
        }
        Set<String> named = new LinkedHashSet<>(fileNames);
        List<String> loadedByNewerBuild = states.entrySet().stream()
                .filter(entry -> entry.getValue() == FileState.LOADED_BY_NEWER_BUILD
                        && named.contains(entry.getKey().file()))
                .map(entry -> entry.getKey().file())
                .toList();
        if (!loadedByNewerBuild.isEmpty()) {
            throw new BusinessException("Nothing was loaded: {0} was loaded by a newer build than the one running. "
                    + "Loading it here would put older content over newer — deploy that build again.",
                    String.join(", ", loadedByNewerBuild));
        }
        List<String> notPending = new ArrayList<>(named);
        notPending.removeAll(pending.stream().map(SeedFile::file).toList());
        if (!notPending.isEmpty()) {
            throw new BusinessException("Nothing was loaded: {0} is not pending. Refresh the page and try again.",
                    String.join(", ", notPending));
        }
        return pending.stream().filter(file -> named.contains(file.file())).toList();
    }

    /**
     * Platform rows feed every tenant's permission snapshot and entitlement, cached for up to a day. A failure
     * is only logged: the data is in, and the caches expire on their own.
     */
    private void clearCachedPermissions() {
        for (String prefix : List.of(PERMISSION_CACHE_PREFIX, RedisConstant.ENTITLEMENT)) {
            try {
                cacheService.clearByPrefix(prefix);
            } catch (RuntimeException e) {
                log.error("Platform seed load could not clear the {}* cache", prefix, e);
            }
        }
    }
}
