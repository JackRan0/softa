package io.softa.starter.metadata.service;

import io.softa.starter.metadata.dto.MetadataChangeSet;

/**
 * Applies an incremental metadata change set to this runtime's {@code sys_*} catalog — the write half of
 * the studio↔runtime deploy contract (the read/export half is {@link MetadataService}).
 */
public interface MetadataApplyService {

    /**
     * Apply an incremental metadata change set: per-row CRUD keyed by <b>business key</b>.
     * Each UPSERT converges one {@code sys_*} row to its desired attributes (located by its business key,
     * or by the prior key via {@code renamedFrom} on a rename); each DELETE removes one row. DDL runs
     * first. App-scoped (studio is the complete source of truth); identity ({@code id} / {@code appCode})
     * is stamped server-side, never trusted from the wire. Idempotent (UPSERT/DELETE) — safe under
     * dispatch retry.
     */
    void applyChanges(MetadataChangeSet changeSet);
}
