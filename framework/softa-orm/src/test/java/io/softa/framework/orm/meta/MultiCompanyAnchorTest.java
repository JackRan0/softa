package io.softa.framework.orm.meta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the boot-time resolution of which field a {@code multiCompany} model reaches its company
 * through — the anchor the permission grant ({@code PermissionServiceImpl.companyScope}) bounds every such read by.
 *
 * <p>There is no per-company read narrowing any more (it went with the header switcher), but the
 * anchor contract stays load-bearing: a model marked {@code multiCompany} without a usable anchor
 * would be bounded by nothing. Builds a real frozen snapshot through {@code init()} with a mocked
 * {@link JdbcService}, mirroring {@code MultiCountryScopeTest}, because the validation reads snapshot
 * internals.
 */
class MultiCompanyAnchorTest {

    private static Object previousSnapshot;

    @BeforeAll
    static void initSnapshot() throws Exception {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        previousSnapshot = snapshotField().get(null);
        initWith(models(), fields());
    }

    @AfterAll
    static void restoreSnapshot() throws Exception {
        snapshotField().set(null, previousSnapshot);
    }

    // ---- boot validation -------------------------------------------------

    @Test
    void aModelWithNoCompanyReferenceIsRejectedAtInit() throws Exception {
        // Otherwise the flag is a no-op and the model silently keeps showing every company's rows —
        // indistinguishable from never having marked it, which is the failure this mechanism removes.
        Object good = snapshotField().get(null);
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> initWith(
                    new ArrayList<>(List.of(multiCompany("Orphan", "orphan"), legalEntity())),
                    new ArrayList<>(List.of(
                            field("Orphan", "id", "id", FieldType.LONG),
                            field(ModelConstant.COMPANY_MODEL, "id", "id", FieldType.LONG)))));
            assertTrue(e.getMessage().contains("must declare"), e.getMessage());
        } finally {
            snapshotField().set(null, good);
        }
    }

    @Test
    void aSecondCompanyReferenceIsNotAnAnchor() throws Exception {
        // Holding two references to a company is normal — one says the rows belong to it, the other
        // records something about them. A pay group belongs to the entity it is set up under and names
        // a second as the one that pays it. Requiring the name is what keeps the second from ever
        // being mistaken for the axis; without companyId present at all, boot fails rather than
        // narrowing by whichever reference happened to be found first.
        Object good = snapshotField().get(null);
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> initWith(
                    new ArrayList<>(List.of(multiCompany("TwoRefs", "two_refs"), legalEntity())),
                    new ArrayList<>(List.of(
                            field("TwoRefs", "id", "id", FieldType.LONG),
                            companyRef("TwoRefs", "payingEntityId"),
                            companyRef("TwoRefs", "owningEntityId"),
                            field(ModelConstant.COMPANY_MODEL, "id", "id", FieldType.LONG)))));
            assertTrue(e.getMessage().contains(ModelConstant.COMPANY_FIELD), e.getMessage());
        } finally {
            snapshotField().set(null, good);
        }
    }

    @Test
    void aDynamicJoinedReferenceIsAnAnchorLikeAnyOther() throws Exception {
        // How a per-department statistic satisfies the required anchor without a column of its own:
        // declare companyId as a dynamic cascaded field. A grant condition names the field and
        // WhereBuilder rewrites it back to deptId.companyId, so the bound compiles to a LEFT JOIN. A real column would be the other option and the worse one: it goes stale the moment a
        // department is re-parented onto another entity.
        Object good = snapshotField().get(null);
        try {
            initWith(new ArrayList<>(List.of(multiCompany("Stats", "stats"), legalEntity(),
                            model("Department", "department"))),
                    new ArrayList<>(List.of(
                            field("Stats", "id", "id", FieldType.LONG),
                            deptRef("Stats", "deptId"),
                            dynamicCompanyRef("Stats", ModelConstant.COMPANY_FIELD,
                                    "deptId." + ModelConstant.COMPANY_FIELD),
                            field("Department", "id", "id", FieldType.LONG),
                            companyRef("Department", ModelConstant.COMPANY_FIELD),
                            field(ModelConstant.COMPANY_MODEL, "id", "id", FieldType.LONG))));

            // The boot accepts the model, and the anchor is the dynamic field — nothing to resolve at
            // read time, the grant names the field and WhereBuilder joins.
            assertTrue(ModelManager.getModel("Stats").isMultiCompany());
            MetaField anchor = ModelManager.getModelField("Stats", ModelConstant.COMPANY_FIELD);
            assertTrue(anchor.isDynamic());
            assertEquals("deptId." + ModelConstant.COMPANY_FIELD, anchor.getCascadedField());
        } finally {
            snapshotField().set(null, good);
        }
    }

    @Test
    void theCompanyModelItselfCannotBeMultiCompany() throws Exception {
        // The company model has no company reference to anchor on; the grant bounds it by its own id
        // instead (PermissionServiceImpl.companyScope), and the company list must never be
        // narrowed by anything else.
        Object good = snapshotField().get(null);
        try {
            MetaModel selfScoped = multiCompany(ModelConstant.COMPANY_MODEL, "company");
            RuntimeException e = assertThrows(RuntimeException.class, () -> initWith(
                    new ArrayList<>(List.of(selfScoped)),
                    new ArrayList<>(List.of(
                            field(ModelConstant.COMPANY_MODEL, "id", "id", FieldType.LONG)))));
            assertTrue(e.getMessage().contains("IS the company"), e.getMessage());
        } finally {
            snapshotField().set(null, good);
        }
    }

    @Test
    void anAnchorPointingAtTheWrongModelIsRejectedAtInit() throws Exception {
        // A field of the right name pointing somewhere else is worse than a missing one: the condition
        // would still be emitted, comparing company ids against another model's ids and matching
        // nothing — data that looks missing rather than a configuration that looks broken.
        Object good = snapshotField().get(null);
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> initWith(
                    new ArrayList<>(List.of(multiCompany("WrongTarget", "wrong_target"), legalEntity(),
                            model("Department", "department"))),
                    new ArrayList<>(List.of(
                            field("WrongTarget", "id", "id", FieldType.LONG),
                            deptRef("WrongTarget", ModelConstant.COMPANY_FIELD),
                            field("Department", "id", "id", FieldType.LONG),
                            field(ModelConstant.COMPANY_MODEL, "id", "id", FieldType.LONG)))));
            assertTrue(e.getMessage().contains("ManyToOne/OneToOne onto"), e.getMessage());
        } finally {
            snapshotField().set(null, good);
        }
    }

    @Test
    void aPlainModelIsNotOnTheCompanyAxis() {
        assertFalse(ModelManager.getModel("Unscoped").isMultiCompany());
    }

    // ---- fixture ---------------------------------------------------------

    private static Field snapshotField() throws Exception {
        Field field = ModelManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        return field;
    }

    private static void initWith(List<MetaModel> models, List<MetaField> fields) {
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

    // ArrayList, not List.of: ListUtils.allNotNull probes contains(null), which immutable
    // collections reject with NPE.
    private static List<MetaModel> models() {
        MetaModel stats = multiCompany("DeptStats", "dept_stats");
        MetaModel both = multiCompany("BothScoped", "both_scoped");
        both.setMultiCountry(true);
        return new ArrayList<>(List.of(
                multiCompany("Department", "department"),
                stats,
                both,
                model("Unscoped", "unscoped"),
                legalEntity(),
                model(ModelConstant.COUNTRY_REGION_MODEL, "country_region")));
    }

    private static List<MetaField> fields() {
        return new ArrayList<>(List.of(
                field("Department", "id", "id", FieldType.LONG),
                companyRef("Department", "companyId"),
                field("Department", "active", "active", FieldType.BOOLEAN),
                field("DeptStats", "id", "id", FieldType.LONG),
                deptRef("DeptStats", "deptId"),
                // Production shape: no company column of its own, the company is joined through
                // the department. Resolves by convention — there is no companyField to declare.
                dynamicCompanyRef("DeptStats", ModelConstant.COMPANY_FIELD,
                        "deptId." + ModelConstant.COMPANY_FIELD),
                field("BothScoped", "id", "id", FieldType.LONG),
                companyRef("BothScoped", "companyId"),
                countryRef("BothScoped", "country"),
                // An unscoped model may carry a company reference too — it records which company the
                // row relates to, and must not be mistaken for one whose rows belong to a company.
                field("Unscoped", "id", "id", FieldType.LONG),
                companyRef("Unscoped", "companyId"),
                field("Unscoped", "active", "active", FieldType.BOOLEAN),
                field(ModelConstant.COMPANY_MODEL, "id", "id", FieldType.LONG),
                field(ModelConstant.COUNTRY_REGION_MODEL, "id", "id", FieldType.STRING)));
    }

    private static MetaModel model(String modelName, String tableName) {
        MetaModel metaModel = new MetaModel();
        metaModel.setModelName(modelName);
        metaModel.setLabel(modelName);
        metaModel.setTableName(tableName);
        return metaModel;
    }

    private static MetaModel multiCompany(String modelName, String tableName) {
        MetaModel metaModel = model(modelName, tableName);
        metaModel.setMultiCompany(true);
        return metaModel;
    }

    private static MetaModel legalEntity() {
        return model(ModelConstant.COMPANY_MODEL, "company");
    }

    private static MetaField field(String modelName, String fieldName, String columnName, FieldType type) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName(fieldName);
        metaField.setColumnName(columnName);
        metaField.setLabel(fieldName);
        metaField.setFieldType(type);
        return metaField;
    }

    private static MetaField relation(String modelName, String fieldName, String relatedModel) {
        MetaField metaField = field(modelName, fieldName, fieldName, FieldType.MANY_TO_ONE);
        metaField.setRelatedModel(relatedModel);
        return metaField;
    }

    private static MetaField companyRef(String modelName, String fieldName) {
        return relation(modelName, fieldName, ModelConstant.COMPANY_MODEL);
    }

    /** A company reference that is joined at query time rather than stored — no column of its own. */
    private static MetaField dynamicCompanyRef(String modelName, String fieldName, String path) {
        MetaField metaField = companyRef(modelName, fieldName);
        metaField.setDynamic(true);
        metaField.setCascadedField(path);
        return metaField;
    }

    private static MetaField deptRef(String modelName, String fieldName) {
        return relation(modelName, fieldName, "Department");
    }

    private static MetaField employeeRef(String modelName, String fieldName) {
        return relation(modelName, fieldName, "Employee");
    }

    private static MetaField countryRef(String modelName, String fieldName) {
        return relation(modelName, fieldName, ModelConstant.COUNTRY_REGION_MODEL);
    }
}
