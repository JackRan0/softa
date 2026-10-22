package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adding a version on a date where a slice already starts corrects that slice in place. The form
 * sends the version of the slice it had open, which need not be the one corrected, so the version
 * must not be checked against it: that would report a conflict that never happened.
 */
class TimelineAddVersionLockTest {

    private static final String MODEL = "EmpSalaryProfileItem";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    @SuppressWarnings("unchecked")
    void theSliceCorrectedInPlaceIsNotCheckedAgainstTheVersionOfAnother() {
        JdbcService<Serializable> jdbcService = mock(JdbcService.class);
        when(jdbcService.exist(anyString(), any())).thenReturn(true);
        // The slice that already starts on that date.
        when(jdbcService.selectByFilter(anyString(), any(FlexQuery.class))).thenAnswer(invocation ->
                List.of(new LinkedHashMap<>(Map.of(ModelConstant.ID, 900L, ModelConstant.SLICE_ID, 55L,
                        ModelConstant.EFFECTIVE_START_DATE, START,
                        ModelConstant.EFFECTIVE_END_DATE, ModelConstant.MAX_EFFECTIVE_END_DATE))));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(ModelConstant.ID, 900L);
        row.put(ModelConstant.EFFECTIVE_START_DATE, START);
        row.put("amount", 10);
        // The version of the slice the user had open (sliceId 54, say).
        row.put(ModelConstant.VERSION, 2);

        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModel(MODEL)).thenReturn(mock(MetaModel.class));
            models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);
            models.when(() -> ModelManager.getModelUpdatableFields(MODEL)).thenAnswer(invocation ->
                    new HashSet<>(Set.of("amount", ModelConstant.EFFECTIVE_START_DATE)));
            TimelineServiceImpl<Serializable> service = new TimelineServiceImpl<>();
            ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
            // A new version of an existing entity is checked against the caller's update range.
            ReflectionTestUtils.setField(service, "permissionService", mock(PermissionService.class));
            Context context = new Context();
            context.setEffectiveDate(START);
            ContextHolder.runWith(context, () -> service.createSlices(MODEL, new ArrayList<>(List.of(row))));
        }

        ArgumentCaptor<List<Map<String, Object>>> written = ArgumentCaptor.forClass(List.class);
        verify(jdbcService).updateList(eq(MODEL), written.capture(), any());
        assertThat(written.getValue().getFirst())
                .containsEntry(ModelConstant.SLICE_ID, 55L)
                .containsEntry("amount", 10)
                .doesNotContainKey(ModelConstant.VERSION);
    }
}
