package io.softa.starter.flow.runtime.bundle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.design.DesignFlowDefinition;

/**
 * Runtime registry for published compiled flow bundles.
 * <p>
 * All lookups are keyed on {@code bundleId} (exact revision) or {@code designId}
 * (stable flow identity). {@code flowCode} is intentionally absent from the registry
 * API: it is not tenant-unique and cannot be used as a reliable lookup key.
 * </p>
 * <p>
 * Every lookup answers for the current context: a bundle of another tenant reads as absent,
 * platform-shared bundles ({@link #SHARED_TENANT_ID}) are visible to every tenant, and a
 * cross-tenant context sees all of them.
 * </p>
 */
public interface FlowBundleRegistry {

    /** Tenant id of platform-shared bundles: they apply to every tenant. */
    Long SHARED_TENANT_ID = 0L;

    /**
     * Persist a new revision and make it the design's active one.
     *
     * @param definition compiled definition
     * @param design     original design document (stored for editor restore; may be null)
     * @param designId   source design id (required for persistent registries; may be null in tests)
     */
    CompiledFlowDefinition register(CompiledFlowDefinition definition,
                                    DesignFlowDefinition design,
                                    Long designId);

    /** Convenience: publish without a design document. */
    default CompiledFlowDefinition register(CompiledFlowDefinition definition) {
        return register(definition, null, null);
    }

    /**
     * Persist a debug-run bundle: resolvable by {@code bundleId} like any
     * revision (so instances survive restarts), but never active and hidden from
     * revision lists. Purged by the maintenance job after the retention window.
     */
    default CompiledFlowDefinition registerDebug(CompiledFlowDefinition definition,
                                                 DesignFlowDefinition design,
                                                 Long designId) {
        throw new UnsupportedOperationException("Debug bundles are not supported by this registry");
    }

    /** Look up an exact revision by bundle id (primary runtime key). */
    Optional<CompiledFlowDefinition> getByBundleId(Long bundleId);

    /** Look up the active (currently effective) revision for a design id. */
    Optional<CompiledFlowDefinition> getActiveByDesignId(Long designId);

    /** List all revisions for a design id, ordered by revision descending. */
    List<CompiledFlowDefinition> listRevisionsByDesignId(Long designId);

    /** The current tenant's active bundles (one per flow design), ordered by flow code. */
    Collection<CompiledFlowDefinition> list();

    /**
     * Active bundles whose trigger may fire for an event on {@code sourceModel} in the current
     * context: those bound to that model plus those bound to none (API, cron and subflow triggers,
     * or an entity / field trigger without a model filter), from the current tenant and the
     * platform-shared ones — only the shared ones when the context carries no tenant. Callers
     * still apply the full trigger match, tenant check included.
     * <p>The default offers everything {@link #list()} returns.</p>
     */
    default List<CompiledFlowDefinition> listTriggerCandidates(String sourceModel) {
        return List.copyOf(list());
    }

    /**
     * The active revision of a design changed (rollback / roll-forward): make the new one
     * effective. Default no-op; caching implementations should override.
     */
    default void refreshActiveForDesignId(Long designId) {
    }

    /**
     * Drop a bundle from the in-memory cache (used when its row is purged).
     * Default no-op; caching implementations should override.
     */
    default void evict(Long bundleId) {
    }
}
