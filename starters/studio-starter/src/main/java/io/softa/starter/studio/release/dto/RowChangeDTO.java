package io.softa.starter.studio.release.dto;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.metadata.dto.MetaTable;

/**
 * A single metadata row change: <b>self-describing</b> via {@link #op} + {@link #table}, keyed by its
 * <b>business key</b> (in {@link #fullRow}; no logicalId). Produced by
 * {@link io.softa.starter.studio.release.desired.DesignAggregateDiffer} and consumed directly by the DDL
 * renderer, env↔env merge, runtime apply, and drift report — regrouped per table via
 * {@link DesignMetaTables#group}.
 */
@Data
@NoArgsConstructor
public class RowChangeDTO {

    /** CREATE / UPDATE / DELETE — the row-change verb. */
    private RowChangeOp op;

    /** The typed meta-table this row targets. */
    private MetaTable table;

    /** Full effective row snapshot; IS the wire {@code MetaChange.attrs}. */
    private Map<String, Object> fullRow = new HashMap<>();

    /**
     * Sparse OLD values keyed by the changed columns; non-empty only on UPDATE. Studio-local — never
     * shipped. The DDL "changed-columns" gate is its {@code keySet()} (CREATE = all columns; empty here).
     */
    private Map<String, Object> previousValuesForChangedFields = new HashMap<>();

    /**
     * The single immediately-prior business-key name, copied from the desired row's
     * {@code renamedFrom} — set on a renamed row, else null. Shipped on the wire ({@code
     * MetaChange.renamedFrom}) so the apply can locate an un-adopted renamed row by its old key.
     */
    private String renamedFrom;

}
