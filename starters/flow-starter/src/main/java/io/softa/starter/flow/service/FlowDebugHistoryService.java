package io.softa.starter.flow.service;

import java.util.List;

import io.softa.starter.flow.entity.FlowDebugHistory;

/**
 * Service interface for debug execution history persistence and queries.
 */
public interface FlowDebugHistoryService {

    /**
     * Record a debug execution history entry.
     *
     * @param history the history entry to persist
     */
    void recordHistory(FlowDebugHistory history);

    /**
     * List debug histories by flow code, ordered by startTime descending.
     *
     * @param flowCode the flow code
     * @return list of debug histories
     */
    List<FlowDebugHistory> listByFlowCode(String flowCode);
}

