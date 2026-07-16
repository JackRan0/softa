package io.softa.starter.flow.service;

import java.util.Optional;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.entity.FlowEvent;

/**
 * Service interface for trigger event log persistence and queries.
 */
public interface FlowEventService {

    /**
     * Record a trigger event log entry.
     *
     * @param event the event to persist
     */
    void recordEvent(FlowEvent event);

    /**
     * Paged event query for monitoring views.
     */
    default Page<FlowEvent> searchEvents(FlexQuery query, Page<FlowEvent> page) {
        return page;
    }

    /**
     * Single event by id — the detail read that carries the trigger-parameters
     * payload the list rows exclude.
     * <p>
     * Dimension-specific list finders were removed: {@link #searchEvents} with
     * {@code Filters} covers flowCode / source / instanceId / time-range lookups
     * (REST: {@code GET /flow/events}).
     */
    default Optional<FlowEvent> findEventById(Long id) {
        return Optional.empty();
    }
}

