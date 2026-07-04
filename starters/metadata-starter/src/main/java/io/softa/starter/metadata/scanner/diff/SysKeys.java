package io.softa.starter.metadata.scanner.diff;

import io.softa.starter.metadata.entity.*;

/**
 * Single source of truth for the logical (business-key) identity of the five
 * annotation-managed {@code sys_*} entities, mirroring each entity's
 * {@code @Model.businessKey}:
 *
 * <ul>
 *   <li>{@code SysModel} → {@code modelName}</li>
 *   <li>{@code SysField} → {@code modelName + "." + fieldName}</li>
 *   <li>{@code SysOptionSet} → {@code optionSetCode}</li>
 *   <li>{@code SysOptionItem} → {@code optionSetCode + "." + itemCode}</li>
 *   <li>{@code SysModelIndex} → {@code modelName + "." + indexName}</li>
 * </ul>
 *
 * <p>Used by {@code DiffEngine} (diff identity) and the checker's drift report
 * — one composition rule, shared consumers.
 */
public final class SysKeys {

    private SysKeys() {
    }

    public static String of(SysModel m) {
        return m.getModelName();
    }

    public static String of(SysField f) {
        return f.getModelName() + "." + f.getFieldName();
    }

    public static String of(SysOptionSet os) {
        return os.getOptionSetCode();
    }

    public static String of(SysOptionItem i) {
        return i.getOptionSetCode() + "." + i.getItemCode();
    }

    public static String of(SysModelIndex idx) {
        return idx.getModelName() + "." + idx.getIndexName();
    }
}
