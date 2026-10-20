package io.softa.framework.orm.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.domain.CreateOrUpdateResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * An import names an existing record by its generated code; a code typed in another case still names
 * it — codes are unique ignoring case — and the record keeps its stored spelling.
 */
class CreateOrUpdateCodeCaseTest {

    private static final String MODEL = "Employee";
    private static final List<String> KEY = List.of("code");

    private final ModelServiceImpl<Long> service = spy(new ModelServiceImpl<>());
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        models = Mockito.mockStatic(ModelManager.class);
        MetaField code = new MetaField();
        ReflectionTestUtils.setField(code, "fieldName", "code");
        ReflectionTestUtils.setField(code, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(code, "autoSequence", true);
        models.when(() -> ModelManager.getModelField(MODEL, "code")).thenReturn(code);
        models.when(() -> ModelManager.getModelFieldOrNull(eq(MODEL), anyString())).thenAnswer(inv ->
                "code".equals(inv.getArgument(1)) ? code : null);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private static Map<String, Object> row(String code) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        return row;
    }

    /** First read: the exact match; second: the case-insensitive candidates. */
    private void stored(List<Map<String, Object>> exact, List<Map<String, Object>> ignoringCase) {
        doReturn(exact, ignoringCase).when(service).searchList(eq(MODEL), any(FlexQuery.class));
    }

    @Test
    void aCodeInAnotherCaseUpdatesTheRecordItNames_keepingItsSpelling() {
        stored(List.of(), List.of(Map.of("id", 7L, "code", "ZK1000021")));
        Map<String, Object> typed = row("zk1000021");

        CreateOrUpdateResult split = service.splitByExistence(MODEL, new ArrayList<>(List.of(typed)), KEY);

        assertThat(split.updated()).containsExactly(typed);
        assertThat(split.created()).isEmpty();
        assertThat(typed).containsEntry("id", 7L).containsEntry("code", "ZK1000021");
    }

    @Test
    void aCodeMatchingNothingStaysACreate() {
        stored(List.of(), List.of(Map.of("id", 7L, "code", "ZK10000210")));
        Map<String, Object> typed = row("zk1000021");

        CreateOrUpdateResult split = service.splitByExistence(MODEL, new ArrayList<>(List.of(typed)), KEY);

        assertThat(split.created()).containsExactly(typed);
        assertThat(typed).doesNotContainKey("id");
    }

    @Test
    void aCodeMatchingTwoRecordsThatDifferOnlyInCaseMatchesNeither() {
        stored(List.of(), List.of(Map.of("id", 7L, "code", "ZK1000021"), Map.of("id", 8L, "code", "zK1000021")));
        Map<String, Object> typed = row("zk1000021");

        CreateOrUpdateResult split = service.splitByExistence(MODEL, new ArrayList<>(List.of(typed)), KEY);

        assertThat(split.created()).containsExactly(typed);
    }
}
