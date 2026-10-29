package io.softa.starter.flow.runtime.spi;

import io.softa.starter.flow.enums.ApprovalOutcomeType;

/**
 * The result of a record's approval, handed to {@link ModelApprovalHandler#afterOutcome}.
 *
 * @param type       how the approval ended
 * @param instanceId the approval instance
 * @param actorId    the user whose action decided it — the last approver, the rejecting or returning
 *                   approver, the withdrawing submitter; null when no user decided it
 * @param comment    that user's comment, if any
 */
public record ApprovalOutcome(ApprovalOutcomeType type, String instanceId, String actorId, String comment) {
}
