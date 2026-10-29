package io.softa.starter.flow.runtime.spi;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link ModelApprovalHandler} hands over when a record is submitted: the approval
 * instance's title and the routing variables its flow decides on (amounts, durations, types,
 * department, company...), and what the approval lists show of the record besides its subject.
 * Computed on the server only — nothing the client sent is passed through.
 *
 * @param title         instance title shown in inboxes and notifications
 * @param variables     the flow's start variables, null values allowed; never null itself
 * @param effectiveDate the date the record takes effect, when its model has one; null otherwise
 * @param code          the record's own number, when its model has one; null otherwise
 */
public record ApprovalSubmission(String title, Map<String, Object> variables, LocalDate effectiveDate, String code) {

    public ApprovalSubmission {
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** A submission of a record with no effective date and no number of its own. */
    public ApprovalSubmission(String title, Map<String, Object> variables) {
        this(title, variables, null, null);
    }
}
