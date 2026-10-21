package io.softa.starter.metadata.sequence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaIndex;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.entity.SysSequence;
import io.softa.starter.metadata.service.SysSequenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The checks that need the database: unique ignoring case, and the record's company for the format. */
class ManualCodeCheckerTest {

    private static final String MODEL = "Employee";
    private static final String FIELD = "code";

    private final SysSequenceService sequences = mock(SysSequenceService.class);
    private final CompanyAbbreviationResolver abbreviations = mock(CompanyAbbreviationResolver.class);
    private final SequenceCodeOccupancy occupancy = mock(SequenceCodeOccupancy.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final ManualCodeChecker checker = new ManualCodeChecker(sequences, abbreviations, occupancy, modelService);
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        models = Mockito.mockStatic(ModelManager.class);
        MetaField code = mock(MetaField.class);
        when(code.getLabel()).thenReturn("Employee Code");
        models.when(() -> ModelManager.getModelFieldOrNull(MODEL, FIELD)).thenReturn(code);
        MetaIndex unique = mock(MetaIndex.class);
        when(unique.getIndexFields()).thenReturn(List.of("tenantId", "code"));
        when(unique.getUniqueIndex()).thenReturn(true);
        when(unique.getMessage()).thenReturn("An employee with this code already exists.");
        models.when(() -> ModelManager.getModelIndexes(MODEL)).thenReturn(List.of(unique));
        when(occupancy.taken(eq("Employee.code"), anyCollection())).thenReturn(Set.of("zk1000021"));
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private void rule(ManualEntryRule rule) {
        SysSequence config = new SysSequence();
        config.setTemplate("{companyAbbr}{seq:7}");
        config.setManualEntry(rule);
        when(sequences.loadConfigByCode("Employee.code")).thenReturn(config);
    }

    private List<String> check(List<String> codes, List<String> own) {
        List<Map<String, Object>> rows = new ArrayList<>();
        codes.forEach(c -> rows.add(new HashMap<>()));
        return checker.check(MODEL, FIELD, codes, rows, own);
    }

    @Test
    void aCodeTakenInAnotherCaseIsADuplicate_inTheModelsOwnWords() {
        rule(null);
        assertThat(check(List.of("zk1000021"), null)).containsExactly("An employee with this code already exists.");
    }

    @Test
    void retypingOnesOwnCodeInAnotherCaseIsNotADuplicate() {
        rule(null);
        assertThat(check(List.of("zk1000021"), List.of("ZK1000021"))).containsExactly((String) null);
    }

    @Test
    void theSecondOfTwoEqualCodesInOneBatchIsTheDuplicate() {
        rule(null);
        assertThat(check(List.of("ZK2", "zk2"), Arrays.asList(null, null)))
                .containsExactly(null, "An employee with this code already exists.");
    }

    @Test
    void spacesComeFirst_andSkipTheLookup() {
        rule(null);
        assertThat(check(List.of("ZK 1"), null)).containsExactly("Employee Code cannot contain spaces.");
    }

    @Test
    void sameAsAutoGenerationReadsTheRecordsCompany() {
        rule(new ManualEntryRule(ManualEntryFormat.SAME_AS_AUTO_GENERATION, null, null, null, null, null));
        when(abbreviations.abbreviationsOf(eq(MODEL), anyList())).thenReturn(List.of("HF"));
        assertThat(check(List.of("HF123456"), null)).containsExactly("Employee Code must be HF + 7 digits.");
    }

    @Test
    void anyLooksUpNoCompany() {
        rule(new ManualEntryRule(ManualEntryFormat.ANY, null, null, null, null, null));
        assertThat(check(List.of("ZK1234567-SG01"), null)).containsExactly((String) null);
        verify(abbreviations, never()).abbreviationsOf(eq(MODEL), anyList());
    }

    @Test
    void aCodeUniqueOnlyWithinAScopeIsLeftToItsIndex() {
        MetaIndex scoped = mock(MetaIndex.class);
        when(scoped.getIndexFields()).thenReturn(List.of("payGroupId", "code"));
        when(scoped.getUniqueIndex()).thenReturn(true);
        models.when(() -> ModelManager.getModelIndexes(MODEL)).thenReturn(List.of(scoped));
        rule(null);
        assertThat(check(List.of("zk1000021", "ZK1000021"), null)).containsExactly(null, null);
        verify(occupancy, never()).taken(eq("Employee.code"), anyCollection());
    }
}
