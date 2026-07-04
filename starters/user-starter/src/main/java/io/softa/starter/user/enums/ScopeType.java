package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Scope type for data-range filtering.
 * Static scopes (admin-fixed scopeExpr): ALL / DEPT_SUBTREE / LEGAL_ENTITY (with expr) / MANAGED_DEPARTMENTS (with expr) / CUSTOM (literal-only).
 * Dynamic scopes (require principal.employeeContext): SELF / DIRECT_REPORTS / LEGAL_ENTITY (no expr) / MANAGED_DEPARTMENTS (no expr) / CUSTOM (with $principal.xxx).
 * Pure users (no employeeContext) get FilterCondition.EMPTY on dynamic scopes.
 */
@Getter
@AllArgsConstructor
public enum ScopeType {
    ALL("All", "No row restriction"),
    SELF("Self", "Only own record (uses employeeId)"),
    DIRECT_REPORTS("DirectReports", "Direct reports (uses employeeId as manager_id)"),
    DEPT_SUBTREE("DeptSubtree", "Subtree of a specific department (scopeExpr.deptId required)"),
    MANAGED_DEPARTMENTS("ManagedDepartments", "Departments managed by user (scopeExpr.deptIds optional)"),
    LEGAL_ENTITY("LegalEntity", "Within a specific legal entity (scopeExpr.legalEntityId optional)"),
    CREATED_BY_SELF("CreatedBySelf", "Rows created by the current user (uses createdId = principal.userId; works for pure users too)"),
    CUSTOM("Custom", "Custom filter expression (scopeExpr is FilterCondition; supports $principal.xxx refs)")
    ;

    @JsonValue
    private final String code;

    private final String description;
}
