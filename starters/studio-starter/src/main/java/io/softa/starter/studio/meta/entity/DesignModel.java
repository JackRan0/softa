package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.StorageType;

/**
 * DesignModel Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        // copy is disabled — duplicating an env-scoped row would clone its
        // (envId, businessKey) into the same env (a silent invariant break / UNIQUE collision). New
        // logical entities come from no-code create or the env cloner/merger, never the copy API.
        copyable = false,
        // envId scopes the businessKey — each env owns its own "modelName"
        // (envId is globally unique and belongs to one app, so it transitively scopes per-app too).
        // The write path stamps + app-validates envId (DesignWriteStamper); the physical
        // UNIQUE(env_id, model_name) index (V21, enabled by hard delete) enforces it at the DB.
        businessKey = {"envId", "modelName"},
        displayName = {"modelName", "label"},
        searchName = {"modelName", "label"},
        defaultOrder = "modelName"
)
public class DesignModel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID")
    private Long appId;

    // Per-env design: envId scopes this row to one environment. NOT NULL (V19); V21 added the
    // physical UNIQUE(env_id, model_name) — the per-env business-key identity (no logicalId).
    @Field(label = "Env ID")
    private Long envId;

    @Field(required = true)
    private String label;

    @Field(required = true)
    private String modelName;

    /** Single immediately-prior model name for a declared rename; excluded from checksum/diff. */
    @Field
    private String renamedFrom;

    @Field
    private List<String> displayName;

    @Field
    private List<String> searchName;

    @Field
    private Orders defaultOrder;

    @Field
    private String tableName;

    @Field(label = "Enable Soft Delete")
    private Boolean softDelete;

    @Field(label = "Soft Delete Field Name")
    private String softDeleteField;

    @Field(label = "Enable Active Control")
    private Boolean activeControl;

    @Field(label = "Is Timeline Model")
    private Boolean timeline;

    @Field(label = "ID Strategy")
    private IdStrategy idStrategy;

    @Field
    private StorageType storageType;

    @Field(label = "Enable Version Lock")
    private Boolean versionLock;

    @Field(label = "Enable Multi-tenancy")
    private Boolean multiTenant;

    // Structural mirror of sys_model.copyable governance; the cross-lane checksum requires
    // design_* and sys_* to match field-for-field. Initialized true (column is NOT NULL DEFAULT 1).
    @Field(defaultValue = "true")
    private Boolean copyable = Boolean.TRUE;

    @Field
    private String dataSource;

    @Field(label = "Business Primary Key")
    private List<String> businessKey;

    @Field
    private String partitionField;

    @Field(length = 512)
    private String description;

    // Studio is id-based (rename-stable): the FK column modelId stores the parent DesignModel's id,
    // so a model rename (modelName change) never orphans its children. relatedField points at that
    // id column → the OneToManyProcessor joins on parent.id (DesignModel has no field named "modelId").
    @Field(fieldType = FieldType.ONE_TO_MANY,
            relatedModel = DesignField.class, relatedField = "modelId")
    private List<DesignField> modelFields;

    @Field(fieldType = FieldType.ONE_TO_MANY,
            relatedModel = DesignModelIndex.class, relatedField = "modelId")
    private List<DesignModelIndex> modelIndexes;
}
