package io.softa.starter.studio.release.desired;

import static io.softa.starter.studio.release.desired.DesignEnvRowOps.ID;
import static io.softa.starter.studio.release.desired.DesignEnvRowOps.prepareClone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;
import io.softa.starter.studio.release.dto.DesignMetaTables;
import io.softa.starter.studio.release.dto.MetaKeys;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;

/**
 * The env↔env merge writer: brings one env's design (SOURCE) into another env's design
 * (TARGET) of the same app — a single-direction, overwrite-style converge by <b>business key</b> (no
 * three-way merge). Both sides are loaded via {@link DesignEnvSource}; the
 * {@link DesignAggregateDiffer#diff business-key-keyed diff} classifies each aggregate row;
 * this writer applies the result onto the target env's {@code design_*} rows. Locate / re-parent all go
 * through the shared {@link DesignEnvRowOps} primitives (business key → surrogate id; no id threaded
 * across envs) — the same primitives the runtime→design {@link DesignDriftImporter} uses.
 *
 * <p>Per the diff:
 * <ul>
 *   <li><b>CREATE</b> (business key only in source) — clone the source row into the target env: drop its
 *       surrogate id (a fresh one is minted), stamp {@code envId = target}, copy the business-key
 *       columns verbatim, and remap the parent FK ({@code modelId} / {@code optionSetId}) onto the
 *       target parent with the <i>same parent business key</i> (existing in target, or just created
 *       here);</li>
 *   <li><b>UPDATE</b> (business key in both, business attrs differ) — update the matched target row
 *       <i>in place</i> (its surrogate id / env untouched) with the changed business values. A field /
 *       optionItem rename is bridged by {@code renamedFrom} (the diff carries the prior business key),
 *       so it is an in-place UPDATE of the name column — never a drop+add;</li>
 *   <li><b>DELETE</b> (business key only in target) — remove the target row.</li>
 * </ul>
 *
 * <p>Ordering is FK-safe: parents (model / option-set) are created and updated before children
 * (field / index / item) so a child's remap finds its parent; deletes run children-first.
 *
 * <p>Scope &amp; limitations (single-direction overwrite, v1): the 5 swept meta-models only (no
 * View / Navigation / Trans — same scope as publish/drift). A child is not re-parented on update
 * (the surrogate FK is not a diffed attr). A source aggregate whose business key (e.g. {@code
 * modelName}) collides with a <i>different</i> logical entity already in the target is a genuine
 * merge conflict: the physical {@code UNIQUE(env_id, business key)} index rejects the duplicate,
 * so the (transactional) merge fails and rolls back — align the names across the envs first.
 * Runs in the caller's transaction; the caller holds the target env mutex.
 */
@Component
public class DesignEnvMerger {

    private static final String MODEL = DesignModel.class.getSimpleName();
    private static final String FIELD = DesignField.class.getSimpleName();
    private static final String INDEX = DesignModelIndex.class.getSimpleName();
    private static final String OPTION_SET = DesignOptionSet.class.getSimpleName();
    private static final String OPTION_ITEM = DesignOptionItem.class.getSimpleName();

    // Business-key columns used for selective-merge filtering, parent-FK re-parent and rename bridging
    // (the compare/locate keys themselves come from DesignEnvRowOps.BIZ_KEY_ATTRS — the single source).
    private static final String MODEL_NAME = MetaKeys.MODEL_NAME;
    private static final String OPTION_SET_CODE = MetaKeys.OPTION_SET_CODE;
    private static final String FIELD_NAME = MetaKeys.FIELD_NAME;
    private static final String ITEM_CODE = MetaKeys.ITEM_CODE;

    // Per-table business keys — the env-scoped locate / re-parent keys (envId is the load scope, not part
    // of the key). renameKeyAttr (the old-key fallback column) is field / optionItem only — model /
    // optionSet / index don't bridge a rename here.
    private static final List<String> MODEL_KEY = DesignEnvRowOps.BIZ_KEY_ATTRS.get(MODEL);
    private static final List<String> FIELD_KEY = DesignEnvRowOps.BIZ_KEY_ATTRS.get(FIELD);
    private static final List<String> INDEX_KEY = DesignEnvRowOps.BIZ_KEY_ATTRS.get(INDEX);
    private static final List<String> OPTION_SET_KEY = DesignEnvRowOps.BIZ_KEY_ATTRS.get(OPTION_SET);
    private static final List<String> ITEM_KEY = DesignEnvRowOps.BIZ_KEY_ATTRS.get(OPTION_ITEM);

    private final ModelService<Long> modelService;
    private final DesignEnvSource envSource;
    private final DesignAggregateDiffer differ;

    public DesignEnvMerger(ModelService<Long> modelService, DesignEnvSource envSource,
                           DesignAggregateDiffer differ) {
        this.modelService = modelService;
        this.envSource = envSource;
        this.differ = differ;
    }

    /** The outcome of a merge — row counts plus the underlying business-key diff (for the audit record). */
    public record MergeResult(int created, int updated, int deleted, List<RowChangeDTO> changes) {
    }

    /**
     * Merge {@code sourceEnvId}'s design into {@code targetEnvId} (same {@code appId}), converging the
     * target to the source by business key. When {@code selection} is non-empty, only the chosen aggregate
     * roots (and their children) are applied (selective merge); a {@code null}/empty
     * selection is a full merge. Returns the applied row counts + the applied diff (filtered when a
     * selection is given, so the audit reflects exactly what was merged).
     */
    public MergeResult merge(Long appId, Long sourceEnvId, Long targetEnvId, MergeSelection selection) {
        DesignRows source = envSource.load(appId, sourceEnvId);
        DesignRows target = envSource.load(appId, targetEnvId);
        List<RowChangeDTO> changes = differ.diff(source, target);
        if (selection != null && !selection.isEmpty()) {
            changes = filterToSelection(changes, selection);
        }

        // The diff is a flat row-change list; regroup per design meta-model for the FK-safe write order.
        Map<String, ModelChangesDTO> byModel = new HashMap<>();
        for (ModelChangesDTO dto : DesignMetaTables.group(changes)) {
            byModel.put(dto.getModelName(), dto);
        }

        // Index the TARGET env's rows by their per-env business key → surrogate id. All
        // locate (update/delete) and parent re-parent resolve through these — no surrogate id is
        // threaded through the diff. The model / optionSet maps double as the parent-FK remap
        // for children and absorb parents created during this merge.
        Map<String, Long> modelTargetId = DesignEnvRowOps.indexByKey(target.models(), MODEL_KEY);
        Map<String, Long> setTargetId = DesignEnvRowOps.indexByKey(target.optionSets(), OPTION_SET_KEY);
        Map<String, Long> fieldTargetId = DesignEnvRowOps.indexByKey(target.fields(), FIELD_KEY);
        Map<String, Long> indexTargetId = DesignEnvRowOps.indexByKey(target.indexes(), INDEX_KEY);
        Map<String, Long> itemTargetId = DesignEnvRowOps.indexByKey(target.items(), ITEM_KEY);

        int created = 0;
        int updated = 0;
        int deleted = 0;

        // 1. Parents first (create then update), so children can resolve their target parent id.
        created += createParents(MODEL, byModel.get(MODEL), targetEnvId, modelTargetId, MODEL_NAME);
        created += createParents(OPTION_SET, byModel.get(OPTION_SET), targetEnvId, setTargetId, OPTION_SET_CODE);
        updated += updateRows(MODEL, byModel.get(MODEL), modelTargetId, MODEL_KEY, null);
        updated += updateRows(OPTION_SET, byModel.get(OPTION_SET), setTargetId, OPTION_SET_KEY, null);

        // 2. Children (create with parent-FK remap by business key, then update).
        created += createChildren(FIELD, byModel.get(FIELD), targetEnvId,
                DesignEnvRowOps.MODEL_ID, MODEL_NAME, modelTargetId);
        created += createChildren(INDEX, byModel.get(INDEX), targetEnvId,
                DesignEnvRowOps.MODEL_ID, MODEL_NAME, modelTargetId);
        created += createChildren(OPTION_ITEM, byModel.get(OPTION_ITEM), targetEnvId,
                DesignEnvRowOps.OPTION_SET_ID, OPTION_SET_CODE, setTargetId);
        updated += updateRows(FIELD, byModel.get(FIELD), fieldTargetId, FIELD_KEY, FIELD_NAME);
        updated += updateRows(INDEX, byModel.get(INDEX), indexTargetId, INDEX_KEY, null);
        updated += updateRows(OPTION_ITEM, byModel.get(OPTION_ITEM), itemTargetId, ITEM_KEY, ITEM_CODE);

        // 3. Deletes: children before parents.
        deleted += deleteRows(FIELD, byModel.get(FIELD), fieldTargetId, FIELD_KEY);
        deleted += deleteRows(INDEX, byModel.get(INDEX), indexTargetId, INDEX_KEY);
        deleted += deleteRows(OPTION_ITEM, byModel.get(OPTION_ITEM), itemTargetId, ITEM_KEY);
        deleted += deleteRows(MODEL, byModel.get(MODEL), modelTargetId, MODEL_KEY);
        deleted += deleteRows(OPTION_SET, byModel.get(OPTION_SET), setTargetId, OPTION_SET_KEY);

        return new MergeResult(created, updated, deleted, changes);
    }

    /** Create each source-only parent into the target env; record its business key → new target id for children. */
    private int createParents(String model, ModelChangesDTO dto, Long targetEnvId,
                              Map<String, Long> targetIdByCode, String codeAttr) {
        if (dto == null) {
            return 0;
        }
        int n = 0;
        for (RowChangeDTO change : dto.getCreatedRows()) {
            Long newId = modelService.createOne(model, prepareClone(change.getFullRow(), targetEnvId));
            targetIdByCode.put(str(change.getFullRow().get(codeAttr)), newId);
            n++;
        }
        return n;
    }

    /** Create each source-only child into the target env, re-pointing its parent FK by parent business key. */
    private int createChildren(String model, ModelChangesDTO dto, Long targetEnvId, String parentFk,
                               String parentCodeAttr, Map<String, Long> parentTargetIdByCode) {
        if (dto == null) {
            return 0;
        }
        List<Map<String, Object>> clones = new ArrayList<>();
        for (RowChangeDTO change : dto.getCreatedRows()) {
            clones.add(prepareClone(change.getFullRow(), targetEnvId));
        }
        if (clones.isEmpty()) {
            return 0;
        }
        // The source child carries its parent's business code (denormalized) — re-point its FK at the
        // target-env parent that owns that code (modelId / optionSetId are per-env, never carried across).
        DesignEnvRowOps.relinkChildFk(clones, parentFk, parentCodeAttr, parentTargetIdByCode);
        modelService.createList(model, clones);
        return clones.size();
    }

    /**
     * Update each matched target row in place, located by its per-env BUSINESS KEY (never a
     * surrogate id). A field / optionItem rename (the source row carries the new key +
     * {@code renamedFrom}) is located by the OLD key the target still holds. env / surrogate id stay.
     */
    private int updateRows(String model, ModelChangesDTO dto, Map<String, Long> targetIdByKey,
                           List<String> keyAttrs, String renameKeyAttr) {
        if (dto == null) {
            return 0;
        }
        int n = 0;
        for (RowChangeDTO change : dto.getUpdatedRows()) {
            Long targetId = targetIdByKey.get(DesignEnvRowOps.bizKey(keyAttrs, change.getFullRow()));
            if (targetId == null && renameKeyAttr != null && change.getRenamedFrom() != null) {
                targetId = targetIdByKey.get(DesignEnvRowOps.oldBizKey(
                        keyAttrs, renameKeyAttr, change.getFullRow(), change.getRenamedFrom()));
            }
            if (targetId == null) {
                continue;   // an updated row always pairs an existing target row; defensive only
            }
            // The new values for the changed columns = the full row projected onto the changed keys.
            Map<String, Object> update = new HashMap<>();
            for (String key : change.getPreviousValuesForChangedFields().keySet()) {
                update.put(key, change.getFullRow().get(key));
            }
            update.put(ID, targetId);
            modelService.updateOne(model, update);
            n++;
        }
        return n;
    }

    /** Delete each target-only row, located by its per-env business key. */
    private int deleteRows(String model, ModelChangesDTO dto, Map<String, Long> targetIdByKey, List<String> keyAttrs) {
        if (dto == null) {
            return 0;
        }
        List<Long> ids = new ArrayList<>();
        for (RowChangeDTO change : dto.getDeletedRows()) {
            Long targetId = targetIdByKey.get(DesignEnvRowOps.bizKey(keyAttrs, change.getFullRow()));
            if (targetId != null) {
                ids.add(targetId);
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        modelService.deleteByIds(model, ids);
        return ids.size();
    }

    /**
     * Keep only the change rows belonging to the selected aggregate roots (and their children). Roots are
     * selected by <b>business key</b> ({@code modelName} / {@code optionSetCode}) — the same key
     * the change rows carry — so the selection matches directly with no id resolution. Children (field /
     * index / item) are kept by their parent business key, so aggregate-root granularity holds without
     * per-child selection.
     */
    private List<RowChangeDTO> filterToSelection(List<RowChangeDTO> changes, MergeSelection selection) {
        Set<String> selectedModels = selection.modelNames() == null ? Set.of() : selection.modelNames();
        Set<String> selectedSets = selection.optionSetCodes() == null ? Set.of() : selection.optionSetCodes();
        List<RowChangeDTO> kept = new ArrayList<>();
        for (RowChangeDTO change : changes) {
            MetaTable table = change.getTable();
            boolean keep = (table == MetaTable.MODEL || table == MetaTable.FIELD || table == MetaTable.INDEX)
                    ? selectedModels.contains(str(change.getFullRow().get(MODEL_NAME)))
                    : selectedSets.contains(str(change.getFullRow().get(OPTION_SET_CODE)));
            if (keep) {
                kept.add(change);
            }
        }
        return kept;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
