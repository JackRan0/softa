package io.softa.starter.metadata.controller;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.metadata.dto.RuntimeExportFilter;
import io.softa.starter.metadata.service.MetadataApplyService;
import io.softa.starter.metadata.service.MetadataService;

/**
 * Metadata Upgrade controller.
 * <p>
 * Every endpoint under {@code /upgrade/**} is studio-internal: it is called
 * over the public network by a remote studio env and must carry a valid
 * Ed25519 signature. Enforcement is purely path-scoped — the prefix wires
 * into {@code ContextScopeFilter} (as the {@code INTERNAL} bucket — service
 * context, no user, cross-tenant) and {@code SignatureVerificationFilter}
 * (URL-scoped, unconditional verification). Adding a handler here means
 * accepting both contracts.
 * <p>
 * On top of the signature, every routing endpoint verifies the requested
 * {@code appCode} against this runtime's {@code system.app-code}:
 * the signature proves <i>who</i> is calling, the app-code handshake proves
 * the call was addressed to <i>this app</i> — a mis-configured endpoint URL
 * can no longer land an envelope on the wrong runtime. One runtime hosts one
 * app (composite multi-app runtimes are out of scope).
 */
@Tag(name = "Metadata upgrade API")
@RestController
@RequestMapping("/upgrade/runtime")
public class UpgradeController {

    private final MetadataService metadataService;
    private final MetadataApplyService metadataApplyService;
    private final SystemConfig systemConfig;

    public UpgradeController(MetadataService metadataService,
                             MetadataApplyService metadataApplyService,
                             SystemConfig systemConfig) {
        this.metadataService = metadataService;
        this.metadataApplyService = metadataApplyService;
        this.systemConfig = systemConfig;
    }

    /**
     * App-identity handshake: reject any signed request addressed
     * to a different app, fail-closed, before any work is dispatched.
     */
    private void assertAppCode(String requested) {
        String configured = systemConfig.getAppCode();
        Assert.notBlank(configured,
                "system.app-code is not configured on this runtime; metadata upgrade APIs are unavailable.");
        Assert.isEqual(configured, requested,
                "Requested appCode {0} does not match this runtime's app identity {1}; "
                        + "the request was addressed to a different app (check the env binding).",
                requested, configured);
    }

    /**
     * Export runtime metadata rows for a model, scoped to the requested app
     * identity.
     * <p>
     * Used by the studio to compare design-time state with runtime state. The
     * {@code appCode} doubles as the handshake: it must equal this runtime's
     * {@code system.app-code}, otherwise the export was addressed to a different
     * app and is rejected.
     *
     * <p>
     * An optional {@link RuntimeExportFilter} body narrows the export to a set of aggregate business
     * keys (incremental fetch): the studio deploy pulls only the aggregates whose
     * checksum differs instead of the whole catalog. No body (or a null {@code keyColumn}) = full export.
     *
     * @param modelName runtime model name
     * @param appCode   app identity the caller is synchronising
     * @param filter    optional per-aggregate key filter; {@code null} / null keyColumn = full export
     * @return list of row data maps
     */
    @Operation(summary = "exportRuntimeMetadata", description = "Export runtime metadata rows for a studio-managed model, scoped to an app")
    @PostMapping("/exportRuntimeMetadata")
    public ApiResponse<List<Map<String, Object>>> exportRuntimeMetadata(
            @Parameter(description = "Runtime model name", required = true) @RequestParam String modelName,
            @Parameter(description = "App code", required = true) @RequestParam String appCode,
            @RequestBody(required = false) RuntimeExportFilter filter) {
        Assert.notBlank(modelName, "Model name cannot be empty.");
        Assert.notBlank(appCode, "App code cannot be empty.");
        this.assertAppCode(appCode);
        // A null keyColumn or keyValues means no aggregate narrowing — the full app-scoped export.
        boolean fullExport = filter == null || filter.keyColumn() == null || filter.keyValues() == null;
        String keyColumn = fullExport ? null : filter.keyColumn();
        Collection<String> keyValues = fullExport ? null : filter.keyValues();
        return ApiResponse.success(metadataService.exportRuntimeMetadata(modelName, appCode, keyColumn, keyValues));
    }

    /**
     * Export this runtime's per-aggregate checksums, scoped to the requested app.
     * Lightweight, handshake-gated: the studio compares these against its design-side checksums
     * and pulls full rows only for the aggregates that differ.
     */
    @Operation(summary = "exportRuntimeChecksums", description = "Export this runtime's per-aggregate checksums, scoped to an app")
    @PostMapping("/exportRuntimeChecksums")
    public ApiResponse<RuntimeChecksumsDTO> exportRuntimeChecksums(
            @Parameter(description = "App code", required = true) @RequestParam String appCode) {
        Assert.notBlank(appCode, "App code cannot be empty.");
        this.assertAppCode(appCode);
        return ApiResponse.success(metadataService.exportRuntimeChecksums(appCode));
    }

    /**
     * Apply an incremental metadata change set: per-row CRUD keyed
     * by business key, DDL first. The live synchronous APP deploy apply path — applies and returns on
     * completion (no async / callback wrapping). (URL kept as the legacy {@code /applyDesiredAggregates} path.)
     */
    @Operation(summary = "applyChanges",
            description = "Apply an incremental metadata change set: per-row CRUD keyed by business key, DDL first")
    @PostMapping("/applyDesiredAggregates")
    public ApiResponse<Boolean> applyChanges(
            @Parameter(description = "App code", required = true) @RequestParam String appCode,
            @RequestBody MetadataChangeSet changeSet) {
        Assert.notBlank(appCode, "App code cannot be empty.");
        this.assertAppCode(appCode);
        Assert.notNull(changeSet, "Metadata change set must not be null.");
        metadataApplyService.applyChanges(changeSet);
        return ApiResponse.success(true);
    }

}
