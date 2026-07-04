package io.softa.starter.metadata.scanner.diff;

import java.util.List;

import io.softa.starter.metadata.entity.*;

/**
 * Result of comparing two {@code AnnotationScanResult}s — the from-code state
 * (parsed from {@code @Model} / {@code @Field} / ...) and the from-db state
 * (existing {@code sys_*} rows).
 *
 * <p>Categorized per Sys* entity into three buckets:
 * <ul>
 *   <li><b>added</b>: in code, not in DB → INSERT</li>
 *   <li><b>removed</b>: in DB, not in code → DELETE (drift; e.g. annotation removed)</li>
 *   <li><b>modified</b>: present in both with differing attributes → UPDATE</li>
 * </ul>
 *
 * <p>For modified rows, the diff carries both sides (code + db) so the
 * downstream layer (scanner persistence, checker warning log) can decide what
 * to do with the delta — overwrite (scanner, in-scope) or warn (checker).
 *
 * <p>The scanner uses this to drive idempotent reconciliation of the
 * {@code sys_*} rows, matched by business key.
 */
public record SchemaDiff(
        EntityDiff<SysModel> models,
        EntityDiff<SysField> fields,
        EntityDiff<SysOptionSet> optionSets,
        EntityDiff<SysOptionItem> optionItems,
        EntityDiff<SysModelIndex> modelIndexes
) {

    /**
     * Convenience 4-arg constructor with no index diffs — preserves test call
     * sites pre-dating the {@code modelIndexes} bucket.
     */
    public SchemaDiff(
            EntityDiff<SysModel> models,
            EntityDiff<SysField> fields,
            EntityDiff<SysOptionSet> optionSets,
            EntityDiff<SysOptionItem> optionItems) {
        this(models, fields, optionSets, optionItems, EntityDiff.empty());
    }

    /**
     * Per-entity-type bucket of added / removed / modified rows.
     */
    public record EntityDiff<T>(
            List<T> added,
            List<T> removed,
            List<Modification<T>> modified
    ) {
        public static <T> EntityDiff<T> empty() {
            return new EntityDiff<>(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
        }

        public int totalCount() {
            return added.size() + removed.size() + modified.size();
        }
    }

    /**
     * A row present on both sides — either with differing attributes under the
     * same business key ({@link Kind#MODIFY}), or paired across a declared
     * {@code renamedFrom} where the keys differ ({@link Kind#RENAME}):
     * {@code fromCode} carries the new name, {@code fromDb} the prior one.
     *
     * @param fromCode the parsed-from-code Sys* entity (target state)
     * @param fromDb   the existing-in-db Sys* entity (current state)
     * @param kind     MODIFY (same key, attribute delta) or RENAME (key changed)
     */
    public record Modification<T>(T fromCode, T fromDb, Kind kind) {

        /** Same-key attribute modification (the common case). */
        public Modification(T fromCode, T fromDb) {
            this(fromCode, fromDb, Kind.MODIFY);
        }
    }

    /** Whether a {@link Modification} is a same-key change or a declared rename. */
    public enum Kind { MODIFY, RENAME }

    public static SchemaDiff empty() {
        return new SchemaDiff(
                EntityDiff.empty(),
                EntityDiff.empty(),
                EntityDiff.empty(),
                EntityDiff.empty(),
                EntityDiff.empty());
    }

    public boolean isEmpty() {
        return models.isEmpty()
                && fields.isEmpty()
                && optionSets.isEmpty()
                && optionItems.isEmpty()
                && modelIndexes.isEmpty();
    }

    public int totalCount() {
        return models.totalCount()
                + fields.totalCount()
                + optionSets.totalCount()
                + optionItems.totalCount()
                + modelIndexes.totalCount();
    }
}
