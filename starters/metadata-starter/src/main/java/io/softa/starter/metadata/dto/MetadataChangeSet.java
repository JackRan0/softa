package io.softa.starter.metadata.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * The wire contract for the incremental metadata apply: the per-row changes (CRUD keyed by
 * business key) plus the rename-aware DDL to run first. Replaces the whole-aggregate
 * {@code DesiredStatePayload} — the runtime no longer receives full aggregates and
 * reconciles; it applies exactly the shipped row changes.
 *
 * @param changes the per-row changes; the runtime sorts them (UPSERT parent→child, DELETE child→parent)
 * @param ddl     rename-aware DDL statements executed <b>before</b> the rows; empty when the
 *                target env's {@code autoExecuteDDL=false} (a DBA runs it out of band) or nothing
 *                structural changed
 */
public record MetadataChangeSet(
        List<MetaChange> changes,
        List<String> ddl) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public MetadataChangeSet {
        changes = changes == null ? List.of() : List.copyOf(changes);
        ddl = ddl == null ? List.of() : List.copyOf(ddl);
    }

    /** Nothing to apply — the env already matches the design. */
    public boolean isEmpty() {
        return changes.isEmpty() && ddl.isEmpty();
    }
}
