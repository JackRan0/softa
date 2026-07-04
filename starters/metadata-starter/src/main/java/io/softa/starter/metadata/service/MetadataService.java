package io.softa.starter.metadata.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.softa.starter.metadata.controller.dto.MetaModelDTO;
import io.softa.starter.metadata.controller.dto.ResolveCascadedPathsResponse;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;

/**
 * Metadata query + runtime-export service (the read half of the studio↔runtime contract). The write half —
 * applying an incremental change set — is {@link MetadataApplyService}.
 */
public interface MetadataService {

    /**
     * Get the MetaModelDTO object by modelName
     *
     * @param modelName model name
     * @return metaModelDTO object
     */
    MetaModelDTO getMetaModelDTO(String modelName);

    /**
     * Resolve cascaded field paths from {@code rootModel} in a single round-trip.
     * Returns the metaModel closure of related models reachable from successful
     * paths (excluding the root, which the caller already has) plus per-path
     * leaf metaField. A single invalid path does not fail the request: the
     * corresponding entry in {@code resolutions} carries {@code ok = false} and
     * an error code; other paths are unaffected.
     *
     * @param rootModel root model name; must exist in the metadata registry
     * @param paths     dot-separated cascaded paths
     * @return closure + per-path resolutions
     */
    ResolveCascadedPathsResponse resolveCascadedPaths(String rootModel, List<String> paths);

    /**
     * Reload metadata.
     * The current replica will be unavailable if an exception occurs during the reload,
     * and the metadata needs to be fixed and reloaded.
     */
    void reloadMetadata();

    /**
     * Export runtime metadata rows for the given model, scoped to the requested
     * app identity.
     * <p>
     * The requested {@code appCode} must equal this runtime's configured
     * {@code system.app-code} (handshake — the export must have been addressed to
     * this app). For models that carry an {@code appCode} column the filter is
     * applied directly; for translation models (suffix {@code Trans}) the column
     * lives on the parent row, so the implementation resolves the parent app and
     * matches on {@code rowId}.
     *
     * @param modelName runtime model name
     * @param appCode   app identity the caller is synchronising; required
     * @return list of row data maps
     */
    default List<Map<String, Object>> exportRuntimeMetadata(String modelName, String appCode) {
        return exportRuntimeMetadata(modelName, appCode, null, null);
    }

    /**
     * Export runtime metadata rows, optionally narrowed to a set of aggregate business keys
     * (incremental fetch). Behaves like {@link #exportRuntimeMetadata(String, String)}
     * but, when {@code keyColumn} is non-null, ANDs {@code keyColumn IN keyValues} onto the app-scope
     * filter so the studio deploy pulls only the aggregates whose checksum differs (or are runtime-only)
     * instead of the whole {@code appCode} catalog.
     *
     * @param modelName runtime model name
     * @param appCode   app identity the caller is synchronising; required
     * @param keyColumn aggregate-root business-key column to narrow on (e.g. {@code modelName} /
     *                  {@code optionSetCode}), or {@code null} for a full export
     * @param keyValues business keys to keep; an empty set with a non-null {@code keyColumn} returns no
     *                  rows (nothing to fetch); ignored when {@code keyColumn} is {@code null}
     * @return list of row data maps
     */
    List<Map<String, Object>> exportRuntimeMetadata(String modelName, String appCode,
                                                    String keyColumn, Collection<String> keyValues);

    /**
     * Per-aggregate checksums of this runtime's catalog, keyed by business key:
     * {@code modelName → checksum} and {@code optionSetCode → checksum}. Lightweight — the
     * studio compares these against its design-side checksums and pulls full rows only for
     * the aggregates that differ (the network gate). Same {@code appCode} handshake as
     * {@link #exportRuntimeMetadata}.
     *
     * @param appCode app identity the caller is synchronising; required
     * @return the runtime's model + option-set aggregate checksums
     */
    RuntimeChecksumsDTO exportRuntimeChecksums(String appCode);
}
