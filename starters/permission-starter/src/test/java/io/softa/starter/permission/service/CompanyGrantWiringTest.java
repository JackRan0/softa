package io.softa.starter.permission.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The company grant through the PUBLIC entry point, with a crafted header.
 *
 * <p>{@code CompanyGrantTest} calls {@code companyScope} directly. That pins what the method
 * decides, and nothing about whether the public entry point actually routes through it under a
 * crafted header — which is where the exposure would be. A security property no test can see from
 * the outside is a comment.
 *
 * <p>So this class drives {@link PermissionServiceImpl#appendScopeAccessFilters} with a real
 * compiler, a real snapshot and a real {@code Context}, and asserts on the SQL-shaped result. The
 * scenario is the one the platform forces administrators into: {@code RoleController} refuses to
 * save an {@code ALL} rule on a multi-company model unless a company grant is present, so "ALL
 * rows, bounded to these companies" is not an exotic configuration — it is the configuration.
 *
 * <p>{@code X-Company-Id} is caller-supplied and unvalidated by design — it names a view, not a
 * permission — so every test here crafts one outside the grant and asserts the grant survives it.
 */
class CompanyGrantWiringTest {

    private static final String MODEL = "Employee";
    private static final Set<Long> GRANTED = Set.of(8712L, 9001L);
    /** Outside the grant, as a crafted X-Company-Id would be. */
    private static final Long CRAFTED = 9999L;

    private MockedStatic<ModelManager> modelManager;
    private PermissionSnapshotProvider snapshotProvider;
    private ScopeRuleCompiler compiler;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        MetaModel meta = mock(MetaModel.class);
        when(meta.isMultiCompany()).thenReturn(true);
        modelManager.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModel(MODEL)).thenReturn(meta);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(MODEL, ModelConstant.COMPANY_FIELD))
                .thenReturn(new MetaField());

        snapshotProvider = mock(PermissionSnapshotProvider.class);
        compiler = mock(ScopeRuleCompiler.class);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private PermissionServiceImpl service() {
        return new PermissionServiceImpl(snapshotProvider, compiler, null, null, null);
    }

    /** A caller holding one rule on the model, a company grant, and a header naming a company. */
    private void caller(ScopeType ruleType, Filters compiled) {
        ScopeRule rule = new ScopeRule();
        rule.setScopeType(ruleType);
        PermissionInfo pi = new PermissionInfo();
        pi.setGrantedCompanyIds(GRANTED);
        pi.setModelScopeMap(Map.of(MODEL, List.of(rule)));
        when(snapshotProvider.get(any(), anyLong())).thenReturn(pi);
        when(snapshotProvider.get(any(), any())).thenReturn(pi);
        // The compiler's answer IS the row scope: null means "ALL — no restriction".
        when(compiler.compile(any(), anyString())).thenReturn(compiled);
    }

    private Filters underHeader(Long selected) {
        Context ctx = new Context();
        ctx.setUserId(77L);
        ctx.setTenantId(1L);
        ctx.setCompanyId(selected);
        return ContextHolder.callWith(ctx,
                () -> service().appendScopeAccessFilters(MODEL, new Filters()));
    }

    @Test
    void anAllRuleUnderACraftedHeaderIsStillBoundedByTheGrant() {
        // The configuration RoleController forces: an ALL rule plus a company grant. The ALL rule
        // compiles to no filter at all, so the grant is the ONLY thing standing between a crafted
        // X-Company-Id and every row of an arbitrary company.
        caller(ScopeType.ALL, null);

        Filters result = underHeader(CRAFTED);

        assertThat(Filters.containsField(result, ModelConstant.COMPANY_FIELD)).isTrue();
        assertThat(result.toString()).contains("8712", "9001");
        assertThat(result.toString()).doesNotContain(String.valueOf(CRAFTED));
    }

    @Test
    void aRestrictingRuleIsBoundedToo() {
        // A restricting row scope does not buy its way past the grant either. An earlier revision let
        // it: "my companies" was to be "grant ∪ reach", so a manager with a cross-company report
        // could see the rows their own rule hands over. The requirement settled "my companies" as
        // the role's step-2 company selection alone, which leaves the grant unconditional
        // — and a header naming a company outside it must therefore yield nothing, not a subset.
        caller(ScopeType.SELF, Filters.of("id", io.softa.framework.base.enums.Operator.EQUAL, 42L));

        Filters result = underHeader(CRAFTED);

        assertThat(result.toString()).contains("8712", "9001");
        assertThat(result.toString()).doesNotContain(String.valueOf(CRAFTED));
    }

    @Test
    void namingAnIdInTheRequestDoesNotWaiveTheGrant() {
        // A second door, and it opens without a header at all: the grant used to exempt any filter
        // naming `id`. A caller holding an ALL rule could POST searchList with
        // filters [["id", ">", 0]] and read an ungranted company whole — no header, no crafted
        // selection needed. The exemption's stated purpose (display expansion) never reached this
        // method at all: that path is @SkipPermissionCheck.
        caller(ScopeType.ALL, null);

        Context ctx = new Context();
        ctx.setUserId(77L);
        ctx.setTenantId(1L);
        Filters withId = Filters.of(ModelConstant.ID, io.softa.framework.base.enums.Operator.GREATER_THAN, 0L);
        Filters result = ContextHolder.callWith(ctx,
                () -> service().appendScopeAccessFilters(MODEL, withId));

        assertThat(result.toString()).contains("8712", "9001");
    }

    @Test
    void withNoHeaderTheGrantBoundsEitherWay() {
        caller(ScopeType.ALL, null);

        Filters result = underHeader(null);

        assertThat(result.toString()).contains("8712", "9001");
    }
}
