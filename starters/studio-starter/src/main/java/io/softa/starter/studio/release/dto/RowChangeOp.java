package io.softa.starter.studio.release.dto;

/**
 * Studio-local row-change operation (wire reshape): the 3-op verb the diff produces and the
 * DDL renderer needs — {@code CREATE} → create table, {@code UPDATE} → alter / CHANGE COLUMN / ADD
 * COLUMN, {@code DELETE} → drop. Distinct from the metadata-starter wire {@code ChangeOp}
 * (UPSERT / DELETE), which collapses CREATE+UPDATE and is lossy for the DDL lane.
 */
public enum RowChangeOp {
    CREATE,
    UPDATE,
    DELETE
}
