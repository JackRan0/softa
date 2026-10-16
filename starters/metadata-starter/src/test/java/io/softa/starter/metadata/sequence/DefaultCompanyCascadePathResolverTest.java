package io.softa.starter.metadata.sequence;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The framework's own answer: the company is itself, or what the company-axis field names. */
class DefaultCompanyCascadePathResolverTest {

    private final DefaultCompanyCascadePathResolver resolver = new DefaultCompanyCascadePathResolver();
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = Mockito.mockStatic(ModelManager.class);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private void model(String name) {
        modelManager.when(() -> ModelManager.existModel(name)).thenReturn(true);
    }

    private MetaField companyField(String model, boolean dynamic, String cascade) {
        MetaField f = mock(MetaField.class);
        when(f.isDynamicCascadedField()).thenReturn(dynamic);
        when(f.getCascadedField()).thenReturn(cascade);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(model, ModelConstant.COMPANY_FIELD)).thenReturn(f);
        return f;
    }

    @Test
    @DisplayName("the company model is its own company")
    void companyIsSelf() {
        model(ModelConstant.COMPANY_MODEL);
        assertThat(resolver.resolve(ModelConstant.COMPANY_MODEL)).contains(CompanyCascadePathResolver.SELF_PATH);
    }

    @Test
    @DisplayName("a model with a company column belongs to the company it names")
    void storedCompanyField() {
        model("Department");
        companyField("Department", false, null);
        assertThat(resolver.resolve("Department")).contains("companyId");
    }

    @Test
    @DisplayName("a dynamic company field has no column, so its cascade path is the answer")
    void dynamicCompanyFieldUsesItsCascade() {
        model("DeptStats");
        companyField("DeptStats", true, "deptId.companyId");
        assertThat(resolver.resolve("DeptStats")).contains("deptId.companyId");
    }

    @Test
    @DisplayName("a model with no company field has no answer here")
    void noCompanyField() {
        model("JobGrade");
        assertThat(resolver.resolve("JobGrade")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("an unknown model has no answer")
    void unknownModel() {
        assertThat(resolver.resolve("Nope")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
