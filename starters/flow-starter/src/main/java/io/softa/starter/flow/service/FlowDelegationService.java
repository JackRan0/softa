package io.softa.starter.flow.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.entity.FlowDelegation;

/**
 * Service interface for delegation rule CRUD and queries.
 */
public interface FlowDelegationService {

    /**
     * Create a delegation rule and return the saved entity.
     */
    FlowDelegation createDelegation(FlowDelegation delegation);


    /**
     * Find the best matching active delegation for a delegator, considering scope (ALL, flow-code, node-id).
     */
    Optional<FlowDelegation> findActiveDelegation(String delegatorId, String flowId, String nodeId, LocalDateTime now);

    /**
     * Expire delegations that have passed their end time and have autoExpire enabled.
     *
     * @return the number of expired delegations
     */
    int expireDelegations(LocalDateTime now);

    /**
     * Cancel (deactivate) one of the requester's delegations.
     *
     * @throws io.softa.starter.flow.runtime.exception.FlowAuthorizationException when the requester
     *         has no delegation of that id — another user's and a missing one are not told apart
     */
    void cancelDelegation(Long delegationId, String requesterId);

    /**
     * List delegations created by a delegator.
     */
    List<FlowDelegation> getMyDelegations(String delegatorId);

    /**
     * List active delegations assigned to a delegate.
     */
    List<FlowDelegation> getDelegationsToMe(String delegateId);

    /**
     * Increment the delegated task count and update last delegation time.
     */
    void recordDelegationUsage(Long delegationId);
}

