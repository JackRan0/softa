package io.softa.framework.orm.meta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.utils.BeanTool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @Model(enableAuditLog = ...)} as {@link ModelManager} reads it from {@code sys_model}.
 *
 * <p>Only an explicit false turns a model's audit log off. The meta row mapper reads a NULL column
 * into a primitive as false, so a NULL row — or a primitive {@code MetaModel} field — would switch
 * logging off for a model nobody asked to change. The rows here go through that same mapper, as
 * the boot read does: MySQL hands the column back as a TINYINT, PostgreSQL as a boolean, and an
 * environment that has not run the migration yet has no column at all.
 */
class ModelManagerAuditLogTest {

    private static Object previousSnapshot;

    @BeforeAll
    static void initSnapshot() throws Exception {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        previousSnapshot = snapshotField().get(null);

        List<Map<String, Object>> rows = List.of(
                row("LoggedByDefault", true),
                row("OffAsTinyint", 0),
                row("OffAsBoolean", false),
                row("NullColumn", null),
                rowWithoutTheColumn("BeforeTheMigration"));
        List<MetaModel> models = new ArrayList<>(BeanTool.originalMapListToObjects(rows, MetaModel.class, true));
        List<MetaField> fields = new ArrayList<>();
        models.forEach(model -> fields.add(idField(model.getModelName())));

        JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
        Mockito.when(jdbcService.selectMetaEntityList("SysModel", MetaModel.class, null)).thenReturn(models);
        Mockito.when(jdbcService.selectMetaEntityList("SysField", MetaField.class, null)).thenReturn(fields);
        ModelManager modelManager = new ModelManager();
        Field jdbc = ModelManager.class.getDeclaredField("jdbcService");
        jdbc.setAccessible(true);
        jdbc.set(modelManager, jdbcService);
        modelManager.init();
    }

    @AfterAll
    static void restoreSnapshot() throws Exception {
        snapshotField().set(null, previousSnapshot);
    }

    @Test
    void onlyAnExplicitFalseTurnsAModelsAuditLogOff() {
        assertTrue(ModelManager.isAuditLogEnabled("LoggedByDefault"));
        assertFalse(ModelManager.isAuditLogEnabled("OffAsTinyint"));
        assertFalse(ModelManager.isAuditLogEnabled("OffAsBoolean"));
    }

    @Test
    void aNullColumnLeavesTheAuditLogOn() {
        assertNull(ModelManager.getModel("NullColumn").getEnableAuditLog(), "the mapper keeps the NULL");
        assertTrue(ModelManager.isAuditLogEnabled("NullColumn"));
    }

    @Test
    void aSysModelWithoutTheColumnYetLeavesTheAuditLogOn() {
        assertTrue(ModelManager.isAuditLogEnabled("BeforeTheMigration"));
    }

    @Test
    void aProgrammaticallyBuiltModelLogsByDefault() {
        assertEquals(Boolean.TRUE, new MetaModel().getEnableAuditLog());
    }

    private static Map<String, Object> row(String modelName, Object enableAuditLog) {
        Map<String, Object> row = rowWithoutTheColumn(modelName);
        row.put("enableAuditLog", enableAuditLog);
        return row;
    }

    private static Map<String, Object> rowWithoutTheColumn(String modelName) {
        Map<String, Object> row = new HashMap<>();
        row.put("modelName", modelName);
        row.put("label", modelName);
        row.put("tableName", modelName.toLowerCase());
        return row;
    }

    private static MetaField idField(String modelName) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName("id");
        metaField.setColumnName("id");
        metaField.setLabel("id");
        metaField.setFieldType(FieldType.LONG);
        return metaField;
    }

    private static Field snapshotField() throws Exception {
        Field field = ModelManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        return field;
    }
}
