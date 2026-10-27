package io.softa.starter.flow.runtime.spi.impl;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.spi.ApproverInfo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FlowOrgService} over the default HR conventions: approvers are login accounts, employees
 * without an account count as nobody, a department without a leader or HRBP defers to its parent,
 * and every read skips the caller's permission checks.
 */
class FlowOrgServiceTest {

    private final Map<String, List<Map<String, Object>>> tables = new HashMap<>();
    private final List<Boolean> permissionChecksSkipped = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private FlowOrgService org;

    @BeforeEach
    void setUp() {
        when(modelService.searchList(anyString(), any(FlexQuery.class))).thenAnswer(invocation ->
                rows(invocation.getArgument(0), ((FlexQuery) invocation.getArgument(1)).getFilters()));
        when(modelService.getById(anyString(), anyLong(), anyCollection())).thenAnswer(invocation ->
                rows(invocation.getArgument(0), Filters.of("id", Operator.EQUAL, invocation.getArgument(1)))
                        .stream().findFirst());
        org = new FlowOrgService(modelService, new OrganizationServiceProperties());

        employee(1L, 1001L, "Alice", 10L, 2L);
        employee(2L, 1002L, "Bob", 10L, 3L);
        employee(3L, null, "Carol", 1L, null);
        employee(4L, 1004L, "Dora", 1L, null);
        department(1L, null, 4L, 4L);
        department(10L, 1L, 3L, null);
        row("Role", Map.of("id", 50L, "code", "HR_ADMIN"));
        row("UserRoleRel", Map.of("id", 1L, "userId", 1004L, "roleId", 50L));
        row("UserRoleRel", Map.of("id", 2L, "userId", 1099L, "roleId", 50L));
    }

    @Test
    void theSupervisorChainEndsAtAnEmployeeWithoutAccount() {
        assertEquals(1002L, org.getSupervisor(1001L, 1).getUserId());
        assertNull(org.getSupervisor(1001L, 2));
    }

    @Test
    void aLeaderOrHrbpIsLookedForUpTheDepartmentTree() {
        // Department 10's leader has no account and it has no HRBP: both defer to department 1.
        assertEquals(new ApproverInfo(1004L, "Dora", 1L, null), org.getDeptLeader(10L));
        assertEquals(1004L, org.getDeptHrbp(10L).getUserId());
    }

    @Test
    void roleHoldersAreAccountsEvenWithoutAnEmployeeProfile() {
        List<ApproverInfo> holders = org.getUsersByRoleCodes(List.of("HR_ADMIN"));

        assertEquals(List.of(1004L, 1099L), holders.stream().map(ApproverInfo::getUserId).toList());
        assertEquals("Dora", holders.getFirst().getUserName());
    }

    @Test
    void departmentMembersAreTheirAccountsSubDepartmentsIncluded() {
        List<ApproverInfo> members = org.getUsersByDeptIds(List.of(1L), true);

        assertEquals(Set.of(1001L, 1002L, 1004L), members.stream().map(ApproverInfo::getUserId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(10L, org.getUserDeptId(1001L));
    }

    @Test
    void everyReadSkipsTheCallersPermissionChecks() {
        org.getSupervisor(1001L, 1);
        org.getUsersByRoleCodes(List.of("HR_ADMIN"));

        assertFalse(permissionChecksSkipped.isEmpty());
        assertTrue(permissionChecksSkipped.stream().allMatch(Boolean::booleanValue));
    }

    private void employee(Long id, Long accountId, String name, Long deptId, Long supervisorId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("userId", accountId);
        row.put("fullName", name);
        row.put("departmentId", deptId);
        row.put("piEmployeeId", supervisorId);
        row("Employee", row);
    }

    private void department(Long id, Long parentId, Long leaderId, Long hrbpId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("parentId", parentId);
        row.put("picEmpId", leaderId);
        row.put("hrbpEmpId", hrbpId);
        row("Department", row);
    }

    private void row(String model, Map<String, Object> row) {
        tables.computeIfAbsent(model, k -> new ArrayList<>()).add(row);
    }

    /** The rows of {@code model} matching a single EQUAL or IN condition — all FlowOrgService issues. */
    private List<Map<String, Object>> rows(String model, Filters filters) {
        permissionChecksSkipped.add(ContextHolder.existContext() && ContextHolder.getContext().isSkipPermissionCheck());
        FilterUnit unit = filters.getFilterUnit();
        return tables.getOrDefault(model, List.of()).stream()
                .filter(row -> switch (unit.getOperator()) {
                    case EQUAL -> Objects.equals(row.get(unit.getField()), unit.getValue());
                    case IN -> row.get(unit.getField()) != null
                            && ((Collection<?>) unit.getValue()).contains(row.get(unit.getField()));
                    default -> throw new IllegalArgumentException("Unexpected operator " + unit.getOperator());
                })
                .toList();
    }
}
