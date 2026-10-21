package io.softa.starter.flow.service;

import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.entity.FlowBundle;

/**
 * Service interface for persistent flow bundle CRUD and revision management.
 * <p>
 * All revision-management operations are keyed by {@code designId}
 * ({@link io.softa.starter.flow.entity.FlowDesign#getId()}), the stable, globally-unique
 * identity for a flow across revisions and tenants.
 * </p>
 */
public interface FlowBundleService {

    Optional<FlowBundle> findById(Long id);

    void saveBundle(FlowBundle bundle);

    Optional<FlowBundle> findActiveByDesignId(Long designId);

    /** Id of the design's active revision, read without the bundle's JSON columns. */
    Optional<Long> findActiveBundleId(Long designId);

    List<FlowBundle> listRevisionsByDesignId(Long designId);

    /**
     * All currently active bundles (one per design) of the current tenant, carrying only what
     * {@link io.softa.starter.flow.runtime.bundle.FlowBundleMapper#toDefinition} reads.
     */
    List<FlowBundle> getAllActiveFlow();

    int getNextRevision(Long designId);

    void markAsActive(Long designId, Integer revision);

    Optional<FlowBundle> activateBundle(Long bundleId);
}
