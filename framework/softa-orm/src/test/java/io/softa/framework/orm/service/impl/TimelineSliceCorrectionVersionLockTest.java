package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.entity.TimelineSlice;
import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.JdbcServiceImpl;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A slice's neighbour is rewritten by the framework, not by a client: deleting a slice hands its end
 * date to the slice before it. On a versionLock model that write moves the neighbour's version on,
 * so a form still holding the neighbour sees it changed, and it carries no version condition, since
 * nobody read the neighbour to make it.
 */
class TimelineSliceCorrectionVersionLockTest {

    private static final String MODEL = "EmpSalaryProfileItem";

    @Test
    void deletingASliceMovesTheVersionOfTheNeighbourThatInheritsItsEnd() {
        JdbcProxy jdbcProxy = mock(JdbcProxy.class);
        when(jdbcProxy.update(anyString(), any())).thenReturn(1);
        JdbcServiceImpl<Serializable> jdbcService = spy(new JdbcServiceImpl<>());
        ReflectionTestUtils.setField(jdbcService, "jdbcProxy", jdbcProxy);
        // The slice before the deleted one, found by the day before the deleted slice starts.
        doReturn(List.of(54L)).when(jdbcService).getIds(eq(MODEL), eq(ModelConstant.SLICE_ID), any(FlexQuery.class));
        doReturn(true).when(jdbcService).deleteBySliceId(MODEL, 55L);
        TimelineSlice deleted = new TimelineSlice();
        deleted.setId(900L);
        deleted.setSliceId(55L);
        deleted.setEffectiveStartDate(LocalDate.of(2026, 2, 1));
        deleted.setEffectiveEndDate(ModelConstant.MAX_EFFECTIVE_END_DATE);

        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            MetaModel metaModel = new MetaModel();
            ReflectionTestUtils.setField(metaModel, "tableName", "emp_salary_profile_item");
            models.when(() -> ModelManager.getModel(MODEL)).thenReturn(metaModel);
            models.when(() -> ModelManager.getModelPrimaryKey(MODEL)).thenReturn(ModelConstant.SLICE_ID);
            models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);
            stubColumn(models, ModelConstant.SLICE_ID, "slice_id");
            stubColumn(models, ModelConstant.EFFECTIVE_END_DATE, "effective_end_date");
            TimelineServiceImpl<Serializable> service = new TimelineServiceImpl<>();
            ReflectionTestUtils.setField(service, "jdbcService", jdbcService);

            assertThat(service.deleteSlice(MODEL, deleted)).isTrue();
        }

        ArgumentCaptor<SqlParams> sql = ArgumentCaptor.forClass(SqlParams.class);
        verify(jdbcProxy).update(eq(MODEL), sql.capture());
        assertThat(sql.getValue().getSql()).isEqualTo(
                "UPDATE emp_salary_profile_item SET effective_end_date=?,version=version+1 WHERE slice_id = ?");
        assertThat(sql.getValue().getArgs()).containsExactly(ModelConstant.MAX_EFFECTIVE_END_DATE, 54L);
    }

    private static void stubColumn(MockedStatic<ModelManager> models, String fieldName, String columnName) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "columnName", columnName);
        models.when(() -> ModelManager.getModelField(MODEL, fieldName)).thenReturn(metaField);
    }
}
