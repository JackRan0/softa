package io.softa.framework.orm.jdbc;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.annotation.ExecuteSql;
import io.softa.framework.orm.annotation.WriteOperation;
import io.softa.framework.orm.changelog.ChangeLogPublisher;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An update writes its rows as JDBC batches.
 *
 * <p>{@code updateList} and the soft delete used to send one UPDATE per row, so a payroll run
 * writing back a few thousand results paid a round trip for each. Rows that set the same fields
 * now share one statement and go out together. What each row writes and the single-row path are
 * what they were; under the version lock each row is still checked on its own.
 */
class UpdateRowsInBatchesTest {

    private static final String MODEL = "PayRunResult";

    private final JdbcProxy jdbcProxy = mock(JdbcProxy.class);
    private final ChangeLogPublisher changeLogPublisher = mock(ChangeLogPublisher.class);
    private final JdbcServiceImpl<Long> jdbcService = new JdbcServiceImpl<>();
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jdbcService, "jdbcProxy", jdbcProxy);
        ReflectionTestUtils.setField(jdbcService, "changeLogPublisher", changeLogPublisher);
        models = mockStatic(ModelManager.class);
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "modelName", MODEL);
        ReflectionTestUtils.setField(metaModel, "tableName", "pay_run_result");
        ReflectionTestUtils.setField(metaModel, "auditUpdateFields", Set.of(ModelConstant.UPDATED_TIME));
        models.when(() -> ModelManager.getModel(MODEL)).thenReturn(metaModel);
        models.when(() -> ModelManager.getModelPrimaryKey(MODEL)).thenReturn(ModelConstant.ID);
        stubColumn(ModelConstant.ID, "id");
        stubColumn("amount", "amount");
        stubColumn("status", "status");
        stubColumn(ModelConstant.VERSION, "version");
        stubColumn(ModelConstant.SOFT_DELETED_FIELD, "deleted");
        stubColumn(ModelConstant.ACTIVE_CONTROL_FIELD, "active");
        stubColumn(ModelConstant.UPDATED_TIME, "updated_time");
        // Every row matches unless a test says otherwise.
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList())).thenAnswer(invocation -> {
            int[] counts = new int[invocation.<List<?>>getArgument(2).size()];
            Arrays.fill(counts, 1);
            return counts;
        });
        when(jdbcProxy.update(anyString(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void rowsSettingTheSameFieldsGoOutAsOneBatch() {
        // The third row lists its fields in another order: the statement is the same.
        int count = jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10, "status", "A"),
                row("id", 2L, "amount", 20, "status", "B"),
                row("status", "C", "id", 3L, "amount", 30)));

        assertThat(count).isEqualTo(3);
        List<Batch> batches = batches(1);
        assertThat(batches.getFirst().sql()).isEqualTo("UPDATE pay_run_result SET amount=?,status=? WHERE id = ?");
        assertThat(batches.getFirst().args()).containsExactly(
                new Object[]{10, "A", 1L}, new Object[]{20, "B", 2L}, new Object[]{30, "C", 3L});
        verify(jdbcProxy, never()).update(anyString(), any());
    }

    @Test
    void rowsSettingDifferentFieldsAreBatchedByTheFieldsTheySet() {
        int count = jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10),
                row("id", 2L, "status", "B"),
                row("id", 3L, "amount", 30),
                row("id", 4L, "status", "D")));

        assertThat(count).isEqualTo(4);
        List<Batch> batches = batches(2);
        assertThat(batches.get(0).sql()).isEqualTo("UPDATE pay_run_result SET amount=? WHERE id = ?");
        assertThat(batches.get(0).args()).containsExactly(new Object[]{10, 1L}, new Object[]{30, 3L});
        assertThat(batches.get(1).sql()).isEqualTo("UPDATE pay_run_result SET status=? WHERE id = ?");
        assertThat(batches.get(1).args()).containsExactly(new Object[]{"B", 2L}, new Object[]{"D", 4L});
    }

    @Test
    void aSingleRowIsStillAPlainUpdateOne() {
        int count = jdbcService.updateRows(MODEL, List.of(row("id", 1L, "amount", 10)));

        assertThat(count).isEqualTo(1);
        SqlParams sqlParams = updateOneCall();
        assertThat(sqlParams.getSql()).isEqualTo("UPDATE pay_run_result SET amount=? WHERE id = ?");
        assertThat(sqlParams.getArgs()).containsExactly(10, 1L);
        verify(jdbcProxy, never()).batchUpdateCounts(anyString(), any(), anyList());
    }

    @Test
    void aRowAloneInItsShapeIsAPlainUpdateOne() {
        int count = jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10),
                row("id", 2L, "amount", 20),
                row("id", 3L, "amount", 30, "status", "C")));

        assertThat(count).isEqualTo(3);
        assertThat(batches(1).getFirst().args()).hasSize(2);
        assertThat(updateOneCall().getSql()).isEqualTo("UPDATE pay_run_result SET amount=?,status=? WHERE id = ?");
    }

    @Test
    void aLargeGroupIsSentInBatchesOfTheDefaultSize() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = 1; id <= 2500; id++) {
            rows.add(row("id", id, "amount", 10));
        }

        int count = jdbcService.updateRows(MODEL, rows);

        assertThat(count).isEqualTo(2500);
        assertThat(batches(3)).extracting(batch -> batch.args().size())
                .containsExactly(BaseConstant.DEFAULT_BATCH_SIZE, BaseConstant.DEFAULT_BATCH_SIZE, 500);
    }

    @Test
    void anEmptyListWritesNothing() {
        // What an update whose rows all matched their stored values hands down.
        assertThat(jdbcService.updateRows(MODEL, List.of())).isZero();
        verify(jdbcProxy, never()).update(anyString(), any());
        verify(jdbcProxy, never()).batchUpdateCounts(anyString(), any(), anyList());
    }

    @Test
    void aRowNamingOnlyItsPrimaryKeyIsSkipped() {
        // It has nothing to set: written, it would be `UPDATE pay_run_result SET WHERE id = ?`.
        int count = jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10),
                row("id", 2L),
                row("id", 3L, "amount", 30)));

        assertThat(count).isEqualTo(2);
        assertThat(batches(1).getFirst().args()).containsExactly(new Object[]{10, 1L}, new Object[]{30, 3L});
        verify(jdbcProxy, never()).update(anyString(), any());
    }

    @Test
    void aRowNamingOnlyItsPrimaryKeyDoesNotMoveTheVersionOn() {
        // Under the lock its statement would be valid — and a version bump with nothing changed.
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);

        assertThat(jdbcService.updateRows(MODEL, List.of(row("id", 1L)))).isZero();
        verify(jdbcProxy, never()).update(anyString(), any());
        verify(jdbcProxy, never()).batchUpdateCounts(anyString(), any(), anyList());
    }

    @Test
    void aCallRepeatingAPrimaryKeyIsWrittenRowByRowInTheOrderGiven() {
        // Grouped by shape, the first and third patches of row 1 would go out together and the
        // second would land last; written in order, the third is the one that stays.
        int count = jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10, "status", "A"),
                row("id", 1L, "amount", 20),
                row("id", 1L, "amount", 30, "status", "C"),
                row("id", 2L, "amount", 40, "status", "D")));

        assertThat(count).isEqualTo(4);
        ArgumentCaptor<SqlParams> sqlParams = ArgumentCaptor.forClass(SqlParams.class);
        verify(jdbcProxy, times(4)).update(eq(MODEL), sqlParams.capture());
        assertThat(sqlParams.getAllValues()).extracting(SqlParams::getArgs).containsExactly(
                List.of(10, "A", 1L), List.of(20, 1L), List.of(30, "C", 1L), List.of(40, "D", 2L));
        verify(jdbcProxy, never()).batchUpdateCounts(anyString(), any(), anyList());
    }

    @Test
    void aVersionLockedBatchMatchesEachRowOnTheVersionItWasReadAt() {
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);

        // Each row carries the version its patch was checked against; the SQL moves it on.
        int count = jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10, "version", 3),
                row("id", 2L, "amount", 20, "version", 7)));

        assertThat(count).isEqualTo(2);
        Batch batch = batches(1).getFirst();
        assertThat(batch.sql()).isEqualTo(
                "UPDATE pay_run_result SET amount=?,version=version+1 WHERE id = ? AND version = ?");
        assertThat(batch.args()).containsExactly(new Object[]{10, 1L, 3}, new Object[]{20, 2L, 7});
    }

    @Test
    void aVersionConflictInABatchStillFailsTheUpdate() {
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList())).thenReturn(new int[]{1, 0, 1});

        assertThatThrownBy(() -> jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10, "version", 3),
                row("id", 2L, "amount", 20, "version", 7),
                row("id", 3L, "amount", 30, "version", 1))))
                .isInstanceOf(VersionException.class)
                // The same message updateOne gives, naming the version the missed row was read at.
                .hasMessageContaining("Data version does not match")
                .hasMessageContaining("Provided value: 7");
    }

    @Test
    void aVersionLockedBatchTheDriverCannotCountIsRefused() {
        // Nothing to tell a matched row from a lost update: refuse rather than pass it.
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList()))
                .thenReturn(new int[]{1, Statement.SUCCESS_NO_INFO});

        assertThatThrownBy(() -> jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10, "version", 3),
                row("id", 2L, "amount", 20, "version", 7))))
                .isInstanceOf(SystemException.class)
                .hasMessageContaining("SUCCESS_NO_INFO");
    }

    @Test
    void versionLockedRowsWithoutAVersionAreWrittenUnguardedAndStillMoveItOn() {
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);
        // Unguarded, a row the driver cannot count is written like any other.
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList()))
                .thenReturn(new int[]{1, Statement.SUCCESS_NO_INFO});

        int count = jdbcService.updateRows(MODEL, List.of(row("id", 1L, "amount", 10), row("id", 2L, "amount", 20)));

        assertThat(count).isEqualTo(2);
        Batch batch = batches(1).getFirst();
        assertThat(batch.sql()).isEqualTo("UPDATE pay_run_result SET amount=?,version=version+1 WHERE id = ?");
        assertThat(batch.args()).containsExactly(new Object[]{10, 1L}, new Object[]{20, 2L});
    }

    @Test
    void rowsWithAndWithoutAVersionGoOutAsTwoStatements() {
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);

        jdbcService.updateRows(MODEL, List.of(
                row("id", 1L, "amount", 10, "version", 3),
                row("id", 2L, "amount", 20),
                row("id", 3L, "amount", 30, "version", 5),
                row("id", 4L, "amount", 40)));

        List<Batch> batches = batches(2);
        assertThat(batches.get(0).sql()).isEqualTo(
                "UPDATE pay_run_result SET amount=?,version=version+1 WHERE id = ? AND version = ?");
        assertThat(batches.get(0).args()).containsExactly(new Object[]{10, 1L, 3}, new Object[]{30, 3L, 5});
        assertThat(batches.get(1).sql()).isEqualTo("UPDATE pay_run_result SET amount=?,version=version+1 WHERE id = ?");
        assertThat(batches.get(1).args()).containsExactly(new Object[]{20, 2L}, new Object[]{40, 4L});
    }

    @Test
    void aSingleVersionLockedRowThatMatchesNothingIsAConflictOnlyWhenItCarriesAVersion() {
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);
        when(jdbcProxy.update(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> jdbcService.updateRows(MODEL, List.of(row("id", 1L, "amount", 10, "version", 3))))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("Provided value: 3");
        // Without a version, matching nothing only means the row is not there, as before.
        assertThat(jdbcService.updateRows(MODEL, List.of(row("id", 1L, "amount", 10)))).isZero();
    }

    @Test
    void withoutTheVersionLockARowTheDriverCannotCountCountsAsWritten() {
        when(jdbcProxy.batchUpdateCounts(anyString(), any(), anyList()))
                .thenReturn(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO});

        int count = jdbcService.updateRows(MODEL, List.of(row("id", 1L, "amount", 10), row("id", 2L, "amount", 20)));

        assertThat(count).isEqualTo(2);
    }

    @Test
    void aSoftDeleteWritesItsRowsAsOneBatch() {
        models.when(() -> ModelManager.isSoftDeleted(MODEL)).thenReturn(true);
        models.when(() -> ModelManager.isActiveControl(MODEL)).thenReturn(true);
        List<Map<String, Object>> deletableRows = List.of(
                row("id", 1L, "amount", 10), row("id", 2L, "amount", 20), row("id", 3L, "amount", 30));

        boolean deleted = jdbcService.deleteByIds(MODEL, List.of(1L, 2L, 3L), deletableRows);

        assertThat(deleted).isTrue();
        Batch batch = batches(1).getFirst();
        assertThat(batch.sql()).isEqualTo("UPDATE pay_run_result SET active=?,deleted=?,updated_time=? WHERE id = ?");
        assertThat(batch.args()).extracting(args -> args[3]).containsExactly(1L, 2L, 3L);
        assertThat(batch.args()).allSatisfy(args -> {
            assertThat(args[0]).isEqualTo(false);
            assertThat(args[1]).isEqualTo(true);
            assertThat(args[2]).isInstanceOf(LocalDateTime.class);
        });
        verify(jdbcProxy, never()).update(anyString(), any());
        verify(changeLogPublisher).publishDeletionLog(eq(MODEL), eq(deletableRows), any(LocalDateTime.class));
    }

    @Test
    void aSoftDeleteOfAVersionLockedModelMovesTheVersionOn() {
        models.when(() -> ModelManager.isSoftDeleted(MODEL)).thenReturn(true);
        models.when(() -> ModelManager.isVersionControl(MODEL)).thenReturn(true);

        jdbcService.deleteByIds(MODEL, List.of(1L, 2L), List.of(row("id", 1L), row("id", 2L)));

        assertThat(batches(1).getFirst().sql())
                .isEqualTo("UPDATE pay_run_result SET deleted=?,updated_time=?,version=version+1 WHERE id = ?");
    }

    @Test
    void theProxyReturnsTheCountOfEachRowAndKeepsItsRoutingAndLogAnnotations() throws NoSuchMethodException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcProxy proxy = new JdbcProxy();
        ReflectionTestUtils.setField(proxy, "jdbcTemplate", jdbcTemplate);
        List<Object[]> batchArgs = List.<Object[]>of(new Object[]{10, 1L}, new Object[]{20, 2L});
        when(jdbcTemplate.batchUpdate("UPDATE t SET a=? WHERE id = ?", batchArgs)).thenReturn(new int[]{1, 0});

        assertThat(proxy.batchUpdateCounts(MODEL, new SqlParams("UPDATE t SET a=? WHERE id = ?"), batchArgs))
                .containsExactly(1, 0);
        // Data source routing and the debug SQL log hook onto these.
        var method = JdbcProxy.class.getMethod("batchUpdateCounts", String.class, SqlParams.class, List.class);
        assertThat(method.isAnnotationPresent(WriteOperation.class)).isTrue();
        assertThat(method.isAnnotationPresent(ExecuteSql.class)).isTrue();
    }

    private record Batch(String sql, List<Object[]> args) {
    }

    /** The batches sent, in order — asserting there were exactly {@code expected} of them. */
    @SuppressWarnings("unchecked")
    private List<Batch> batches(int expected) {
        ArgumentCaptor<SqlParams> sqlParams = ArgumentCaptor.forClass(SqlParams.class);
        ArgumentCaptor<List<Object[]>> batchArgs = ArgumentCaptor.forClass(List.class);
        verify(jdbcProxy, times(expected)).batchUpdateCounts(eq(MODEL), sqlParams.capture(), batchArgs.capture());
        List<Batch> batches = new ArrayList<>();
        for (int i = 0; i < expected; i++) {
            batches.add(new Batch(sqlParams.getAllValues().get(i).getSql(), batchArgs.getAllValues().get(i)));
        }
        return batches;
    }

    /** The one statement sent through updateOne. */
    private SqlParams updateOneCall() {
        ArgumentCaptor<SqlParams> sqlParams = ArgumentCaptor.forClass(SqlParams.class);
        verify(jdbcProxy).update(eq(MODEL), sqlParams.capture());
        return sqlParams.getValue();
    }

    private void stubColumn(String fieldName, String columnName) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "columnName", columnName);
        models.when(() -> ModelManager.getModelField(MODEL, fieldName)).thenReturn(metaField);
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}
