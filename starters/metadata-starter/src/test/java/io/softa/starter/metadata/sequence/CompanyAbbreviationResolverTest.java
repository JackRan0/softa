package io.softa.starter.metadata.sequence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Following a model's path to its company: the first hop from the record, one batched lookup per
 * further hop, and an empty answer — never an error — wherever there is nothing to render.
 */
class CompanyAbbreviationResolverTest {

    private static final String COMPANY = ModelConstant.COMPANY_MODEL;

    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private MockedStatic<ModelManager> modelManager;

    /** Stand-in for an application resolver: knows two models, defers on everything else. */
    private final CompanyCascadePathResolver application = model -> switch (model) {
        case "Employee" -> Optional.of("legalEntityId");
        case "EmpAddress" -> Optional.of("employeeId.legalEntityId");
        default -> Optional.empty();
    };
    private final CompanyCascadePathResolver framework = model -> switch (model) {
        case COMPANY -> Optional.of(CompanyCascadePathResolver.SELF_PATH);
        case "Department", "Employee" -> Optional.of("companyId");
        default -> Optional.empty();
    };

    private CompanyAbbreviationResolver resolver;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(COMPANY)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(COMPANY, "abbreviation")).thenReturn(mock(MetaField.class));
        relation("EmpAddress", "employeeId", "Employee");
        modelManager.when(() -> ModelManager.getModelFieldOrNull("Employee", "legalEntityId")).thenReturn(mock(MetaField.class));
        resolver = new CompanyAbbreviationResolver(List.of(application, framework), modelService);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private void relation(String model, String field, String related) {
        MetaField f = mock(MetaField.class);
        when(f.getRelatedModel()).thenReturn(related);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(model, field)).thenReturn(f);
    }

    private void rows(String model, List<Map<String, Object>> result) {
        when(modelService.searchList(eq(model), any(FlexQuery.class))).thenReturn(result);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private List<String> resolve(String model, List<Map<String, Object>> records) {
        return ContextHolder.callWith(new Context(), () -> resolver.abbreviationsOf(model, records));
    }

    @Test
    @DisplayName("the company being numbered carries its own abbreviation; nothing is queried")
    void selfPath() {
        List<String> out = resolve(COMPANY, List.of(row("abbreviation", "ZK"), row("abbreviation", " ")));
        assertThat(out).containsExactly("ZK", null);
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    @Test
    @DisplayName("one hop: the company id is on the record; one query for the whole batch")
    void oneHop() {
        rows(COMPANY, List.of(row("id", 9001L, "abbreviation", "ZK"), row("id", 9002L, "abbreviation", "HF")));
        List<String> out = resolve("Department", List.of(row("companyId", 9001L), row("companyId", 9002L), row("companyId", 9001L)));
        assertThat(out).containsExactly("ZK", "HF", "ZK");
        verify(modelService, times(1)).searchList(eq(COMPANY), any(FlexQuery.class));
    }

    @Test
    @DisplayName("two hops through the employee: one query per hop, not per record")
    void twoHops() {
        rows("Employee", List.of(row("id", 101L, "legalEntityId", 9001L), row("id", 102L, "legalEntityId", 9002L),
                row("id", 103L, "legalEntityId", 9003L)));
        rows(COMPANY, List.of(row("id", 9001L, "abbreviation", "ZK"), row("id", 9002L, "abbreviation", "HF"),
                row("id", 9003L, "abbreviation", null)));
        List<String> out = resolve("EmpAddress",
                List.of(row("employeeId", 101L), row("employeeId", 102L), row("employeeId", 103L), row("employeeId", 101L)));

        assertThat(out).containsExactly("ZK", "HF", null, "ZK");
        verify(modelService, times(1)).searchList(eq("Employee"), any(FlexQuery.class));
        verify(modelService, times(1)).searchList(eq(COMPANY), any(FlexQuery.class));
    }

    @Test
    @DisplayName("an application's answer wins over the framework's for the same model")
    void applicationAnswerWins() {
        // Employee has both a company-axis field and a signing entity; the application says the latter.
        rows(COMPANY, List.of(row("id", 9002L, "abbreviation", "HF")));
        List<String> out = resolve("Employee", List.of(row("companyId", 9001L, "legalEntityId", 9002L)));
        assertThat(out).containsExactly("HF");
    }

    @Test
    @DisplayName("ids match across types: a String id on the record finds a Long id in the table")
    void idTypesNormalised() {
        rows(COMPANY, List.of(row("id", 9001L, "abbreviation", "ZK")));
        assertThat(resolve("Department", List.of(row("companyId", "9001")))).containsExactly("ZK");
    }

    @Test
    @DisplayName("a missing link along the way renders no prefix for that record only")
    void missingLink() {
        rows(COMPANY, List.of(row("id", 9001L, "abbreviation", "ZK")));
        List<String> out = resolve("Department", List.of(row("companyId", null), row("companyId", 9001L), row("companyId", 7777L)));
        assertThat(out).containsExactly(null, "ZK", null);
    }

    @Test
    @DisplayName("a model with no path to a company gets no prefix and costs no query")
    void noPath() {
        assertThat(resolve("JobGrade", List.of(row("x", 1)))).containsExactly((String) null);
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    @Test
    @DisplayName("no company model, or one without an abbreviation field: nothing to render")
    void noCompanyAbbreviation() {
        modelManager.when(() -> ModelManager.getModelFieldOrNull(COMPANY, "abbreviation")).thenReturn(null);
        assertThat(resolve("Department", List.of(row("companyId", 9001L)))).containsExactly((String) null);
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    @Test
    @DisplayName("outside any context: no lookup that could cross tenants")
    void noContext() {
        List<String> out = resolver.abbreviationsOf("Department", new ArrayList<>(List.of(row("companyId", 9001L))));
        assertThat(out).containsExactly((String) null);
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }

    @Test
    @DisplayName("a path naming a field that does not exist costs the prefix, not the insert")
    void brokenPath() {
        CompanyAbbreviationResolver wrong = new CompanyAbbreviationResolver(
                List.of(model -> Optional.of("employeeId.signingEntityId")), modelService);
        List<String> out = ContextHolder.callWith(new Context(),
                () -> wrong.abbreviationsOf("EmpAddress", List.of(row("employeeId", 101L))));
        assertThat(out).containsExactly((String) null);
        verify(modelService, never()).searchList(anyString(), any(FlexQuery.class));
    }
}
