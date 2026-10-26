package io.softa.starter.flow.service;

import java.util.List;

import io.softa.starter.flow.entity.FlowCcConfig;
import io.softa.starter.flow.enums.CcTiming;

/**
 * Service interface for CC configuration CRUD and queries.
 */
public interface FlowCcConfigService {

    /**
     * Get active CC configurations matching the given flow, node, and timing.
     */
    List<FlowCcConfig> getActiveConfigs(String flowId, String nodeId, CcTiming ccTiming);

    /**
     * Create a CC configuration.
     */
    FlowCcConfig createConfig(FlowCcConfig config);

    /**
     * List all CC configurations for a flow.
     */
    List<FlowCcConfig> listByFlowId(String flowId);

    /**
     * Deactivate a CC configuration.
     */
    void deactivateConfig(Long configId);
}

