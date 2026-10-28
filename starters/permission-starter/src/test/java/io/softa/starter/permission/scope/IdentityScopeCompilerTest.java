package io.softa.starter.permission.scope;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the data-driven identity scope compiler that replaced the four
 * SELF / DIRECT_REPORTS / CREATED_BY_SELF contributors. Feeds a
 * stubbed {@link DataScopeTypeReader} mirroring the Builtin seed (filter templates)
 * and asserts the compiled anchor + fail-closed behaviour matches what those
 * contributors did. Env placeholders (USER_EMP_ID / USER_ID / USER_COMP_ID) are left
 * in the compiled Filters for FilterUnitParser to resolve at SQL time.
 */
class IdentityScopeCompilerTest {

    private IdentityScopeCompiler compiler;

    @BeforeEach
    void setUp() {
        DataScopeTypeReader reader = mock(DataScopeTypeReader.class);
        when(reader.read()).thenReturn(seedRows());
        compiler = new IdentityScopeCompiler(reader);
        compiler.build();
    }

    /** Mirrors DataScopeType.Builtin.json (identity rows carry a filter template). */
    private static List<Map<String, Object>> seedRows() {
        return List.of(
                Map.<String, Object>of("id", "ALL", "appliesToAll", true),
                Map.<String, Object>of("id", "SELF",
                        "filter", List.of("employeeId", "=", "USER_EMP_ID"),
                        "identityModel", "Employee",
                        "identityFilter", List.of("id", "=", "USER_EMP_ID")),
                Map.<String, Object>of("id", "DIRECT_REPORTS",
                        "filter", List.of("managerId", "=", "USER_EMP_ID"),
                        "identityModel", "Employee",
                        "identityFilter", List.of("piEmployeeId", "=", "USER_EMP_ID")),
                Map.<String, Object>of("id", "CREATED_BY_SELF",
                        "filter", List.of("createdId", "=", "USER_ID")),
                Map.<String, Object>of("id", "DEPT_SUBTREE", "applicableFields", List.of("departmentId")),
                Map.<String, Object>of("id", "CUSTOM", "appliesToAll", true));
    }

    // ─── handles() ───

    @Test
    void handles_onlyIdentityTypes() {
        assertThat(compiler.handles(ScopeType.SELF)).isTrue();
        assertThat(compiler.handles(ScopeType.DIRECT_REPORTS)).isTrue();
        assertThat(compiler.handles(ScopeType.CREATED_BY_SELF)).isTrue();
        assertThat(compiler.handles(ScopeType.DEPT_SUBTREE)).isFalse();
        assertThat(compiler.handles(ScopeType.CUSTOM)).isFalse();
        assertThat(compiler.handles(ScopeType.ALL)).isFalse();
    }

    @Test
    void compile_nonIdentityType_returnsNull() {
        // DEPT_SUBTREE is not data-driven — compiler returns null so the
        // ScopeRuleCompiler falls through to its code contributor.
        assertThat(compiler.compile(ScopeType.DEPT_SUBTREE, rule(ScopeType.DEPT_SUBTREE), "LeaveRequest"))
                .isNull();
    }

    // ─── SELF: model-swap (id on Employee, employeeId elsewhere) ───

    @Test
    void self_onEmployeeModel_filtersId() {
        Filters out = ContextHolder.callWith(ctxEmp(empId(7L)),
                () -> compiler.compile(ScopeType.SELF, rule(ScopeType.SELF), "Employee"));
        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(out.getFilterUnit().getField()).isEqualTo("id");
        assertThat(out.getFilterUnit().getValue()).isEqualTo("USER_EMP_ID");
    }

    @Test
    void self_onOtherModel_filtersEmployeeId() {
        Filters out = ContextHolder.callWith(ctxEmp(empId(7L)),
                () -> compiler.compile(ScopeType.SELF, rule(ScopeType.SELF), "LeaveRequest"));
        assertThat(out.getFilterUnit().getField()).isEqualTo("employeeId");
    }

    @Test
    void self_noEmpInfo_failsClosed() {
        Context ctx = new Context();
        ctx.setUserId(1L);   // no EmpInfo bound
        Filters out = ContextHolder.callWith(ctx,
                () -> compiler.compile(ScopeType.SELF, rule(ScopeType.SELF), "Employee"));
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    // ─── DIRECT_REPORTS: managerId elsewhere, piEmployeeId on Employee ───

    @Test
    void directReports_onOtherModel_filtersManagerId() {
        Filters out = ContextHolder.callWith(ctxEmp(empId(7L)),
                () -> compiler.compile(ScopeType.DIRECT_REPORTS, rule(ScopeType.DIRECT_REPORTS), "LeaveRequest"));
        assertThat(out.getFilterUnit().getField()).isEqualTo("managerId");
    }

    @Test
    void directReports_onEmployeeModel_filtersPiEmployeeId() {
        Filters out = ContextHolder.callWith(ctxEmp(empId(7L)),
                () -> compiler.compile(ScopeType.DIRECT_REPORTS, rule(ScopeType.DIRECT_REPORTS), "Employee"));
        assertThat(out.getFilterUnit().getField()).isEqualTo("piEmployeeId");
    }

    // ─── CREATED_BY_SELF: createdId = USER_ID (works for pure users) ───

    @Test
    void createdBySelf_usesUserId() {
        Context ctx = new Context();
        ctx.setUserId(42L);
        Filters out = ContextHolder.callWith(ctx,
                () -> compiler.compile(ScopeType.CREATED_BY_SELF, rule(ScopeType.CREATED_BY_SELF), "AnyModel"));
        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(out.getFilterUnit().getField()).isEqualTo("createdId");
        assertThat(out.getFilterUnit().getValue()).isEqualTo("USER_ID");
    }

    @Test
    void createdBySelf_nullUserId_failsClosed() {
        // No userId bound → USER_ID token unresolvable → fail closed (no rows).
        Filters out = ContextHolder.callWith(new Context(),
                () -> compiler.compile(ScopeType.CREATED_BY_SELF, rule(ScopeType.CREATED_BY_SELF), "AnyModel"));
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    // ─── helpers ───

    private static ScopeRule rule(ScopeType t) {
        return new ScopeRule(t, null);
    }

    private static EmpInfo empId(Long id) {
        EmpInfo e = new EmpInfo();
        e.setEmpId(id);
        return e;
    }

    private static Context ctxEmp(EmpInfo info) {
        Context ctx = new Context();
        ctx.setUserId(1L);
        ctx.setEmpInfo(info);
        return ctx;
    }
}
