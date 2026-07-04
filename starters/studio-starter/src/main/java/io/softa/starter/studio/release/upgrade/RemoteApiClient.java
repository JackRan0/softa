package io.softa.starter.studio.release.upgrade;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * Remote API client for studio → runtime calls (read schema + checksums, apply changes). The signed
 * transport (Ed25519 + idempotency) is supplied by the {@code studioRemoteRestClient} bean.
 */
public interface RemoteApiClient {

    /**
     * Fetch runtime metadata for a specific model from the remote environment, scoped to the app
     * identity. The {@code appCode} doubles as the handshake: the runtime rejects the export unless it
     * equals its configured {@code system.app-code}.
     *
     * @param appEnv           target environment (supplies endpoint + signing key)
     * @param appCode          app identity being synchronised ({@code DesignApp.appCode})
     * @param runtimeModelName runtime model name (e.g. "SysModel")
     * @return list of runtime row data
     */
    List<Map<String, Object>> fetchRuntimeMetadata(DesignAppEnv appEnv, String appCode, String runtimeModelName);

    /**
     * Fetch runtime metadata <b>narrowed</b> to a set of aggregate business keys (incremental fetch):
     * the runtime ANDs {@code keyColumn IN keyValues} onto the app scope, so the
     * studio deploy pulls only the aggregates whose checksum differs instead of the whole catalog. The
     * key set rides in the request body (not the URL), so it is not bounded by URL length.
     *
     * @param appEnv           target environment (supplies endpoint + signing key)
     * @param appCode          app identity being synchronised ({@code DesignApp.appCode})
     * @param runtimeModelName runtime model name (e.g. "SysField")
     * @param keyColumn        aggregate-root business-key column to narrow on (e.g. {@code modelName})
     * @param keyValues        business keys to keep (non-empty — the caller skips the call when empty)
     * @return list of runtime row data
     */
    List<Map<String, Object>> fetchRuntimeMetadata(DesignAppEnv appEnv, String appCode, String runtimeModelName,
                                                   String keyColumn, Collection<String> keyValues);

    /**
     * Fetch the runtime's per-aggregate checksums, scoped to the app identity. Lightweight —
     * the studio diffs these against its design-side checksums and pulls full rows only for the
     * aggregates that differ (the network gate).
     *
     * @param appEnv  target environment (supplies endpoint + signing key)
     * @param appCode app identity being synchronised ({@code DesignApp.appCode})
     * @return the runtime's model + option-set aggregate checksums
     */
    RuntimeChecksumsDTO fetchRuntimeChecksums(DesignAppEnv appEnv, String appCode);

    /**
     * Ship an incremental metadata change set to the runtime: per-row CRUD keyed by
     * business key + DDL-first; the runtime applies exactly these row changes (no
     * whole-aggregate overwrite). Same {@code appCode} handshake as the other calls.
     *
     * @param appEnv    target environment (supplies endpoint + signing key)
     * @param appCode   app identity being deployed ({@code DesignApp.appCode})
     * @param changeSet the per-row changes (UPSERT/DELETE by business key) + the rename-aware DDL
     */
    void applyChanges(DesignAppEnv appEnv, String appCode, MetadataChangeSet changeSet);
}
