package io.softa.starter.studio.release.desired;

import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import io.softa.starter.metadata.dto.ChangeOp;
import io.softa.starter.metadata.dto.MetaChange;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.studio.release.connector.Connector;
import io.softa.starter.studio.release.ddl.DdlRenderResult;
import io.softa.starter.studio.release.ddl.MetadataChangeDdlRenderer;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.dto.RowChangeOp;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.upgrade.DdlSqlSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The shared <b>apply half</b> of a desired-state converge: given a flat
 * {@link RowChangeDTO} change list (each row self-describing via {@code table} + {@code op}), render
 * the rename-aware DDL, project the per-row changes into the incremental {@link MetadataChangeSet}
 * (CRUD keyed by business key), and ship it to the target (DDL first, then the row changes)
 * via {@link Connector#apply}.
 *
 * <p>Reused by both converge directions — they only differ in how the change list is computed
 * (publish: env-design ↔ runtime; merge: design ↔ design). Both produce the same flat
 * {@link RowChangeDTO} shape, so the DDL render + wire contract are identical.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DesiredStateDeployService {

    private final MetadataChangeDdlRenderer ddlRenderer;

    /**
     * Render the rename-aware DDL from the change list, project it to per-row business-key changes, and hand
     * the full change set to {@link Connector#apply} — the connector decides what to ship/execute:
     * a Softa connector gates the DDL on the env's {@code autoExecuteDDL} and ships the
     * rows through the signed envelope; a JDBC connector executes the DDL against the external database and
     * drops the rows (a raw DB has no {@code sys_*} rows). The full rendered DDL is always returned for the
     * deployment audit record.
     *
     * <p>An empty change list ships nothing (no apply) and returns an empty change set — the caller's gate.
     */
    public Applied applyToRuntime(DesignAppEnv targetEnv, String appCode, Connector connector,
                                  List<RowChangeDTO> changes) {
        if (changes.isEmpty()) {
            log.info("Desired-state apply: env {} ({}) already in sync — nothing to apply.",
                    targetEnv.getId(), appCode);
            return new Applied(new MetadataChangeSet(List.of(), List.of()), "");
        }

        // Render the full rename-aware DDL (table structure + fields + indexes) on the target connector's
        // dialect, project rows, and hand BOTH to the connector. The autoExecuteDDL gate is NOT applied
        // here (it moved into SoftaRuntimeConnector.apply); the connector owns shipping.
        DdlRenderResult ddlResult = ddlRenderer.generateDdlResult(connector.dialect(), changes);
        List<String> allDdl = DdlSqlSplitter.split(ddlResult.tableDdl(), ddlResult.indexDdl());
        MetadataChangeSet changeSet = new MetadataChangeSet(toMetaChanges(changes), allDdl);

        log.info("Desired-state apply: env {} ({}) → {} row change(s) + {} rendered DDL statement(s) "
                        + "(the connector decides what to ship/execute).",
                targetEnv.getId(), appCode, changeSet.changes().size(), allDdl.size());
        connector.apply(appCode, changeSet);
        return new Applied(changeSet, ddlResult.combinedDdl());
    }

    /**
     * Project the flat row-change list into the wire {@link MetaChange} list: CREATE/UPDATE → UPSERT,
     * DELETE → DELETE, each carrying {@code fullRow} as {@code attrs} (the business key is inside it;
     * no logicalId). The runtime whitelists the attrs to real {@code sys_*} columns (via
     * {@code SysCatalog}) and stamps identity, so no studio-side strip is needed.
     */
    private static List<MetaChange> toMetaChanges(List<RowChangeDTO> changes) {
        return changes.stream()
                .map(r -> new MetaChange(r.getTable(),
                        r.getOp() == RowChangeOp.DELETE ? ChangeOp.DELETE : ChangeOp.UPSERT,
                        new LinkedHashMap<>(r.getFullRow()),
                        r.getRenamedFrom()))
                .toList();
    }

    /** The outcome of {@link #applyToRuntime}: the applied change set + the full rendered DDL (for audit). */
    public record Applied(MetadataChangeSet changeSet, String combinedDdl) {
    }
}
