package io.softa.starter.flow.runtime.spi.impl;

import java.util.*;
import java.util.function.Supplier;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;

/**
 * Default {@link OrganizationService}: reads the organization through the metadata-driven
 * {@link ModelService}, by the HR conventions permission-starter reads — {@code Employee},
 * {@code Department}, and user-starter's {@code UserRoleRel} and {@code Role}. Model and field
 * names can be overridden through {@link OrganizationServiceProperties}.
 *
 * <p>Approvers are login accounts. An employee takes part through the account on
 * {@code Employee.userId}; an employee without an account counts as nobody, so a step resolving
 * only to such employees is empty and follows its empty-approver strategy. A role holder is an
 * account, and approves even without an employee profile. Supervisors, department leaders and
 * HRBPs are stored as employee ids and translated to their accounts; a department without a
 * leader (or HRBP) defers to its parent's.
 *
 * <p>Every read skips row-scope and field checks, the tenant still applying: who approves a
 * submission must not depend on what the submitter may see.
 */
public class FlowOrgService implements OrganizationService {

    private final ModelService<Long> modelService;
    private final OrganizationServiceProperties props;

    @SuppressWarnings("unchecked")
    public FlowOrgService(ModelService<?> modelService, OrganizationServiceProperties props) {
        this.modelService = (ModelService<Long>) modelService;
        this.props = props;
    }

    @Override
    public ApproverInfo getUserById(Long userId) {
        if (userId == null) {
            return null;
        }
        List<ApproverInfo> users = getUsersByIds(List.of(userId));
        return users.isEmpty() ? null : users.getFirst();
    }

    @Override
    public List<ApproverInfo> getUsersByIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return toApprovers(employees(Filters.of(props.getAccountIdField(), Operator.IN, userIds)));
    }

    @Override
    public List<ApproverInfo> getUsersByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        FlexQuery query = new FlexQuery(Filters.of(props.getRoleIdField(), Operator.IN, roleIds));
        query.setFields(List.of(props.getRoleUserIdField()));
        List<Long> accountIds = read(() -> modelService.searchList(props.getRoleModel(), query)).stream()
                .map(row -> toLong(row.get(props.getRoleUserIdField())))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ApproverInfo> employees = new HashMap<>();
        getUsersByIds(accountIds).forEach(user -> employees.put(user.getUserId(), user));
        return accountIds.stream()
                .map(accountId -> employees.getOrDefault(accountId, new ApproverInfo(accountId, null)))
                .toList();
    }

    @Override
    public List<ApproverInfo> getUsersByRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        FlexQuery query = new FlexQuery(Filters.of(props.getRoleCodeField(), Operator.IN, roleCodes));
        query.setFields(List.of(ModelConstant.ID));
        List<Long> roleIds = read(() -> modelService.searchList(props.getRoleDefinitionModel(), query)).stream()
                .map(row -> toLong(row.get(ModelConstant.ID)))
                .filter(Objects::nonNull)
                .toList();
        return getUsersByRoleIds(roleIds);
    }

    @Override
    public List<ApproverInfo> getUsersByDeptIds(List<Long> deptIds, boolean includeSubDepts) {
        if (deptIds == null || deptIds.isEmpty()) {
            return List.of();
        }
        Set<Long> targets = new LinkedHashSet<>(deptIds);
        if (includeSubDepts) {
            deptIds.forEach(deptId -> targets.addAll(getSubDeptIds(deptId)));
        }
        return toApprovers(employees(Filters.of(props.getDeptIdField(), Operator.IN, List.copyOf(targets))));
    }

    @Override
    public List<ApproverInfo> getUsersByPositionIds(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return List.of();
        }
        return toApprovers(employees(Filters.of(props.getPositionIdField(), Operator.IN, positionIds)));
    }

    @Override
    public Long getEmployeeIdOfUser(Long userId) {
        if (userId == null) {
            return null;
        }
        Map<String, Object> employee = employeeOfAccount(userId);
        return employee == null ? null : toLong(employee.get(ModelConstant.ID));
    }

    @Override
    public ApproverInfo getSupervisorOfEmployee(Long employeeId, int level) {
        if (employeeId == null || level < 1) {
            return null;
        }
        Map<String, Object> employee = employeeById(employeeId);
        for (int i = 0; i < level && employee != null; i++) {
            Long supervisorId = toLong(employee.get(props.getSupervisorIdField()));
            employee = supervisorId == null ? null : employeeById(supervisorId);
        }
        return employee == null ? null : toApprover(employee);
    }

    @Override
    public ApproverInfo getDeptLeader(Long deptId) {
        return nearestDeptMember(deptId, props.getDeptLeaderIdField());
    }

    @Override
    public ApproverInfo getDeptHrbp(Long deptId) {
        return nearestDeptMember(deptId, props.getDeptHrbpIdField());
    }

    @Override
    public Long getDeptIdOfEmployee(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        Map<String, Object> employee = employeeById(employeeId);
        return employee == null ? null : toLong(employee.get(props.getDeptIdField()));
    }

    @Override
    public List<Long> getSubDeptIds(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        List<Long> level = List.of(deptId);
        for (int depth = 0; !level.isEmpty() && depth < props.getMaxDeptDepth(); depth++) {
            FlexQuery query = new FlexQuery(Filters.of(props.getParentDeptIdField(), Operator.IN, level));
            query.setFields(List.of(ModelConstant.ID));
            List<Long> children = read(() -> modelService.searchList(props.getDepartmentModel(), query)).stream()
                    .map(row -> toLong(row.get(ModelConstant.ID)))
                    .filter(id -> id != null && !id.equals(deptId) && !result.contains(id))
                    .toList();
            result.addAll(children);
            level = children;
        }
        return Collections.unmodifiableList(result);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * The approver a department names in {@code field} (an employee id), or its nearest ancestor's
     * when it names none, or only an employee without an account.
     */
    private ApproverInfo nearestDeptMember(Long deptId, String field) {
        Set<Long> visited = new HashSet<>();
        Long current = deptId;
        for (int depth = 0; current != null && depth < props.getMaxDeptDepth() && visited.add(current); depth++) {
            Long id = current;
            Map<String, Object> dept = read(() -> modelService.getById(props.getDepartmentModel(), id,
                    List.of(field, props.getParentDeptIdField()))).orElse(null);
            if (dept == null) {
                return null;
            }
            Long employeeId = toLong(dept.get(field));
            Map<String, Object> employee = employeeId == null ? null : employeeById(employeeId);
            ApproverInfo member = employee == null ? null : toApprover(employee);
            if (member != null) {
                return member;
            }
            current = toLong(dept.get(props.getParentDeptIdField()));
        }
        return null;
    }

    private List<Map<String, Object>> employees(Filters filters) {
        FlexQuery query = new FlexQuery(filters);
        query.setFields(employeeFields());
        return read(() -> modelService.searchList(props.getEmployeeModel(), query));
    }

    private Map<String, Object> employeeOfAccount(Long accountId) {
        List<Map<String, Object>> rows = employees(Filters.of(props.getAccountIdField(), Operator.EQUAL, accountId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> employeeById(Long employeeId) {
        return read(() -> modelService.getById(props.getEmployeeModel(), employeeId, employeeFields())).orElse(null);
    }

    private List<String> employeeFields() {
        return List.of(ModelConstant.ID, props.getAccountIdField(), props.getNameField(), props.getDeptIdField(),
                props.getPositionIdField(), props.getSupervisorIdField());
    }

    private List<ApproverInfo> toApprovers(List<Map<String, Object>> employees) {
        return employees.stream().map(this::toApprover).filter(Objects::nonNull).toList();
    }

    /** The employee as an approver; null without a login account. */
    private ApproverInfo toApprover(Map<String, Object> employee) {
        Long accountId = toLong(employee.get(props.getAccountIdField()));
        if (accountId == null) {
            return null;
        }
        Object name = employee.get(props.getNameField());
        return new ApproverInfo(accountId, name == null ? null : name.toString(),
                toLong(employee.get(props.getDeptIdField())), null);
    }

    private static <T> T read(Supplier<T> query) {
        return ContextUtils.withoutPermissionChecks(query);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
