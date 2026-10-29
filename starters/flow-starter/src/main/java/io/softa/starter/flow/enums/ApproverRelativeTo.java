package io.softa.starter.flow.enums;

/**
 * Whose place in the organization a {@code Supervisor} approver source starts from — its
 * {@code relativeTo}.
 */
public enum ApproverRelativeTo {

    /**
     * The record's subject: the employee it is about. The default, so a request submitted on someone's
     * behalf goes to their supervisor, not the submitter's. An instance without a record has no subject
     * and starts from its initiator.
     */
    SUBJECT,

    /** The initiator: the user who submitted the record. */
    INITIATOR,
    ;

    /**
     * The value an approver source names, or {@code fallback} when it names none.
     *
     * @throws IllegalArgumentException (the JDK one) for a value that names none of these
     */
    public static ApproverRelativeTo of(Object value, ApproverRelativeTo fallback) {
        return value == null || value.toString().isBlank() ? fallback : valueOf(value.toString());
    }
}
