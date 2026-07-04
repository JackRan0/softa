package io.softa.starter.metadata.service.impl;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.metadata.ddl.DdlExecutor;
import io.softa.starter.metadata.dto.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the runtime apply engine {@link MetadataApplyServiceImpl}: per-row UPSERT / DELETE keyed
 * by business key (located via {@link ModelService#searchOne}), rename located by the prior key
 * ({@code renamedFrom}), server-side {@code appCode} stamping, and DDL-before-rows fail-fast.
 */
class MetadataApplyServiceImplTest {

    @BeforeAll
    static void ensureSystemConfig() {
        // IllegalArgumentException / identity resolution reach SystemConfig.env; seed it for raw unit tests.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
        if (SystemConfig.env.getAppCode() == null) {
            SystemConfig.env.setAppCode("demo-app");
        }
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Serializable> mockModelService() {
        return mock(ModelService.class);
    }

    private static MetadataApplyServiceImpl serviceWith(ModelService<Serializable> modelService) {
        return new MetadataApplyServiceImpl(modelService, mock(DdlExecutor.class));
    }

    @Test
    void metadataChangeSetNormalizesNullListsToEmpty() {
        MetadataChangeSet changeSet = new MetadataChangeSet(null, null);

        assertTrue(changeSet.changes().isEmpty());
        assertTrue(changeSet.ddl().isEmpty());
        assertTrue(changeSet.isEmpty());
    }

    @Test
    void applyChangesUpsertUpdatesExistingByBusinessKey() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataApplyServiceImpl service = serviceWith(modelService);

        // A field UPSERT whose business key already exists on the runtime → in-place UPDATE keyed by that
        // row's id (identity is the business key, not a surrogate logicalId).
        MetaChange upsert = new MetaChange(MetaTable.FIELD, ChangeOp.UPSERT,
                Map.of("modelName", "Customer", "fieldName", "code", "appId", 7L));
        when(modelService.searchOne(eq("SysField"), any(FlexQuery.class)))
                .thenReturn(Optional.of(Map.of(ModelConstant.ID, 99L)));   // found by business key

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField(eq("SysField"), anyString())).thenReturn(true);

            service.applyChanges(new MetadataChangeSet(List.of(upsert), List.of()));

            // Located row UPDATED in place: its id, the business value, and the server-stamped identity;
            // appId (design-internal) sanitized out via SysCatalog.
            verify(modelService).updateOne(eq("SysField"), argThat(row ->
                    Long.valueOf(99L).equals(row.get(ModelConstant.ID))
                            && "code".equals(row.get("fieldName"))
                            && "demo-app".equals(row.get("appCode"))
                            && !row.containsKey("appId")));
            verify(modelService, never()).createOne(eq("SysField"), anyMap());
        }
    }

    @Test
    void applyChangesUpsertInsertsWhenAbsent() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataApplyServiceImpl service = serviceWith(modelService);

        MetaChange upsert = new MetaChange(MetaTable.FIELD, ChangeOp.UPSERT,
                Map.of("modelName", "Customer", "fieldName", "code"));
        // Not found by business key (no rename → no old-key lookup) → INSERT.
        when(modelService.searchOne(eq("SysField"), any(FlexQuery.class))).thenReturn(Optional.empty());

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField(eq("SysField"), anyString())).thenReturn(true);

            service.applyChanges(new MetadataChangeSet(List.of(upsert), List.of()));

            verify(modelService).createOne(eq("SysField"), argThat(row ->
                    "code".equals(row.get("fieldName"))
                            && "demo-app".equals(row.get("appCode"))
                            && !row.containsKey(ModelConstant.ID)));
            verify(modelService, never()).updateOne(eq("SysField"), anyMap());
        }
    }

    @Test
    void applyChangesUpsertLocatesRenamedRowByOldBusinessKey() {
        // A renamed runtime field still holds the OLD name. The UPSERT's NEW business key
        // misses, and only renamedFrom (the old key) locates the row → in-place rename UPDATE +
        // renamed_from written. NOT a drop+add divorce.
        ModelService<Serializable> modelService = mockModelService();
        MetadataApplyServiceImpl service = serviceWith(modelService);

        MetaChange rename = new MetaChange(MetaTable.FIELD, ChangeOp.UPSERT,
                Map.of("modelName", "Customer", "fieldName", "partnerCode"), "code");
        // searchOne order: findByBusinessKey(new) → miss; findByOldBusinessKey(old) → row.
        when(modelService.searchOne(eq("SysField"), any(FlexQuery.class)))
                .thenReturn(Optional.empty(), Optional.of(Map.of(ModelConstant.ID, 99L)));

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField(eq("SysField"), anyString())).thenReturn(true);

            service.applyChanges(new MetadataChangeSet(List.of(rename), List.of()));

            verify(modelService).updateOne(eq("SysField"), argThat(row ->
                    Long.valueOf(99L).equals(row.get(ModelConstant.ID))           // located by OLD key
                            && "partnerCode".equals(row.get("fieldName"))         // renamed in place
                            && "code".equals(row.get("renamedFrom"))));           // dedicated renamed_from write
            verify(modelService, never()).createOne(eq("SysField"), anyMap());    // no divorce
        }
    }

    @Test
    void applyChangesUpsertIsIdempotentWhenReapplied() {
        // A retried dispatch re-applies the same UPSERT. The row matches by business key, so each apply
        // locates it and UPDATEs in place — never a duplicate INSERT, so replaying a timed-out deploy is
        // safe (the studio apply path has no other dedup).
        ModelService<Serializable> modelService = mockModelService();
        MetadataApplyServiceImpl service = serviceWith(modelService);

        MetaChange upsert = new MetaChange(MetaTable.FIELD, ChangeOp.UPSERT,
                Map.of("modelName", "Customer", "fieldName", "code"));
        when(modelService.searchOne(eq("SysField"), any(FlexQuery.class)))
                .thenReturn(Optional.of(Map.of(ModelConstant.ID, 99L)));   // already present

        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField(eq("SysField"), anyString())).thenReturn(true);

            service.applyChanges(new MetadataChangeSet(List.of(upsert), List.of()));
            service.applyChanges(new MetadataChangeSet(List.of(upsert), List.of()));   // retry

            verify(modelService, times(2)).updateOne(eq("SysField"), anyMap());
            verify(modelService, never()).createOne(eq("SysField"), anyMap());
        }
    }

    @Test
    void applyChangesDeleteIsNoOpWhenAlreadyAbsent() {
        // Idempotency: a retried DELETE whose target is already gone (business-key miss) is a safe no-op —
        // no error, no stray delete.
        ModelService<Serializable> modelService = mockModelService();
        MetadataApplyServiceImpl service = serviceWith(modelService);

        MetaChange delete = new MetaChange(MetaTable.FIELD, ChangeOp.DELETE,
                Map.of("modelName", "Customer", "fieldName", "gone"));
        when(modelService.searchOne(eq("SysField"), any(FlexQuery.class))).thenReturn(Optional.empty());

        service.applyChanges(new MetadataChangeSet(List.of(delete), List.of()));

        verify(modelService, never()).deleteById(anyString(), any());
    }

    @Test
    void applyChangesDeleteRemovesByBusinessKey() {
        ModelService<Serializable> modelService = mockModelService();
        MetadataApplyServiceImpl service = serviceWith(modelService);

        MetaChange delete = new MetaChange(MetaTable.FIELD, ChangeOp.DELETE,
                Map.of("modelName", "Customer", "fieldName", "oldField"));
        when(modelService.searchOne(eq("SysField"), any(FlexQuery.class)))
                .thenReturn(Optional.of(Map.of(ModelConstant.ID, 99L)));

        service.applyChanges(new MetadataChangeSet(List.of(delete), List.of()));

        verify(modelService).deleteById("SysField", 99L);
    }

    @Test
    void applyChangesRunsDdlBeforeRows() {
        ModelService<Serializable> modelService = mockModelService();
        DdlExecutor ddlExecutor = mock(DdlExecutor.class);
        when(ddlExecutor.executeAll(anyList()))
                .thenReturn(List.of(new DdlStatementResult(0, DdlStatementStatus.SUCCESS, null, null)));
        MetadataApplyServiceImpl service = new MetadataApplyServiceImpl(modelService, ddlExecutor);

        // Structure-only change set: no row changes, just DDL — the DDL still executes.
        service.applyChanges(new MetadataChangeSet(List.of(), List.of("CREATE TABLE customer (id BIGINT)")));

        verify(ddlExecutor).executeAll(List.of("CREATE TABLE customer (id BIGINT)"));
    }

    @Test
    void applyChangesAbortsBeforeRowsOnDdlFailure() {
        ModelService<Serializable> modelService = mockModelService();
        DdlExecutor ddlExecutor = mock(DdlExecutor.class);
        when(ddlExecutor.executeAll(anyList()))
                .thenReturn(List.of(new DdlStatementResult(1, DdlStatementStatus.FAILED, "boom", null)));
        MetadataApplyServiceImpl service = new MetadataApplyServiceImpl(modelService, ddlExecutor);

        MetadataChangeSet cs = new MetadataChangeSet(
                List.of(new MetaChange(MetaTable.MODEL, ChangeOp.UPSERT, Map.of("modelName", "Customer"))),
                List.of("CREATE bad"));

        // A failed DDL statement aborts before any row write — committed rows never describe absent structure.
        assertThrows(IllegalStateException.class, () -> service.applyChanges(cs));
        verify(modelService, never()).createOne(anyString(), anyMap());
        verify(modelService, never()).updateOne(anyString(), anyMap());
    }
}
