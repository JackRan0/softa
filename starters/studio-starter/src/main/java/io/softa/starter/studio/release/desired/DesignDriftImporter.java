package io.softa.starter.studio.release.desired;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IDGenerator;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;
import io.softa.starter.studio.release.dto.DesignMetaTables;
import io.softa.starter.studio.release.dto.MetaKeys;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * Writes an <b>inverted</b> design↔runtime drift back onto a target env's {@code design_*} rows — the
 * write half of import (Softa connector, full-fidelity) / reverse (JDBC connector, structural-only).
 * <p>
 * The drift is computed as "operations to apply to the runtime to match the env design"
 * ({@link DesignAggregateDiffer}); importing runtime state into design-time flips every operation:
 * <ul>
 *   <li>{@code drift.deletedRows} (runtime has, design doesn't) → CREATE on design using {@code fullRow}
 *       (the runtime values), in the declared parent→child order so a child's parent exists first, then
 *       relinked to that target-env parent by business code ({@link #relinkImportedChildFk});</li>
 *   <li>{@code drift.updatedRows} → UPDATE on design with the runtime values in
 *       {@code previousValuesForChangedFields}, located by the design row's <b>business key</b>;</li>
 *   <li>{@code drift.createdRows} (design has, runtime doesn't) → DELETE from design located by business
 *       key, in reverse model order so children drop before parents.</li>
 * </ul>
 * The env↔env sibling of this writer is {@link DesignEnvMerger}; both locate / re-parent by business key
 * through the shared {@link DesignEnvRowOps} primitives (no surrogate id threaded across envs). The caller
 * ({@code DesignAppEnvServiceImpl.applyDrift}) runs this under the env mutex and its own transaction.
 */
@Component
public class DesignDriftImporter {

    private static final String DESIGN_MODEL = DesignModel.class.getSimpleName();
    private static final String DESIGN_FIELD = DesignField.class.getSimpleName();
    private static final String DESIGN_INDEX = DesignModelIndex.class.getSimpleName();
    private static final String DESIGN_OPTION_SET = DesignOptionSet.class.getSimpleName();
    private static final String DESIGN_OPTION_ITEM = DesignOptionItem.class.getSimpleName();
    private static final String MODEL_NAME = MetaKeys.MODEL_NAME;
    private static final String OPTION_SET_CODE = MetaKeys.OPTION_SET_CODE;

    private final ModelService<Serializable> modelService;

    public DesignDriftImporter(ModelService<Serializable> modelService) {
        this.modelService = modelService;
    }

    /**
     * Apply the inverted drift onto {@code target}'s design rows (see the class doc for the op flip).
     * Creates run parent→child, updates in any order, deletes children→parents.
     */
    public void apply(List<RowChangeDTO> drift, DesignAppEnv target) {
        // The drift diff is a flat row-change list; regroup per design meta-model for the FK-safe order.
        Map<String, ModelChangesDTO> byModel = DesignMetaTables.group(drift).stream()
                .collect(Collectors.toMap(ModelChangesDTO::getModelName, m -> m));

        // Inserts: parent before child — follow the declared map order.
        for (String designModel : MetadataConstant.DESIGN_TO_RUNTIME_MODELS.keySet()) {
            ModelChangesDTO changes = byModel.get(designModel);
            if (changes == null) {
                continue;
            }
            List<Map<String, Object>> toCreate = changes.getDeletedRows().stream()
                    .map(RowChangeDTO::getFullRow)
                    .filter(Objects::nonNull)
                    .map(HashMap::new)
                    .map(row -> stampImportedRow(row, target))
                    .collect(Collectors.toList());
            relinkImportedChildFk(designModel, toCreate, target);
            if (!toCreate.isEmpty()) {
                modelService.createList(designModel, Cast.of(toCreate));
            }
        }

        // Updates: order irrelevant. Locate each design row by its business key — the runtime values to
        // write live in previousValuesForChangedFields; fullRow carries the design row's current key.
        for (Map.Entry<String, ModelChangesDTO> entry : byModel.entrySet()) {
            String designModel = entry.getKey();
            List<RowChangeDTO> updates = entry.getValue().getUpdatedRows();
            if (updates.isEmpty()) {
                continue;
            }
            Map<String, Long> idByBizKey = designRowIdsByBizKey(designModel, target);
            List<String> keyAttrs = DesignEnvRowOps.BIZ_KEY_ATTRS.get(designModel);
            List<Map<String, Object>> toUpdate = new ArrayList<>();
            for (RowChangeDTO row : updates) {
                Long id = idByBizKey.get(DesignEnvRowOps.bizKey(keyAttrs, row.getFullRow()));
                if (id == null) {
                    continue;   // design row absent (should not happen for an UPDATE) — skip rather than misroute
                }
                Map<String, Object> payload = new HashMap<>(row.getPreviousValuesForChangedFields());
                payload.put(ModelConstant.ID, id);
                toUpdate.add(payload);
            }
            if (!toUpdate.isEmpty()) {
                modelService.updateList(designModel, toUpdate);
            }
        }

        // Deletes: reverse the declared order so children drop before parents. Located by business key.
        List<String> reversed = new ArrayList<>(MetadataConstant.DESIGN_TO_RUNTIME_MODELS.keySet());
        Collections.reverse(reversed);
        for (String designModel : reversed) {
            ModelChangesDTO changes = byModel.get(designModel);
            if (changes == null || changes.getCreatedRows().isEmpty()) {
                continue;
            }
            Map<String, Long> idByBizKey = designRowIdsByBizKey(designModel, target);
            List<String> keyAttrs = DesignEnvRowOps.BIZ_KEY_ATTRS.get(designModel);
            List<Serializable> ids = new ArrayList<>();
            for (RowChangeDTO row : changes.getCreatedRows()) {
                Long id = idByBizKey.get(DesignEnvRowOps.bizKey(keyAttrs, row.getFullRow()));
                if (id != null) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                modelService.deleteByIds(designModel, Cast.of(ids));
            }
        }
    }

    /** This env's rows of {@code designModel} indexed by their (env-scoped) business key → surrogate id. */
    private Map<String, Long> designRowIdsByBizKey(String designModel, DesignAppEnv target) {
        return DesignEnvRowOps.indexByKey(
                modelService.searchList(designModel,
                        new FlexQuery(new Filters().eq(DesignEnvRowOps.ENV_ID, target.getId()))),
                DesignEnvRowOps.BIZ_KEY_ATTRS.get(designModel));
    }

    /**
     * Stamp a runtime row being imported into the target env's design. The runtime {@code sys_*} catalog
     * carries business values only — no per-env identity — so scope the row to the target env
     * ({@code appId}/{@code envId}) and mint a fresh design surrogate id (dropping the runtime's).
     */
    private static Map<String, Object> stampImportedRow(Map<String, Object> row, DesignAppEnv target) {
        row.put("appId", target.getAppId());
        row.put(DesignEnvRowOps.ENV_ID, target.getId());
        row.put(ModelConstant.ID, IDGenerator.generateLongId());
        return row;
    }

    /**
     * Relink imported child rows to their parent in the TARGET env. A runtime {@code sys_*} child row
     * carries its parent's business code but a {@code modelId}/{@code optionSetId} in the <b>runtime's</b>
     * id-space, foreign to this design env — so the FK is <b>unconditionally overwritten</b> with the
     * target-env parent id that owns the same business code (never "fill if null"). Safe here because the
     * insert loop follows parent→child order, so every parent already exists in the target env. Roots
     * (model / option-set) have no parent FK — no-op.
     */
    private void relinkImportedChildFk(String designModel, List<Map<String, Object>> rows, DesignAppEnv target) {
        String parentModel;
        String codeAttr;
        String fkAttr;
        if (DESIGN_FIELD.equals(designModel) || DESIGN_INDEX.equals(designModel)) {
            parentModel = DESIGN_MODEL;
            codeAttr = MODEL_NAME;
            fkAttr = DesignEnvRowOps.MODEL_ID;
        } else if (DESIGN_OPTION_ITEM.equals(designModel)) {
            parentModel = DESIGN_OPTION_SET;
            codeAttr = OPTION_SET_CODE;
            fkAttr = DesignEnvRowOps.OPTION_SET_ID;
        } else {
            return;   // a root (DesignModel / DesignOptionSet) — no parent FK to relink
        }
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Long> parentIdByCode = new HashMap<>();
        for (Map<String, Object> parent : modelService.searchList(parentModel,
                new FlexQuery(new Filters().eq(DesignEnvRowOps.ENV_ID, target.getId())))) {
            Object code = parent.get(codeAttr);
            Long id = DesignEnvRowOps.asLong(parent.get(ModelConstant.ID));
            if (code != null && id != null) {
                parentIdByCode.put(String.valueOf(code), id);
            }
        }
        DesignEnvRowOps.relinkChildFk(rows, fkAttr, codeAttr, parentIdByCode);
    }
}
