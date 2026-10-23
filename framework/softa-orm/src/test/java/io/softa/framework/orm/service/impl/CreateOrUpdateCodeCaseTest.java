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

import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A generated code names a record only as it is spelled: codes that differ only in case are different
 * codes. An import row typed {@code zk1000021} beside a stored {@code ZK1000021} is a new record, never
 * an update of that one — matched ignoring case, a second row of a file overwrote the record the first
 * row had just created, and the import reported both as done.
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

    @Test
    void aCodeInAnotherCaseIsANewRecord() {
        // The exact match finds nothing: the stored code is spelled ZK1000021.
        doReturn(List.of()).when(service).searchList(eq(MODEL), any(FlexQuery.class));
        Map<String, Object> typed = row("zk1000021");

        CreateOrUpdateResult split = service.splitByExistence(MODEL, new ArrayList<>(List.of(typed)), KEY);

        assertThat(split.created()).containsExactly(typed);
        assertThat(split.updated()).isEmpty();
        assertThat(typed).doesNotContainKey("id").containsEntry("code", "zk1000021");
        verify(service, times(1)).searchList(eq(MODEL), any(FlexQuery.class));
    }

    @Test
    void twoRowsDifferingOnlyInCaseAreTwoRecords() {
        doReturn(List.of()).when(service).searchList(eq(MODEL), any(FlexQuery.class));
        Map<String, Object> upper = row("AI1022IMP12");
        Map<String, Object> lower = row("ai1022imp12");

        CreateOrUpdateResult split = service.splitByExistence(MODEL, new ArrayList<>(List.of(upper, lower)), KEY);

        assertThat(split.created()).containsExactly(upper, lower);
    }

    @Test
    void theExactSpellingStillUpdates() {
        doReturn(List.of(Map.of("id", 7L, "code", "ZK1000021"))).when(service).searchList(eq(MODEL), any(FlexQuery.class));
        Map<String, Object> typed = row("ZK1000021");

        CreateOrUpdateResult split = service.splitByExistence(MODEL, new ArrayList<>(List.of(typed)), KEY);

        assertThat(split.updated()).containsExactly(typed);
        assertThat(typed).containsEntry("id", 7L);
    }
}
