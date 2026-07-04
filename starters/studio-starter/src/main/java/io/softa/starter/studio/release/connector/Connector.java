package io.softa.starter.studio.release.connector;

import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.studio.release.desired.AggregateSelection;
import io.softa.starter.studio.release.desired.DesignRows;

/**
 * A connection to a metadata target — the connector spine. A connector fully abstracts "the target
 * runtime": its database flavor (the {@link #dialect()}) plus the forward/reverse
 * schema transfer the converge engine drives ({@link #readChecksums} gate → {@link #readSchema} →
 * {@link #apply}). Routing every runtime touch through this interface is what lets a future
 * {@code JdbcSchemaConnector} drop in without changing publish / drift / import.
 *
 * <p>Why the dialect comes from the connector: a Softa-runtime target must render DDL on the
 * <b>builtin</b> resolver (compile-time annotation knowledge, identical to the boot scanner), not
 * through the {@code design_*}-backed Spring registry. Selecting the dialect per connector makes the
 * annotation and studio lanes converge by construction.
 */
public interface Connector {

    /** DDL dialect of the target this connector addresses — the renderer for CREATE / ALTER / DROP. */
    DdlDialect dialect();

    /**
     * Read the target's per-aggregate checksums — the lightweight gate the comparator diffs
     * against the design side before any full-schema fetch.
     *
     * @param appCode app identity being synchronised (the runtime verifies it)
     */
    RuntimeChecksumsDTO readChecksums(String appCode);

    /**
     * Read the target's <b>full</b> metadata catalog (the five meta-tables) — the "read everything"
     * primitive used to discover an unknown schema (reverse / import), where the caller cannot
     * enumerate the aggregate keys up front.
     *
     * @param appCode app identity being synchronised (the runtime verifies it)
     */
    DesignRows readSchema(String appCode);

    /**
     * Read only the {@code selection} aggregates' rows — the OBSERVED side of the design↔runtime diff,
     * narrowed to the aggregates the checksum gate flagged as changed (incremental fetch).
     * The {@code DesiredStateConverger} fetches only {@code differing ∪ onlyInRuntime}, so the payload is
     * proportional to the change surface rather than the catalog size. An empty selection reads nothing.
     *
     * @param appCode   app identity being synchronised (the runtime verifies it)
     * @param selection which Model / OptionSet aggregates to pull (by business key)
     */
    DesignRows readSchema(String appCode, AggregateSelection selection);

    /**
     * Apply an incremental metadata change set to the target: DDL-first, then per-row CRUD
     * keyed by business key. The single write entry point — publish ships through here.
     *
     * @param appCode   app identity being deployed (the runtime verifies it)
     * @param changeSet the per-row changes (UPSERT/DELETE by business key) + the rename-aware DDL
     */
    void apply(String appCode, MetadataChangeSet changeSet);
}
