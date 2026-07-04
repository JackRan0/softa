package io.softa.starter.metadata.scanner.diff;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.softa.starter.metadata.catalog.SysCatalog;
import io.softa.starter.metadata.catalog.SysColumn;
import io.softa.starter.metadata.entity.*;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.annotation.RenameDeclarations;

/**
 * Compares the from-code {@link AnnotationScanResult} (parsed from
 * annotations) against the from-db {@link AnnotationScanResult} (existing
 * {@code sys_*} rows) and produces a
 * {@link SchemaDiff}.
 *
 * <p>Key matching (composed by {@link SysKeys}):
 * <ul>
 *   <li>{@code SysModel} → {@code modelName}</li>
 *   <li>{@code SysField} → {@code modelName + "." + fieldName}</li>
 *   <li>{@code SysOptionSet} → {@code optionSetCode}</li>
 *   <li>{@code SysOptionItem} → {@code optionSetCode + "." + itemCode}</li>
 * </ul>
 *
 * <p>Equality (for the "modified" bucket) compares only annotation-derived
 * attributes — surrogate keys ({@code id}, {@code appId}, FK {@code modelId},
 * {@code optionSetId}), audit fields, and the {@code modelFields} relation are
 * ignored.
 *
 * <p><b>Declared renames</b> (the {@code renamedFrom} attribute, replacing the
 * retired {@code @RenamedFrom} annotation): a {@code renamedFrom} on the code
 * side (carried in {@code fromCode.renames()}) lets the diff pair a removed-old
 * row with the added-new one and emit a {@code Modification(kind=RENAME)}
 * instead of {@code added + removed} — so downstream renders {@code CHANGE
 * COLUMN} / {@code RENAME TABLE} and updates the {@code sys_*} row in place
 * (id preserved) rather than divorcing the data. A <b>model</b> rename also
 * cascades: db fields / indexes under the prior model name are re-keyed to the
 * new name before the field / index diff, so a pure model rename shows no
 * field churn. Four-state per declaration: old-present+new-absent → rename;
 * new-present → already applied (normal path); both-present → fail-fast (a
 * half-applied rename or a name collision a human must resolve); neither → a
 * fresh add. Renames not declared still fall through as {@code added+removed}.
 *
 * <p>Pure POJO — no Spring dependency. Used by both
 * {@code MetadataAnnotationScanner} (scanner, write side) and
 * {@code MetadataAnnotationChecker} (read-only drift detection).
 */
public final class DiffEngine {

    /**
     * Compute the diff between code-side and db-side scan results.
     *
     * @param fromCode parsed-from-annotations result (target)
     * @param fromDb   loaded-from-db result (current {@code sys_*} rows)
     */
    public SchemaDiff diff(AnnotationScanResult fromCode, AnnotationScanResult fromDb) {
        RenameDeclarations renames = fromCode.renames();

        // Models: pair declared renames (Customer renamedFrom "OldCustomer") → RENAME.
        SchemaDiff.EntityDiff<SysModel> modelDiff = diffWithRenames(
                fromCode.models(), fromDb.models(),
                SysKeys::of, SysKeys::of,
                key -> renames.modelOldNames().get(key),
                (a, b) -> equalByCatalog(SysModel.class, a, b),
                "model");

        // A model rename cascades: re-key the db fields / indexes under the prior model
        // name onto the new name BEFORE their diff, so a pure model rename shows no
        // field / index churn. The cascade MUTATES the db-side entities (not just the
        // lookup key): rows that end up in the removed bucket must carry the new
        // modelName too, or the writer's DELETE (issued after its row-side cascade has
        // re-pointed all child rows) would miss them, and DdlPolicy would group their
        // DROP hint under the renamed-away model.
        Map<String, String> oldToNewModel = new HashMap<>();
        for (SchemaDiff.Modification<SysModel> m : modelDiff.modified()) {
            if (m.kind() == SchemaDiff.Kind.RENAME) {
                oldToNewModel.put(m.fromDb().getModelName(), m.fromCode().getModelName());
            }
        }
        if (!oldToNewModel.isEmpty()) {
            for (SysField f : fromDb.fields()) {
                String newName = oldToNewModel.get(f.getModelName());
                if (newName != null) {
                    f.setModelName(newName);
                }
            }
            for (SysModelIndex i : fromDb.modelIndexes()) {
                String newName = oldToNewModel.get(i.getModelName());
                if (newName != null) {
                    i.setModelName(newName);
                }
            }
        }

        SchemaDiff.EntityDiff<SysField> fieldDiff = diffWithRenames(
                fromCode.fields(), fromDb.fields(),
                SysKeys::of, SysKeys::of,
                key -> fieldOldKey(key, renames),
                (a, b) -> equalByCatalog(SysField.class, a, b),
                "field");

        SchemaDiff.EntityDiff<SysModelIndex> indexDiff = diffWithRenames(
                fromCode.modelIndexes(), fromDb.modelIndexes(),
                SysKeys::of, SysKeys::of,
                key -> null,
                (a, b) -> equalByCatalog(SysModelIndex.class, a, b),
                "index");

        // Option sets / items carry no rename declarations: same-key diff via the
        // shared rename-aware path with a null oldKeysOf (exactly the index bucket).
        SchemaDiff.EntityDiff<SysOptionSet> optionSetDiff = diffWithRenames(
                fromCode.optionSets(), fromDb.optionSets(),
                SysKeys::of, SysKeys::of,
                key -> null,
                (a, b) -> equalByCatalog(SysOptionSet.class, a, b),
                "optionSet");
        SchemaDiff.EntityDiff<SysOptionItem> optionItemDiff = diffWithRenames(
                fromCode.optionItems(), fromDb.optionItems(),
                SysKeys::of, SysKeys::of,
                key -> null,
                (a, b) -> equalByCatalog(SysOptionItem.class, a, b),
                "optionItem");

        return new SchemaDiff(
                modelDiff,
                fieldDiff,
                optionSetDiff,
                optionItemDiff,
                indexDiff);
    }

    /** For a code field key {@code "Model.newField"}, the db key under its single prior field name, or null. */
    private static String fieldOldKey(String codeKey, RenameDeclarations renames) {
        String oldFieldName = renames.fieldOldNames().get(codeKey);
        if (oldFieldName == null) {
            return null;
        }
        String model = codeKey.substring(0, codeKey.lastIndexOf('.'));
        return model + "." + oldFieldName;
    }

    /**
     * Set-diff with a declared-rename pre-pass. {@code oldKeyOf} maps a code row's
     * key to its single prior db key to pair across, or null when there is no
     * declaration (option sets / items / indexes never carry one). See
     * {@link #diff} for the four states. (A model rename's cascade onto db field /
     * index rows happens before this call, by mutating their {@code modelName}.)
     */
    private <T> SchemaDiff.EntityDiff<T> diffWithRenames(
            Collection<T> code,
            Collection<T> db,
            Function<T, String> codeKeyFn,
            Function<T, String> dbKeyFn,
            Function<String, String> oldKeyOf,
            BiFunction<T, T, Boolean> equalsFn,
            String entityLabel) {

        Map<String, T> codeByKey = indexBy(code, codeKeyFn);
        Map<String, T> dbByKey = indexBy(db, dbKeyFn);

        List<T> added = new ArrayList<>();
        List<T> removed = new ArrayList<>();
        List<SchemaDiff.Modification<T>> modified = new ArrayList<>();
        Set<String> consumedDbKeys = new HashSet<>();

        for (Map.Entry<String, T> e : codeByKey.entrySet()) {
            String key = e.getKey();
            T codeRow = e.getValue();
            T sameKeyDb = dbByKey.get(key);

            String oldKey = oldKeyOf.apply(key);
            String matchedOld = oldKey != null && dbByKey.containsKey(oldKey) ? oldKey : null;
            if (matchedOld != null) {
                if (sameKeyDb != null) {
                    throw new IllegalStateException(entityLabel + " '" + key
                            + "' declares renamedFrom but both the new name and prior name '" + matchedOld
                            + "' exist in the catalog — a half-applied rename or a name collision; resolve"
                            + " manually (drop the stale one) before reconciling.");
                }
                modified.add(new SchemaDiff.Modification<>(codeRow, dbByKey.get(matchedOld),
                        SchemaDiff.Kind.RENAME));
                consumedDbKeys.add(matchedOld);
                continue;
            }

            if (sameKeyDb != null) {
                if (!equalsFn.apply(codeRow, sameKeyDb)) {
                    modified.add(new SchemaDiff.Modification<>(codeRow, sameKeyDb));
                }
            } else {
                added.add(codeRow);
            }
        }
        for (Map.Entry<String, T> e : dbByKey.entrySet()) {
            if (!codeByKey.containsKey(e.getKey()) && !consumedDbKeys.contains(e.getKey())) {
                removed.add(e.getValue());
            }
        }
        return new SchemaDiff.EntityDiff<>(added, removed, modified);
    }

    private <T> Map<String, T> indexBy(Collection<T> rows, Function<T, String> keyFn) {
        Map<String, T> out = new LinkedHashMap<>();
        for (T row : rows) {
            String key = keyFn.apply(row);
            if (key != null) {
                out.put(key, row);
            }
        }
        return out;
    }

    // -------------------------------------------- descriptor-driven equality

    /**
     * Two rows are "equal" (not a modification) iff every annotation-derived
     * data column — per {@link SysCatalog} — is typed-equal. Surrogate / FK /
     * audit and runtime active-control columns are excluded
     * by the descriptor and never compared; {@code Orders} compares by its
     * string form (via the {@code ORDERS} codec).
     */
    private static <T> boolean equalByCatalog(Class<T> type, T a, T b) {
        for (SysColumn<T> col : SysCatalog.of(type).data()) {
            if (!col.equalTyped(a, b)) {
                return false;
            }
        }
        return true;
    }
}
