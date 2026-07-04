package io.softa.starter.metadata.dto;

/**
 * The operation of a {@link MetaChange}. Only two — CREATE and UPDATE collapse into an
 * idempotent UPSERT-by-business-key (converge the row to its desired attributes), which makes
 * the whole apply retry-safe.
 */
public enum ChangeOp {
    UPSERT,
    DELETE
}
