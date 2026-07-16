package io.softa.starter.metadata.entity;

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
 * SysModel — metadata catalog row describing a Softa Model.
 *
 * <p>Self-described: this class carries {@code @Model} + per-field {@code @Field}
 * annotations, so the {@code MetadataAnnotationScanner} writes {@code sys_model}
 * rows describing {@code SysModel} itself (and its 7 siblings). The scanner
 * takes a pure-JDBC path against the physical {@code sys_*} tables and does
 * <b>not</b> read its own {@code ModelManager} state, so this self-reference
 * does not create a circular dependency.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Model",
        businessKey = {"modelName"},
        description = "Metadata catalog of models"
)
public class SysModel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    @Field
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

    // Initialized to true (the column is NOT NULL DEFAULT 1) so hand-constructed
    // instances — scanner paths go through AnnotationParser — never insert NULL.
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

    /**
     * One-to-many to {@link SysField} (joins on the surrogate FK {@code modelId}).
     * Has NO {@code sys_model} column — SysCatalog and the DDL builder both exclude
     * X-to-many — but is emitted as a {@code sys_field} row, so the meta-model is
     * self-describing. Populated in memory by {@code ModelManager} (by {@code modelName}).
     */
    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = SysField.class, relatedField = "modelId")
    private List<SysField> modelFields;

    /**
     * One-to-many to {@link SysModelIndex} (joins on the surrogate FK {@code modelId}).
     * Makes the index a first-class child of the Model aggregate, mirroring
     * {@code DesignModel.modelIndexes}; like {@link #modelFields} it has no physical
     * {@code sys_model} column and is populated in memory by {@code ModelManager}.
     */
    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = SysModelIndex.class, relatedField = "modelId")
    private List<SysModelIndex> modelIndexes;
}
