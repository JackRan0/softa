package io.softa.starter.flow.runtime.spi;

import java.util.List;

/**
 * SPI for resolving organizational data.
 * <p>
 * Every user id here — taken or returned — is a login account id, the id approvers act under.
 * The default {@code FlowOrgService} reads the conventional HR models; applications implement this
 * interface only to integrate a different organization system.
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
     * Get the supervisor of a user.
     *
     * @param userId the user ID
     * @param level  the level of supervisor (1 = direct, 2 = supervisor's supervisor, etc.)
     */
    ApproverInfo getSupervisor(Long userId, int level);

    /**
     * Get the department leader.
     */
    ApproverInfo getDeptLeader(Long deptId);

    /**
     * Get the department ID of a user.
     */
    Long getUserDeptId(Long userId);

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

