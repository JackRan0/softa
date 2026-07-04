package io.softa.starter.metadata.ddl.context;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Model-level DDL context passed to templates.
 */
@Data
public class ModelDdlCtx {

    /** Default primary-key column for non-timeline models. */
    private static final String DEFAULT_PK_COLUMN = "id";

    /**
     * Build a minimal placeholder ModelDdlCtx (only modelName / tableName /
     * pkColumn set), used when a model is referenced by a downstream operation
     * (e.g., field change) before its full model context is available.
     */
    public static ModelDdlCtx placeholder(String modelName) {
        ModelDdlCtx model = new ModelDdlCtx();
        model.setModelName(modelName);
        model.setTableName(modelName == null ? null : StringTools.toUnderscoreCase(modelName));
        model.setPkColumn(DEFAULT_PK_COLUMN);
        model.setAutoIncrementPrimaryKey(false);
        return model;
    }

    private String modelName;
    private String label;
    private String description;
    private String tableName;
    private String oldTableName;
    private String pkColumn;
    private IdStrategy idStrategy;
    private boolean autoIncrementPrimaryKey;
    private boolean renamed;
    private boolean descriptionChanged;
    private final List<FieldDdlCtx> createdFields = new ArrayList<>();
    private final List<FieldDdlCtx> deletedFields = new ArrayList<>();
    private final List<FieldDdlCtx> updatedFields = new ArrayList<>();
    private final List<FieldDdlCtx> renamedFields = new ArrayList<>();
    private final List<IndexDdlCtx> createdIndexes = new ArrayList<>();
    private final List<IndexDdlCtx> deletedIndexes = new ArrayList<>();
    private final List<IndexDdlCtx> updatedIndexes = new ArrayList<>();
    private final List<IndexDdlCtx> renamedIndexes = new ArrayList<>();

    public boolean isHasFieldChanges() {
        return !createdFields.isEmpty()
                || !deletedFields.isEmpty()
                || !updatedFields.isEmpty()
                || !renamedFields.isEmpty();
    }

    public boolean isHasIndexChanges() {
        return !createdIndexes.isEmpty()
                || !deletedIndexes.isEmpty()
                || !updatedIndexes.isEmpty()
                || !renamedIndexes.isEmpty();
    }

    public boolean isHasAlterTableChanges() {
        return descriptionChanged || isHasFieldChanges();
    }
}
