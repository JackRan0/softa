package io.softa.framework.orm.jdbc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.changelog.ChangeLogPublisher;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.impl.ModelServiceImpl;
import io.softa.framework.orm.service.versioning.IdentityStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The optimistic lock of a versionLock model, through the real update path.
 *
 * <p>{@code version} is readonly, so ModelService drops it from the fields to update, and for as
 * long as the lock has existed that was the end of it: a stale version was written over, the
 * version never moved, and the {@code AND version = ?} guard was unreachable. Now a patch that
 * carries the version its client read is checked against the stored one and written only at it,
 * and every written row moves its version on in the SQL itself, whether or not the patch named one.
 *
 * <p>Runs ModelServiceImpl, the pipeline, JdbcServiceImpl and the SQL builder for real; only the
 * metadata and the JDBC calls are stubbed.
 */
class VersionLockUpdateTest {

    private static final String MODEL = "Payment";

    private final JdbcProxy jdbcProxy = mock(JdbcProxy.class);
    private final ChangeLogPublisher changeLogPublisher = mock(ChangeLogPublisher.class);
    private final JdbcServiceImpl<Long> jdbcService = new JdbcServiceImpl<>();
    private final ModelServiceImpl<Long> modelService = new ModelServiceImpl<>();
    private final Map<Long, Map<String, Object>> stored = new LinkedHashMap<>();
    private MockedStatic<ModelManager> models;
    private SystemConfig previousConfig;

    @BeforeEach
    void setUp() {
        previousConfig = SystemConfig.env;
        SystemConfig.env = new SystemConfig();
        ReflectionTestUtils.setField(jdbcService, "jdbcProxy", jdbcProxy);
        ReflectionTestUtils.setField(jdbcService, "changeLogPublisher", changeLogPublisher);
        VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
        when(versioning.of(MODEL)).thenReturn(new IdentityStrategy<>(jdbcService));
        ReflectionTestUtils.setField(modelService, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(modelService, "permissionService", mock(PermissionService.class));
        ReflectionTestUtils.setField(modelService, "versioning", versioning);

        models = mockStatic(ModelManager.class);
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "modelName", MODEL);
        ReflectionTestUtils.setField(metaModel, "tableName", "payment");
        ReflectionTestUtils.setField(metaModel, "auditUpdateFields", Set.of(ModelConstant.UPDATED_TIME));
        models.when(() -> ModelManager.getModel(MODEL)).thenReturn(metaModel);
        models.when(() -> ModelManager.getModelPrimaryKey(MODEL)).thenReturn(ModelConstant.ID);
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);
        List<MetaField> fields = List.of(
                field(ModelConstant.ID, "id", FieldType.LONG, true),
                field("status", "status", FieldType.STRING, false),
                field("amount", "amount", FieldType.INTEGER, false),
                field(ModelConstant.VERSION, "version", FieldType.INTEGER, true),
                field(ModelConstant.UPDATED_TIME, "updated_time", FieldType.DATE_TIME, true));
        fields.forEach(f -> models.when(() -> ModelManager.getModelField(MODEL, f.getFieldName())).thenReturn(f));
        models.when(() -> ModelManager.getModelPrimaryKeyField(MODEL)).thenReturn(fields.getFirst());
        models.when(() -> ModelManager.getModelFields(MODEL)).thenReturn(fields);
        // What ModelManager derives from the readonly flags: `version` is not updatable.
        models.when(() -> ModelManager.getModelUpdatableFields(MODEL)).thenAnswer(invocation -> new HashSet<>(
                fields.stream().filter(f -> !f.isReadonly()).map(MetaField::getFieldName).toList()));

        storeRow(1L, "NEW", 100, 3);
        storeRow(2L, "NEW", 200, 7);
        // The originals come from the stored rows; the writes match unless a test says otherwise.
        when(jdbcProxy.queryForList(eq(MODEL), any())).thenAnswer(invocation -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            stored.values().forEach(row -> rows.add(new HashMap<>(row)));
            return rows;
        });
        when(jdbcProxy.update(anyString(), any())).thenReturn(1);
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList()))
                .thenAnswer(invocation -> new int[invocation.<List<?>>getArgument(2).size()]);
    }

    @AfterEach
    void tearDown() {
        models.close();
        SystemConfig.env = previousConfig;
    }

    @Test
    void aSecondUpdateCarryingTheSameReadVersionFails() {
        // Both callers read the row at version 3; the first one won.
        stored.get(1L).put(ModelConstant.VERSION, 4);

        assertThatThrownBy(() -> modelService.updateOne(MODEL, patch(1L, 3, "status", "PAID")))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("Provided value: 3, database value: 4");
        verifyNothingWritten();
    }

    @Test
    void anUpdateAtTheStoredVersionIsWrittenOnlyAtThatVersionAndMovesItOn() {
        boolean updated = modelService.updateOne(MODEL, patch(1L, 3, "status", "PAID"));

        assertThat(updated).isTrue();
        SqlParams sql = updateOneCall();
        assertThat(sql.getSql()).startsWith("UPDATE payment SET ")
                .contains("status=?")
                .endsWith(",version=version+1 WHERE id = ? AND version = ?");
        // The guard is the stored version; the patch's version is never set as a value.
        assertThat(sql.getArgs()).endsWith(1L, 3).doesNotContain(4);
    }

    @Test
    void aVersionComparesAsANumberWhateverTypeItArrivesAs() {
        // A Long version reaches the browser as a string, and comes back as one.
        assertThat(modelService.updateOne(MODEL, patch(1L, "3", "status", "PAID"))).isTrue();
        assertThat(updateOneCall().getArgs()).endsWith(1L, 3);
    }

    @Test
    void anUpdateWithoutAVersionIsNotCheckedButStillMovesItOn() {
        // A concurrent writer holding version 3 must see this write: the SQL bumps the version.
        assertThat(modelService.updateOne(MODEL, patch(1L, null, "status", "PAID"))).isTrue();

        SqlParams sql = updateOneCall();
        assertThat(sql.getSql()).endsWith(",version=version+1 WHERE id = ?");
        assertThat(sql.getArgs()).endsWith(1L);
    }

    @Test
    void aStaleVersionFailsEvenWhenNothingElseInThePatchDiffers() {
        stored.get(1L).put(ModelConstant.VERSION, 4);

        assertThatThrownBy(() -> modelService.updateOne(MODEL, patch(1L, 3, "status", "NEW")))
                .isInstanceOf(VersionException.class);
    }

    @Test
    void aPatchThatChangesNothingIsNotWrittenAndDoesNotMoveTheVersion() {
        assertThat(modelService.updateOne(MODEL, patch(1L, 3, "status", "NEW"))).isFalse();
        assertThat(modelService.updateOne(MODEL, patch(1L, null, "status", "NEW"))).isFalse();
        verifyNothingWritten();
    }

    @Test
    void aWriteThatLosesTheRaceAfterTheCheckFails() {
        // Another transaction committed between this update's read and its UPDATE.
        when(jdbcProxy.update(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> modelService.updateOne(MODEL, patch(1L, 3, "status", "PAID")))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("Provided value: 3");
    }

    @Test
    void aClientCannotSetTheVersion() {
        // A version the row never had is a stale one, not a value to write.
        assertThatThrownBy(() -> modelService.updateOne(MODEL, patch(1L, 999, "status", "PAID")))
                .isInstanceOf(VersionException.class);
        verifyNothingWritten();
    }

    @Test
    void aRowWithoutAStoredVersionIsNotChecked() {
        // Rows written before the column had a default may hold NULL: they stay writable.
        stored.get(1L).put(ModelConstant.VERSION, null);

        assertThat(modelService.updateOne(MODEL, patch(1L, 0, "status", "PAID"))).isTrue();
        assertThat(updateOneCall().getSql()).endsWith(",version=version+1 WHERE id = ?");
    }

    @Test
    void oneStaleRowFailsTheWholeListBeforeAnythingIsWritten() {
        stored.get(2L).put(ModelConstant.VERSION, 8);

        assertThatThrownBy(() -> modelService.updateList(MODEL, List.of(
                patch(1L, 3, "amount", 101),
                patch(2L, 7, "amount", 201))))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("Provided value: 7, database value: 8");
        verifyNothingWritten();
    }

    @Test
    void aListIsBatchedOnTheVersionEachRowWasReadAt() {
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList())).thenReturn(new int[]{1, 1});

        assertThat(modelService.updateList(MODEL, List.of(
                patch(1L, 3, "amount", 101),
                patch(2L, 7, "amount", 201)))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> batchArgs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<SqlParams> sql = ArgumentCaptor.forClass(SqlParams.class);
        verify(jdbcProxy).batchUpdateCounts(eq(MODEL), sql.capture(), batchArgs.capture());
        assertThat(sql.getValue().getSql()).isEqualTo(
                "UPDATE payment SET amount=?,updated_time=?,version=version+1 WHERE id = ? AND version = ?");
        assertThat(batchArgs.getValue()).extracting(args -> args[2], args -> args[3])
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 3), org.assertj.core.groups.Tuple.tuple(2L, 7));
    }

    @Test
    void aBatchRowMovedUnderneathFailsTheList() {
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList())).thenReturn(new int[]{1, 0});

        assertThatThrownBy(() -> modelService.updateList(MODEL, List.of(
                patch(1L, 3, "amount", 101),
                patch(2L, 7, "amount", 201))))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("Provided value: 7");
    }

    @Test
    void theChangeLogIsHandedTheRowsAsWritten() {
        modelService.updateOne(MODEL, patch(1L, 3, "status", "PAID"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> logged = ArgumentCaptor.forClass(List.class);
        verify(changeLogPublisher).publishUpdateLog(eq(MODEL), logged.capture(), any(), any(LocalDateTime.class));
        // The publisher leaves `version` out of the log (ChangeLogPublisherImplTest).
        assertThat(logged.getValue().getFirst()).containsEntry("status", "PAID").containsEntry(ModelConstant.VERSION, 3);
    }

    private void storeRow(long id, String status, int amount, Integer version) {
        Map<String, Object> row = new HashMap<>();
        row.put(ModelConstant.ID, id);
        row.put("status", status);
        row.put("amount", amount);
        row.put(ModelConstant.VERSION, version);
        stored.put(id, row);
    }

    private static Map<String, Object> patch(long id, Object version, String field, Object value) {
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, id);
        if (version != null) {
            patch.put(ModelConstant.VERSION, version);
        }
        patch.put(field, value);
        return patch;
    }

    private void verifyNothingWritten() {
        verify(jdbcProxy, never()).update(anyString(), any());
        verify(jdbcProxy, never()).batchUpdateCounts(anyString(), any(), anyList());
    }

    private SqlParams updateOneCall() {
        ArgumentCaptor<SqlParams> sqlParams = ArgumentCaptor.forClass(SqlParams.class);
        verify(jdbcProxy).update(eq(MODEL), sqlParams.capture());
        return sqlParams.getValue();
    }

    private static MetaField field(String name, String column, FieldType type, boolean readonly) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", MODEL);
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "columnName", column);
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "readonly", readonly);
        return field;
    }
}
