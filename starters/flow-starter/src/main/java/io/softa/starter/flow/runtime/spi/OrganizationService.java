package io.softa.starter.flow.runtime.spi;

import java.util.List;

/**
 * SPI for resolving organizational data.
 * <p>
 * Every user id here — taken or returned — is a login account id, the id approvers act under; an
 * employee id is an employee record's own id, the one a supervisor, a department leader or a record's
 * subject is stored as. Lookups relative to someone start from an employee: a record's subject is
 * one already, and a user is bridged through {@link #getEmployeeIdOfUser}. The default
 * {@code FlowOrgService} reads the conventional HR models; applications implement this interface
 * only to integrate a different organization system.
 * </p>
 */
public interface OrganizationService {

    /**
     * Get user info by user ID.
     */
    ApproverInfo getUserById(Long userId);

    /**
     * Get users by user IDs.
     */
    List<ApproverInfo> getUsersByIds(List<Long> userIds);

    /**
     * Get users by role IDs.
     */
    List<ApproverInfo> getUsersByRoleIds(List<Long> roleIds);

    /**
     * Get users by department IDs.
     *
     * @param deptIds          department IDs
     * @param includeSubDepts  whether to include sub-departments
     */
    List<ApproverInfo> getUsersByDeptIds(List<Long> deptIds, boolean includeSubDepts);

    /**
     * Get users by position IDs.
     */
    List<ApproverInfo> getUsersByPositionIds(List<Long> positionIds);

    /**
     * The employee whose login account is {@code userId}; null when the account has none.
     */
    Long getEmployeeIdOfUser(Long userId);

    /**
     * The supervisor {@code level} levels above an employee, as an approver; null when the chain ends
     * before it or that supervisor has no account.
     *
     * @param employeeId the employee
     * @param level      1 = direct supervisor, 2 = the supervisor's supervisor, and so on
     */
    ApproverInfo getSupervisorOfEmployee(Long employeeId, int level);

    /**
     * Get the department leader.
     */
    ApproverInfo getDeptLeader(Long deptId);

    /**
     * The department of an employee; null when it has none.
     */
    Long getDeptIdOfEmployee(Long employeeId);

    /**
     * Get sub-department IDs of a department.
     */
    List<Long> getSubDeptIds(Long deptId);

    /**
     * Get users holding any of the roles with these codes. Role codes, unlike role ids, are the
     * same in every tenant, so flow templates name roles by code. Empty unless overridden.
     */
    default List<ApproverInfo> getUsersByRoleCodes(List<String> roleCodes) {
        return List.of();
    }

    /**
     * Get the department's HR business partner, walking up to the nearest department that has
     * one. Null unless overridden.
     */
    default ApproverInfo getDeptHrbp(Long deptId) {
        return null;
    }
}

