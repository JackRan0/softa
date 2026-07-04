package io.softa.starter.studio.meta.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.EntityService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.meta.support.DesignWriteStamper;

/**
 * Base controller for the no-code {@code design_*} entities. It claims the {@code /DesignX/create*}
 * and {@code /DesignX/update*} routes away from the generic {@code ModelController} so every design
 * write passes through the per-env identity stamp ({@link DesignWriteStamper}) — minting
 * {@code id} and resolving {@code envId} on create, freezing {@code envId} on update.
 *
 * <p>Behaviour otherwise mirrors the generic model write API exactly (same {@link ConvertType},
 * {@code @DataMask}, id formatting, batch-size guard). Read / copy verbs are not declared here and
 * fall through to {@code ModelController} unchanged.
 *
 * <p><b>Delete</b> is also claimed here (away from {@code ModelController}) so a design delete runs
 * through the typed {@link EntityService} — letting {@code DesignModel} / {@code DesignOptionSet}
 * cascade-delete their children ({@code DesignField}/{@code DesignModelIndex}, {@code DesignOptionItem})
 * in the same env, instead of leaving orphan child rows behind. Controllers whose service has no
 * delete override (e.g. {@code DesignField}) keep the generic delete behaviour unchanged.
 *
 * <p>Subclasses supply {@link #modelName()} and may override the {@code onCreate*}/{@code onUpdate*}
 * hooks to add their own per-row stamping (e.g. {@code DesignField}'s relation-type resolution) — the
 * default hook applies only the per-env identity.
 */
public abstract class AbstractDesignWriteController<S extends EntityService<T, Long>, T extends AbstractModel>
        extends EntityController<S, T, Long> {

    @Autowired
    protected ModelService<Long> modelService;

    @Autowired
    protected DesignWriteStamper designWriteStamper;

    /** The design meta-model name these writes target (e.g. {@code "DesignModel"}). */
    protected abstract String modelName();

    // ----------------------------------------------------------------- stamping hooks

    /** Per-row create stamping. Default: per-env identity. Override to add more (call {@code super}). */
    protected void onCreate(Map<String, Object> row) {
        designWriteStamper.stampCreate(row);
    }

    protected void onCreate(List<Map<String, Object>> rows) {
        rows.forEach(this::onCreate);
    }

    /** Per-row update stamping. Default: freeze per-env identity + capture a rename. Override to add more (call {@code super}). */
    protected void onUpdate(Map<String, Object> row) {
        designWriteStamper.stampUpdate(row);
        captureRename(row);
    }

    protected void onUpdate(List<Map<String, Object>> rows) {
        rows.forEach(this::onUpdate);
    }

    /**
     * The single business-key field this entity can be renamed on ({@code modelName} / {@code fieldName}
     * / {@code optionSetCode} / {@code itemCode}), or {@code null} when the entity is not renameable
     * (e.g. an index). Subclasses of renameable entities override this.
     */
    protected String renameKeyField() {
        return null;
    }

    /**
     * When an update renames the entity (its {@link #renameKeyField()} value changes vs the
     * stored row), record the prior name in {@code renamedFrom} (single-step, replace) so publish/merge
     * can pair the renamed row by its old name — the only bridge, since identity is the business key
     * (no surrogate is threaded). No-op when the entity is not renameable, the update doesn't touch the
     * name, or the name is unchanged.
     */
    private void captureRename(Map<String, Object> row) {
        String keyField = renameKeyField();
        if (keyField == null || !row.containsKey(keyField)) {
            return;
        }
        Object newName = row.get(keyField);
        Object id = row.get("id");
        if (newName == null || id == null) {
            return;
        }
        Object oldName = modelService.getById(modelName(), ((Number) id).longValue(), List.of(keyField))
                .map(existing -> existing.get(keyField))
                .orElse(null);
        if (oldName != null && !oldName.equals(newName)) {
            row.put("renamedFrom", oldName);
        }
    }

    // ----------------------------------------------------------------- create

    @PostMapping("/createOne")
    @DataMask
    public ApiResponse<Long> createOne(@RequestBody Map<String, Object> row) {
        onCreate(row);
        return ApiResponse.success(modelService.createOne(modelName(), row));
    }

    @PostMapping("/createOneAndFetch")
    @DataMask
    public ApiResponse<Map<String, Object>> createOneAndFetch(@RequestBody Map<String, Object> row) {
        onCreate(row);
        return ApiResponse.success(modelService.createOneAndFetch(modelName(), row, ConvertType.REFERENCE));
    }

    @PostMapping("/createList")
    public ApiResponse<List<Long>> createList(@RequestBody List<Map<String, Object>> rows) {
        this.validateBatchSize(rows.size());
        onCreate(rows);
        return ApiResponse.success(modelService.createList(modelName(), rows));
    }

    @PostMapping("/createListAndFetch")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> createListAndFetch(@RequestBody List<Map<String, Object>> rows) {
        this.validateBatchSize(rows.size());
        onCreate(rows);
        return ApiResponse.success(modelService.createListAndFetch(modelName(), rows, ConvertType.REFERENCE));
    }

    // ----------------------------------------------------------------- update

    @PostMapping("/updateOne")
    @DataMask
    public ApiResponse<Boolean> updateOne(@RequestBody Map<String, Object> row) {
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(modelName(), row);
        onUpdate(row);
        return ApiResponse.success(modelService.updateOne(modelName(), row));
    }

    @PostMapping("/updateOneAndFetch")
    @DataMask
    public ApiResponse<Map<String, Object>> updateOneAndFetch(@RequestBody Map<String, Object> row) {
        Assert.notEmpty(row, "The data to be updated cannot be empty!");
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(modelName(), row);
        onUpdate(row);
        return ApiResponse.success(modelService.updateOneAndFetch(modelName(), row, ConvertType.REFERENCE));
    }

    @PostMapping("/updateList")
    public ApiResponse<Boolean> updateList(@RequestBody List<Map<String, Object>> rows) {
        Assert.notEmpty(rows, "The data to be updated cannot be empty!");
        this.validateBatchSize(rows.size());
        IdUtils.formatMapIds(modelName(), rows);
        onUpdate(rows);
        return ApiResponse.success(modelService.updateList(modelName(), rows));
    }

    @PostMapping("/updateListAndFetch")
    @DataMask
    public ApiResponse<List<Map<String, Object>>> updateListAndFetch(@RequestBody List<Map<String, Object>> rows) {
        Assert.notEmpty(rows, "The data to be updated cannot be empty!");
        this.validateBatchSize(rows.size());
        IdUtils.formatMapIds(modelName(), rows);
        onUpdate(rows);
        return ApiResponse.success(modelService.updateListAndFetch(modelName(), rows, ConvertType.REFERENCE));
    }

    // ----------------------------------------------------------------- delete
    //
    // Routed through the typed EntityService (not the generic ModelController) so a parent design
    // entity can cascade-delete its children in the same env (see class javadoc).

    @PostMapping("/deleteById")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        Assert.notNull(id, "`id` cannot be null when deleting data!");
        return ApiResponse.success(service.deleteById(id));
    }

    @PostMapping("/deleteByIds")
    public ApiResponse<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        Assert.allNotNull(ids, "ids cannot contain null values: {0}", ids);
        this.validateBatchSize(ids.size());
        return ApiResponse.success(service.deleteByIds(ids));
    }
}
