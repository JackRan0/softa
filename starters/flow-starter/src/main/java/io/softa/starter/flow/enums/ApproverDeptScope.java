package io.softa.starter.flow.enums;

import java.util.Set;

/**
 * Which department a department-based approver source — {@code DeptLeader}, {@code DeptHrbp},
 * {@code Department}, {@code RoleQuery} — works on: its {@code deptScope}.
 */
public enum ApproverDeptScope {

    /** No department: every holder of the roles ({@code RoleQuery} only). */
    ALL,

    /**
     * The department of the record's subject, the employee it is about. An instance without a record has
     * no subject and takes its initiator's department.
     */
    SUBJECT_DEPT,

    /** The department of the initiator, the user who submitted the record. */
    INITIATOR_DEPT,

    /** The department(s) the source names: {@code deptId}, or {@code deptIds} for {@code Department}. */
    SPECIFIC_DEPT,

    /** The department id(s) held by the variable the source names in {@code deptVariable}. */
    VARIABLE_DEPT,
    ;

    /** The scopes a source that resolves within one or more departments accepts: all but {@link #ALL}. */
    public static final Set<ApproverDeptScope> DEPARTMENT_SCOPES = Set.of(SUBJECT_DEPT, INITIATOR_DEPT, SPECIFIC_DEPT,
            VARIABLE_DEPT);

    /**
     * The value an approver source names, or {@code fallback} when it names none.
     *
     * @throws IllegalArgumentException (the JDK one) for a value that names none of these
     */
    public static ApproverDeptScope of(Object value, ApproverDeptScope fallback) {
        return value == null || value.toString().isBlank() ? fallback : valueOf(value.toString());
    }
}
