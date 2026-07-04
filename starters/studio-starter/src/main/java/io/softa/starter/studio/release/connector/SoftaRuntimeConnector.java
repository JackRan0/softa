package io.softa.starter.studio.release.connector;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;
import io.softa.starter.studio.release.desired.AggregateSelection;
import io.softa.starter.studio.release.desired.DesignRows;
import io.softa.starter.studio.release.dto.MetaKeys;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;

/**
 * {@link Connector} for a Softa runtime targeted by a {@code DesignAppEnv}: DDL is rendered on the
 * builtin annotation resolver (so studio publish matches the boot scanner byte-for-byte), and all
 * runtime touch (checksums / schema read / apply) goes through the signed upgrade API via
 * {@link RemoteApiClient}. The dialect is resolved once from the env's {@code databaseType} and the
 * collaborators are bound by {@link ConnectorFactory}.
 *
 * <p>Always remote ({@code feedback_studio_always_remote_deploy}): there is no local
 * shortcut — the {@code appCode} handshake is the only addressing.
 */
public record SoftaRuntimeConnector(DdlDialect dialect,
                                    RemoteApiClient remoteApiClient, DesignAppEnv env) implements Connector {

    // The aggregate-root business-key columns the runtime export narrows on (camelCase) — the same keys
    // the checksum/diff link by, declared once in MetaKeys.
    private static final String MODEL_NAME = MetaKeys.MODEL_NAME;
    private static final String OPTION_SET_CODE = MetaKeys.OPTION_SET_CODE;

    @Override
    public RuntimeChecksumsDTO readChecksums(String appCode) {
        return remoteApiClient.fetchRuntimeChecksums(env, appCode);
    }

    @Override
    public DesignRows readSchema(String appCode) {
        return new DesignRows(
                remoteApiClient.fetchRuntimeMetadata(env, appCode, SysModel.class.getSimpleName()),
                remoteApiClient.fetchRuntimeMetadata(env, appCode, SysField.class.getSimpleName()),
                remoteApiClient.fetchRuntimeMetadata(env, appCode, SysModelIndex.class.getSimpleName()),
                remoteApiClient.fetchRuntimeMetadata(env, appCode, SysOptionSet.class.getSimpleName()),
                remoteApiClient.fetchRuntimeMetadata(env, appCode, SysOptionItem.class.getSimpleName()));
    }

    @Override
    public DesignRows readSchema(String appCode, AggregateSelection selection) {
        // Per-table narrowed fetch: model-aggregate tables filter by modelName, option-set
        // tables by optionSetCode. An empty key set means there is nothing to pull for that aggregate root,
        // so we skip the RPC entirely (zero round-trip for the onlyInDesign / all-identical case).
        Set<String> models = selection.modelNames();
        Set<String> sets = selection.optionSetCodes();
        return new DesignRows(
                fetchNarrowed(appCode, SysModel.class.getSimpleName(), MODEL_NAME, models),
                fetchNarrowed(appCode, SysField.class.getSimpleName(), MODEL_NAME, models),
                fetchNarrowed(appCode, SysModelIndex.class.getSimpleName(), MODEL_NAME, models),
                fetchNarrowed(appCode, SysOptionSet.class.getSimpleName(), OPTION_SET_CODE, sets),
                fetchNarrowed(appCode, SysOptionItem.class.getSimpleName(), OPTION_SET_CODE, sets));
    }

    private List<Map<String, Object>> fetchNarrowed(String appCode, String runtimeModelName,
                                                    String keyColumn, Set<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return remoteApiClient.fetchRuntimeMetadata(env, appCode, runtimeModelName, keyColumn, keys);
    }

    @Override
    public void apply(String appCode, MetadataChangeSet changeSet) {
        // autoExecuteDDL gate (moved here from DesiredStateDeployService): ship the DDL for
        // the runtime to execute only when the env opts in; otherwise a DBA runs the recorded DDL out of
        // band and we ship rows only. The per-row changes always ship through the signed envelope.
        List<String> shippedDdl = Boolean.TRUE.equals(env.getAutoExecuteDDL()) ? changeSet.ddl() : List.of();
        remoteApiClient.applyChanges(env, appCode, new MetadataChangeSet(changeSet.changes(), shippedDdl));
    }
}
