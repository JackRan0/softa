package io.softa.framework.orm.meta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How far a {@code cascadedField} may reach, judged at boot.
 *
 * <p>A plan row shows its employee's residence status, which lives on the employee's profile: two
 * relations away. A dynamic cascaded field may take that path; it is filled through its first relation,
 * whose model reads the rest as a dotted field. A stored one stays one relation, because it is only
 * recomputed when its own first relation changes.
 */
class CascadedFieldPathTest {

    private Object previousSnapshot;

    @BeforeAll
    static void systemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @BeforeEach
    void keepSnapshot() throws Exception {
        previousSnapshot = snapshotField().get(null);
    }

    @AfterEach
    void restoreSnapshot() throws Exception {
        snapshotField().set(null, previousSnapshot);
    }

    @Test
    void aDynamicFieldMayReachThroughTwoRelations() {
        initWith(cascade("residence", "employeeId.profileId.residence", true));

        MetaField residence = ModelManager.getModelField("Plan", "residence");
        // Filled through its first relation; that model is asked for the rest of the path.
        assertEquals(List.of("employeeId", "profileId.residence"), residence.getDependentFields());
    }

    @Test
    void aOneRelationCascadeIsUnchanged() {
        initWith(cascade("employeeName", "employeeId.name", true));

        assertEquals(List.of("employeeId", "name"),
                ModelManager.getModelField("Plan", "employeeName").getDependentFields());
    }

    @Test
    void aStoredFieldStaysOneRelation() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> initWith(cascade("residence", "employeeId.profileId.residence", false)));
        assertTrue(e.getMessage().contains("only allowed on a dynamic field"), e.getMessage());
    }

    @Test
    void everyStepButTheLastMustBeAToOneRelation() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> initWith(cascade("residence", "employeeId.name.residence", true)));
        assertTrue(e.getMessage().contains("is not a ManyToOne/OneToOne field of model `Employee`"), e.getMessage());
    }

    @Test
    void aRelationAlongTheWayMustBeStored() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> initWith(cascade("residence", "employeeId.dynamicProfileId.residence", true)));
        assertTrue(e.getMessage().contains("`dynamicProfileId` of model `Employee` is dynamic"), e.getMessage());
    }

    @Test
    void theFirstRelationOfALongerPathMustBeStoredToo() {
        // Filtering and sorting join the first relation by its column as well.
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> initWith(cascade("residence", "dynamicEmployeeId.profileId.residence", true)));
        assertTrue(e.getMessage().contains("`dynamicEmployeeId` of model `Plan` is dynamic"), e.getMessage());
    }

    @Test
    void theLastStepMustBeAStoredField() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> initWith(cascade("label", "employeeId.profileId.label", true)));
        assertTrue(e.getMessage().contains("is not a stored field of related model `Profile`"), e.getMessage());
    }

    @Test
    void aPathMayNotCrossMoreThanTheCascadeLevel() {
        // Five relations: one past the limit every other cascaded path is held to.
        RuntimeException e = assertThrows(RuntimeException.class, () -> initWith(cascade("topName",
                "employeeId.managerId.managerId.managerId.managerId.name", true)));
        assertTrue(e.getMessage().contains("crosses more than"), e.getMessage());
    }

    // ---- fixture ---------------------------------------------------------

    /** Plan → Employee (→ manager Employee) → Profile, each also by a dynamic relation, plus the one cascaded field under test. */
    private static void initWith(MetaField cascaded) {
        List<MetaModel> models = new ArrayList<>(List.of(model("Plan", "plan"), model("Employee", "employee"),
                model("Profile", "profile")));
        List<MetaField> fields = new ArrayList<>(List.of(
                field("Plan", "id", FieldType.LONG),
                relation("Plan", "employeeId", FieldType.MANY_TO_ONE, "Employee"),
                dynamic(relation("Plan", "dynamicEmployeeId", FieldType.MANY_TO_ONE, "Employee")),
                field("Employee", "id", FieldType.LONG),
                field("Employee", "name", FieldType.STRING),
                relation("Employee", "managerId", FieldType.MANY_TO_ONE, "Employee"),
                relation("Employee", "profileId", FieldType.ONE_TO_ONE, "Profile"),
                dynamic(relation("Employee", "dynamicProfileId", FieldType.ONE_TO_ONE, "Profile")),
                field("Profile", "id", FieldType.LONG),
                field("Profile", "residence", FieldType.STRING),
                dynamic(field("Profile", "label", FieldType.STRING)),
                cascaded));
        try {
            JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
            Mockito.when(jdbcService.selectMetaEntityList("SysModel", MetaModel.class, null)).thenReturn(models);
            Mockito.when(jdbcService.selectMetaEntityList("SysField", MetaField.class, null)).thenReturn(fields);
            ModelManager modelManager = new ModelManager();
            Field jdbc = ModelManager.class.getDeclaredField("jdbcService");
            jdbc.setAccessible(true);
            jdbc.set(modelManager, jdbcService);
            modelManager.init();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MetaField cascade(String fieldName, String path, boolean dynamic) {
        MetaField metaField = field("Plan", fieldName, FieldType.STRING);
        metaField.setCascadedField(path);
        metaField.setDynamic(dynamic);
        return metaField;
    }

    private static Field snapshotField() throws Exception {
        Field field = ModelManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        return field;
    }

    private static MetaModel model(String modelName, String tableName) {
        MetaModel metaModel = new MetaModel();
        metaModel.setModelName(modelName);
        metaModel.setLabel(modelName);
        metaModel.setTableName(tableName);
        return metaModel;
    }

    private static MetaField field(String modelName, String fieldName, FieldType type) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName(fieldName);
        metaField.setColumnName(fieldName);
        metaField.setLabel(fieldName);
        metaField.setFieldType(type);
        return metaField;
    }

    private static MetaField relation(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField metaField = field(modelName, fieldName, type);
        metaField.setRelatedModel(relatedModel);
        return metaField;
    }

    private static MetaField dynamic(MetaField metaField) {
        metaField.setDynamic(true);
        return metaField;
    }
}
