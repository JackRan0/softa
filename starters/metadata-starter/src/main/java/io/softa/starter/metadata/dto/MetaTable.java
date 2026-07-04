package io.softa.starter.metadata.dto;

import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;

/**
 * The runtime {@code sys_*} meta-table a {@link MetaChange} targets (incremental apply).
 * <p>
 * Declared in <b>parent→child</b> order so the apply can sort deterministically without the wire
 * carrying ordering: UPSERTs run in ascending ordinal (roots before children, FK-safe), DELETEs in
 * descending ordinal (children before roots). Each value carries its {@code Sys*} entity so the apply
 * can resolve the model name and the business key (via {@code SysCatalog}).
 */
public enum MetaTable {

    MODEL(SysModel.class),            // root
    OPTION_SET(SysOptionSet.class),   // root
    FIELD(SysField.class),            // child of MODEL (linked by modelName)
    INDEX(SysModelIndex.class),       // child of MODEL (linked by modelName)
    OPTION_ITEM(SysOptionItem.class); // child of OPTION_SET (linked by optionSetCode)

    private final Class<?> sysEntity;

    MetaTable(Class<?> sysEntity) {
        this.sysEntity = sysEntity;
    }

    public Class<?> sysEntity() {
        return sysEntity;
    }

    /** The runtime model name (e.g. {@code "SysField"}) the apply addresses via {@code ModelService}. */
    public String sysModel() {
        return sysEntity.getSimpleName();
    }
}
