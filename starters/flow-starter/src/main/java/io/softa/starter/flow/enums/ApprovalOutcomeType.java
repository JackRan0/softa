package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result of a record's approval, as handed to its {@code ModelApprovalHandler}: the status the
 * approval instance ended in.
 */
@Getter
@AllArgsConstructor
public enum ApprovalOutcomeType {
    /** Every approval step passed: the instance completed. */
    APPROVED("Approved"),
    /** An approver rejected the record: the instance ended. */
    REJECTED("Rejected"),
    /** An approver sent the record back to its submitter, who may edit it and submit it again as a new approval. */
    RETURNED("Returned"),
    /** The submitter withdrew the record while it waited for approval. */
    WITHDRAWN("Withdrawn"),
    /** The instance was cancelled. */
    CANCELLED("Cancelled"),
    ;

    @JsonValue
    private final String type;
}
