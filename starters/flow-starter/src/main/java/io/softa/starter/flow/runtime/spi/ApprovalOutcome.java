package io.softa.starter.flow.runtime.spi;

import io.softa.starter.flow.enums.ApprovalOutcomeType;

/**
 * The result of a record's approval, handed to {@link ModelApprovalHandler#afterOutcome}: how its approval
 * ended, or that its applicant cancelled the draft.
 *
 * @param type       how the approval ended
 * @param instanceId the approval instance; null for a draft cancelled before any approval of it ran
 * @param actorId    the user whose action decided it — the last approver, the rejecting or returning
 *                   approver, the withdrawing submitter, the cancelling applicant; null when no user decided it
 * @param comment    that user's comment, if any
 */
public record ApprovalOutcome(ApprovalOutcomeType type, String instanceId, String actorId, String comment) {
}
