package io.softa.framework.orm.jdbc.database;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The UPDATE statement must carry COLUMN names on both sides. The WHERE pk of a timeline
 * model is the one case where the pk FIELD name ({@code sliceId}) differs from its column
 * ({@code slice_id}) — regular models (pk {@code id}) masked the asymmetry.
 */
class StaticSqlBuilderTest {

    @Test
    void updateSqlWherePkUsesTheColumnNameForTimelineModels() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "DeptInfo", "dept_info", ModelConstant.SLICE_ID);
            stubField(mm, "DeptInfo", ModelConstant.SLICE_ID, "slice_id");
            stubField(mm, "DeptInfo", "name", "name");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ModelConstant.SLICE_ID, 11L);
            row.put("name", "R&D");

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("DeptInfo", row);

            assertEquals("UPDATE dept_info SET name=? WHERE slice_id = ?", sqlParams.getSql());
            assertEquals("R&D", sqlParams.getArgs().get(0));
            assertEquals(11L, sqlParams.getArgs().get(1));
        }
    }

    @Test
    void updateSqlWherePkStaysIdForRegularModels() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "EmpInfo", "emp_info", ModelConstant.ID);
            stubField(mm, "EmpInfo", ModelConstant.ID, "id");
            stubField(mm, "EmpInfo", "name", "name");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ModelConstant.ID, 6L);
            row.put("name", "Tom");

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("EmpInfo", row);

            assertEquals("UPDATE emp_info SET name=? WHERE id = ?", sqlParams.getSql());
        }
    }

    @Test
    void orderedUpdateSqlSetsTheFieldsInTheGivenOrder() {
        // Rows that set the same fields share this statement in a batch, whatever their map order.
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "EmpInfo", "emp_info", ModelConstant.ID);
            stubField(mm, "EmpInfo", ModelConstant.ID, "id");
            stubField(mm, "EmpInfo", "name", "name");
            stubField(mm, "EmpInfo", "deptId", "dept_id");
            java.util.List<String> fields = java.util.List.of("deptId", "name");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "Tom");
            row.put(ModelConstant.ID, 6L);
            row.put("deptId", 3L);

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("EmpInfo", fields);

            assertEquals("UPDATE emp_info SET dept_id=?,name=? WHERE id = ?", sqlParams.getSql());
            assertArrayEquals(new Object[]{3L, "Tom", 6L}, StaticSqlBuilder.getUpdateArgs("EmpInfo", fields, row));
        }
    }

    // ------------------------------------------------------- the optimistic lock
    // A versionLock row's `version` is the version it was read at, never a value to set: the SQL
    // moves the version on, and a row carrying one is written only while it is still that version.

    @Test
    void aVersionLockedRowCarryingItsVersionIsWrittenOnlyAtThatVersion() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubVersionLockedEmpInfo(mm);
            java.util.List<String> fields = java.util.List.of("name", ModelConstant.VERSION);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ModelConstant.ID, 6L);
            row.put("name", "Tom");
            row.put(ModelConstant.VERSION, 5L);

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("EmpInfo", fields);

            assertEquals("UPDATE emp_info SET name=?,version=version+1 WHERE id = ? AND version = ?", sqlParams.getSql());
            assertArrayEquals(new Object[]{"Tom", 6L, 5L}, StaticSqlBuilder.getUpdateArgs("EmpInfo", fields, row));
            // The single-row form, which updateOne sends, is the same statement.
            SqlParams single = StaticSqlBuilder.getUpdateSql("EmpInfo", row);
            assertEquals("UPDATE emp_info SET name=?,version=version+1 WHERE id = ? AND version = ?", single.getSql());
            assertEquals(java.util.List.of("Tom", 6L, 5L), single.getArgs());
        }
    }

    @Test
    void aVersionLockedRowWithoutAVersionStillMovesItOn() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubVersionLockedEmpInfo(mm);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ModelConstant.ID, 6L);
            row.put("name", "Tom");

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("EmpInfo", row);

            assertEquals("UPDATE emp_info SET name=?,version=version+1 WHERE id = ?", sqlParams.getSql());
            assertEquals(java.util.List.of("Tom", 6L), sqlParams.getArgs());
        }
    }

    @Test
    void withoutTheVersionLockAVersionFieldIsAPlainColumn() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "EmpInfo", "emp_info", ModelConstant.ID);
            stubField(mm, "EmpInfo", ModelConstant.ID, "id");
            stubField(mm, "EmpInfo", "name", "name");
            stubField(mm, "EmpInfo", ModelConstant.VERSION, "version");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ModelConstant.ID, 6L);
            row.put("name", "Tom");
            row.put(ModelConstant.VERSION, 5L);

            SqlParams sqlParams = StaticSqlBuilder.getUpdateSql("EmpInfo", row);

            assertEquals("UPDATE emp_info SET name=?,version=? WHERE id = ?", sqlParams.getSql());
            assertEquals(java.util.List.of("Tom", 5L, 6L), sqlParams.getArgs());
        }
    }

    private void stubVersionLockedEmpInfo(MockedStatic<ModelManager> mm) {
        stubModel(mm, "EmpInfo", "emp_info", ModelConstant.ID);
        stubField(mm, "EmpInfo", ModelConstant.ID, "id");
        stubField(mm, "EmpInfo", "name", "name");
        stubField(mm, "EmpInfo", ModelConstant.VERSION, "version");
        mm.when(() -> ModelManager.isVersionControl("EmpInfo")).thenReturn(true);
    }

    @Test
    void selectSqlKeyedOnSliceIdRendersTheColumnName() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "DeptInfo", "dept_info", ModelConstant.SLICE_ID);
            stubField(mm, "DeptInfo", ModelConstant.SLICE_ID, "slice_id");
            stubField(mm, "DeptInfo", "name", "name");

            SqlParams sqlParams = StaticSqlBuilder.getSelectSql("DeptInfo",
                    java.util.List.of("name"), java.util.List.of(11L, 12L), ModelConstant.SLICE_ID);

            assertEquals("SELECT name FROM dept_info WHERE slice_id IN (?,?)", sqlParams.getSql());
            assertEquals(java.util.List.of(11L, 12L), sqlParams.getArgs());
        }
    }

    @Test
    void selectSqlDefaultsToTheIdColumn() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModel(mm, "EmpInfo", "emp_info", ModelConstant.ID);
            stubField(mm, "EmpInfo", ModelConstant.ID, "id");
            stubField(mm, "EmpInfo", "name", "name");

            SqlParams sqlParams = StaticSqlBuilder.getSelectSql("EmpInfo",
                    java.util.List.of("name"), java.util.List.of(6L));

            assertEquals("SELECT name FROM emp_info WHERE id = ?", sqlParams.getSql());
        }
    }

    private void stubModel(MockedStatic<ModelManager> mm, String modelName, String tableName, String pk) {
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "modelName", modelName);
        ReflectionTestUtils.setField(metaModel, "tableName", tableName);
        mm.when(() -> ModelManager.getModel(modelName)).thenReturn(metaModel);
        mm.when(() -> ModelManager.getModelPrimaryKey(modelName)).thenReturn(pk);
    }

    private void stubField(MockedStatic<ModelManager> mm, String modelName, String fieldName, String columnName) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", modelName);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "columnName", columnName);
        mm.when(() -> ModelManager.getModelField(modelName, fieldName)).thenReturn(metaField);
    }
}
