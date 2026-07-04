package io.softa.starter.metadata.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * One row-level metadata change keyed by its <b>business key</b> — the unit of the incremental
 * apply (logicalId removed). {@code UPSERT} converges the {@code sys_*} row
 * to {@code attrs}; {@code DELETE} removes it.
 *
 * <p>Identity is the row's business key, carried inside {@code attrs} (model = {@code modelName};
 * field = {@code modelName + fieldName}; etc.). The apply locates the runtime row by that key
 * ({@code findByBusinessKey}); a rename is bridged by {@code renamedFrom} (the prior key).
 *
 * <p><b>attrs</b> carries the row's business key + business data only. It must NOT carry identity /
 * server-controlled columns (surrogate {@code id}, {@code appCode}); the runtime
 * whitelists to known {@code sys_*} columns and stamps identity itself. For DELETE, {@code attrs} need
 * only carry the business-key columns (the locator).
 *
 * <p><b>renamedFrom</b>: the single immediately-prior business-key name when this change is a
 * rename, else null. It lets the apply locate a renamed row by its <i>old</i> business key — the case the
 * rename-aware DDL alone cannot pair. The rename-aware DDL is still rendered studio-side and travels as
 * pre-rendered strings ({@link MetadataChangeSet#ddl()}).
 */
public record MetaChange(
        MetaTable table,
        ChangeOp op,
        Map<String, Object> attrs,
        String renamedFrom) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Convenience: a change with no declared rename ({@code renamedFrom = null}). */
    public MetaChange(MetaTable table, ChangeOp op, Map<String, Object> attrs) {
        this(table, op, attrs, null);
    }
}
