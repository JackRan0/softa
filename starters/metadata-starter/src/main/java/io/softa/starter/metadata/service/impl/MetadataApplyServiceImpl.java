package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.catalog.SysCatalog;
import io.softa.starter.metadata.catalog.SysColumn;
import io.softa.starter.metadata.ddl.DdlExecutor;
import io.softa.starter.metadata.dto.ChangeOp;
import io.softa.starter.metadata.dto.DdlStatementResult;
import io.softa.starter.metadata.dto.DdlStatementStatus;
import io.softa.starter.metadata.dto.MetaChange;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.service.MetadataApplyService;

/**
 * Applies an incremental metadata change set to the runtime {@code sys_*} catalog — the runtime side of the
 * studio deploy. Keyed by <b>business key</b> (no surrogate logicalId is threaded); the {@code appCode}
 * identity is stamped server-side ({@link MetadataAppIdentity}), never trusted from the wire.
 */
@Slf4j
@Service
public class MetadataApplyServiceImpl implements MetadataApplyService {

    /** The single immediately-prior business-key name; written on a rename, read to locate by old key. */
    private static final String RENAMED_FROM_FIELD = "renamedFrom";

    private final ModelService<Serializable> modelService;
    private final DdlExecutor ddlExecutor;

    public MetadataApplyServiceImpl(ModelService<Serializable> modelService, DdlExecutor ddlExecutor) {
        this.modelService = modelService;
        this.ddlExecutor = ddlExecutor;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyChanges(MetadataChangeSet changeSet) {
        // DDL first: committed metadata rows must never describe physical structure that
        // does not exist. Fail-fast — a failed statement aborts before any row is written.
        applyDdlFirst(changeSet.ddl());

        // Incremental, idempotent, FK-safe: UPSERTs parent→child (MetaTable ordinal asc),
        // DELETEs child→parent (desc). UPSERT-by-business-key and DELETE are both idempotent, so a retried
        // dispatch re-applies safely. No whole-aggregate overwrite, no reconcile-delete-absent.
        changeSet.changes().stream()
                .filter(c -> c.op() == ChangeOp.UPSERT)
                .sorted(Comparator.comparingInt(c -> c.table().ordinal()))
                .forEach(this::applyUpsert);
        changeSet.changes().stream()
                .filter(c -> c.op() == ChangeOp.DELETE)
                .sorted(Comparator.comparingInt((MetaChange c) -> c.table().ordinal()).reversed())
                .forEach(this::applyDelete);
    }

    /**
     * Converge one row to its desired attributes, keyed by its <b>business key</b> (logicalId
     * removed). Locate by business key; if absent and this is a rename, locate by the prior key
     * ({@code renamedFrom}); else INSERT. The {@code appCode} identity is stamped
     * server-side. App-scoped: studio is the complete source of truth for the app's
     * catalog.
     */
    private void applyUpsert(MetaChange change) {
        String sys = change.table().sysModel();
        Map<String, Object> existing = findByBusinessKey(change);
        if (existing == null) {
            existing = findByOldBusinessKey(change);   // locate a renamed row by its prior key
        }
        Map<String, Object> row = stampIdentity(sys, sanitize(change.table(), change.attrs()));
        if (change.renamedFrom() != null) {
            // Dedicated write — renamed_from is EXCLUDED from SysCatalog so sanitize drops it.
            // Set only on a rename (replace); a non-rename update leaves the column untouched.
            row.put(RENAMED_FROM_FIELD, change.renamedFrom());
        }
        if (existing != null) {
            row.put(ModelConstant.ID, existing.get(ModelConstant.ID));
            modelService.updateOne(sys, row);
        } else {
            // sanitize never emits the surrogate id (SysCatalog routes it to idColumn), so the row is
            // already id-free for the INSERT.
            modelService.createOne(sys, row);
        }
    }

    /**
     * The {@code appCode} identity column is stamped server-side, never trusted
     * from the wire: create rows are forced to the configured {@code appCode} and
     * update rows may not flip it. Returns a mutable copy — the caller's row map
     * is never mutated.
     */
    private Map<String, Object> stampIdentity(String modelName, Map<String, Object> attrs) {
        Map<String, Object> copy = new HashMap<>(attrs);
        if (ModelManager.existField(modelName, MetadataAppIdentity.APP_CODE_FIELD)) {
            copy.put(MetadataAppIdentity.APP_CODE_FIELD, MetadataAppIdentity.configured());
        }
        return copy;
    }

    /**
     * Whitelist the wire attrs to the columns that actually exist on the target {@code sys_*} table
     * ({@code SysCatalog} keys + data) — design-internal columns (envId / modelId / old*Name / the
     * surrogate id, etc.) are dropped authoritatively here, so the studio may ship the full row.
     * {@code appCode} is not a data column; the caller stamps it.
     */
    private static Map<String, Object> sanitize(MetaTable table, Map<String, Object> attrs) {
        Map<String, Object> row = new HashMap<>();
        SysCatalog.SysTable<?> t = SysCatalog.of(table.sysEntity());
        t.keys().forEach(c -> {
            if (attrs.containsKey(c.name())) {
                row.put(c.name(), attrs.get(c.name()));
            }
        });
        t.data().forEach(c -> {
            if (attrs.containsKey(c.name())) {
                row.put(c.name(), attrs.get(c.name()));
            }
        });
        return row;
    }

    /** Remove one row, located by its business key. */
    private void applyDelete(MetaChange change) {
        String sys = change.table().sysModel();
        Map<String, Object> existing = findByBusinessKey(change);
        if (existing != null) {
            modelService.deleteById(sys, (Serializable) existing.get(ModelConstant.ID));
        }
    }

    /**
     * The app-scoped runtime row matching a business key, or null. {@code keyValue} resolves each
     * {@code SysCatalog} key column's value — the current row for {@link #findByBusinessKey}, the prior name
     * swapped in for {@link #findByOldBusinessKey}.
     */
    private Map<String, Object> findByKey(MetaChange change, Function<String, Object> keyValue) {
        Filters filters = new Filters().eq(MetadataAppIdentity.APP_CODE_FIELD, MetadataAppIdentity.configured());
        for (SysColumn<?> key : SysCatalog.of(change.table().sysEntity()).keys()) {
            filters.eq(key.name(), keyValue.apply(key.name()));
        }
        return modelService.searchOne(change.table().sysModel(), new FlexQuery(filters)).orElse(null);
    }

    /** The app-scoped runtime row matching the change's business key (the SysCatalog key columns), or null. */
    private Map<String, Object> findByBusinessKey(MetaChange change) {
        return findByKey(change, col -> change.attrs().get(col));
    }

    /**
     * The app-scoped runtime row matching the change's PRIOR business key: the same key with the
     * renamed column ({@link #renameKeyColumn}) taken from {@code renamedFrom}, or null. Locates a renamed
     * row whose runtime copy still holds the old name — the case the new business key cannot pair. Closes
     * the rename-divorce gap (the only bridge to the old key, since no surrogate identity is threaded).
     */
    private Map<String, Object> findByOldBusinessKey(MetaChange change) {
        String renameKeyCol = renameKeyColumn(change.table());
        if (change.renamedFrom() == null || renameKeyCol == null) {
            return null;
        }
        return findByKey(change,
                col -> col.equals(renameKeyCol) ? change.renamedFrom() : change.attrs().get(col));
    }

    /** The business-key column carrying each renameable entity's own name; null = not renameable. */
    private static String renameKeyColumn(MetaTable table) {
        return switch (table) {
            case MODEL -> "modelName";
            case FIELD -> "fieldName";
            case OPTION_SET -> "optionSetCode";
            case OPTION_ITEM -> "itemCode";
            case INDEX -> null;
        };
    }

    /**
     * Execute the deploy's DDL before any row write, fail-fast. Re-applied statements
     * degrade to {@code SKIPPED_IDEMPOTENT}; a genuine failure aborts so committed rows can never
     * describe absent structure. Empty when nothing structural changed or a DBA runs DDL out of band.
     */
    private void applyDdlFirst(List<String> ddl) {
        if (CollectionUtils.isEmpty(ddl)) {
            return;
        }
        List<DdlStatementResult> results = ddlExecutor.executeAll(ddl);
        DdlStatementResult failed = results.stream()
                .filter(r -> r.status() == DdlStatementStatus.FAILED)
                .findFirst()
                .orElse(null);
        if (failed != null) {
            throw new IllegalStateException(
                    "DDL statement[" + failed.sequence() + "] failed; rows not applied: " + failed.errorMessage());
        }
    }
}
