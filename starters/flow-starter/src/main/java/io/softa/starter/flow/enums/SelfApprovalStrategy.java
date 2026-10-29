package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What an approval step does when the record's own people are among its approvers ("本人是审批人时"): its
 * submitter, or the user of the employee the record is about. A flow setting, like
 * {@link ApproverDedupStrategy}.
 *
 * <p>Either way it is an approval business rule, decided per flow: nobody is quietly dropped from a step,
 * and an automatic approval writes a real {@code AUTO_APPROVE} record, so the approval history shows who
 * passed the step and why.
 */
@Getter
@AllArgsConstructor
public enum SelfApprovalStrategy {

    /**
     * They count as having approved: nobody is asked to approve their own record. Whether the step then
     * passes is the step's own approval mode — an any-one step passes, an all-approve step still waits for
     * the others. This is the default when unconfigured.
     */
    AUTO_APPROVE("AutoApprove"),

    /** No exception: they are asked like any other approver, and approve or reject the record themselves. */
    REQUIRE_APPROVAL("RequireApproval");

    @JsonValue
    private final String type;
}
