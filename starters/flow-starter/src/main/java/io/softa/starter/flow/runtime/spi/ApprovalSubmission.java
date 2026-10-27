package io.softa.starter.flow.runtime.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link ModelApprovalHandler} hands over when a record is submitted: the approval
 * instance's title and the routing variables its flow decides on (amounts, durations, types,
 * department, company...). Computed on the server only — nothing the client sent is passed through.
 *
 * @param title     instance title shown in inboxes and notifications
 * @param variables the flow's start variables, null values allowed; never null itself
 */
public record ApprovalSubmission(String title, Map<String, Object> variables) {

    public ApprovalSubmission {
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
