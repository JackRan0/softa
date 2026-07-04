package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

/**
 * {@link DesignEnvCloner} clones one env's design aggregates into another
 * env, minting fresh ids and remapping parent FKs onto the cloned parent's new id. Identity is
 * the business key — there is no cross-env surrogate carried by the clone.
 */
@SuppressWarnings("unchecked")
class DesignEnvClonerTest {

    private static final long SRC_ENV = 100L;
    private static final long TGT_ENV = 200L;
    private static final long APP = 7L;
    private static final long NEW_MODEL_ID = 1001L;
    private static final long NEW_SET_ID = 1002L;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("clones aggregates: strips id, stamps target envId, remaps parent FKs")
    void clonesWithFreshIdsAndRemappedFks() {
        ModelService<Long> ms = mock(ModelService.class);

        when(ms.searchList(eq("DesignModel"), any(FlexQuery.class))).thenReturn(List.of(
                row("id", 1L, "appId", APP, "envId", SRC_ENV,
                        "modelName", "Customer", "tableName", "customer", "createdBy", "alice")));
        when(ms.searchList(eq("DesignField"), any(FlexQuery.class))).thenReturn(List.of(
                row("id", 10L, "appId", APP, "envId", SRC_ENV, "modelId", 1L,
                        "fieldName", "code", "columnName", "code")));
        when(ms.searchList(eq("DesignModelIndex"), any(FlexQuery.class))).thenReturn(List.of(
                row("id", 20L, "appId", APP, "envId", SRC_ENV, "modelId", 1L,
                        "indexName", "uk_code")));
        when(ms.searchList(eq("DesignOptionSet"), any(FlexQuery.class))).thenReturn(List.of(
                row("id", 30L, "appId", APP, "envId", SRC_ENV, "optionSetCode", "status")));
        when(ms.searchList(eq("DesignOptionItem"), any(FlexQuery.class))).thenReturn(List.of(
                row("id", 40L, "appId", APP, "envId", SRC_ENV, "optionSetId", 30L,
                        "itemCode", "active")));

        when(ms.createOne(eq("DesignModel"), any())).thenReturn(NEW_MODEL_ID);
        when(ms.createOne(eq("DesignOptionSet"), any())).thenReturn(NEW_SET_ID);
        when(ms.createList(eq("DesignField"), any())).thenReturn(List.of(101L));
        when(ms.createList(eq("DesignModelIndex"), any())).thenReturn(List.of(201L));
        when(ms.createList(eq("DesignOptionItem"), any())).thenReturn(List.of(401L));

        int created = new DesignEnvCloner(ms).cloneEnv(APP, SRC_ENV, TGT_ENV);

        // 1 model + 1 field + 1 index + 1 option set + 1 option item.
        assertEquals(5, created);

        ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
        verify(ms).createOne(eq("DesignModel"), model.capture());
        assertFalse(model.getValue().containsKey("id"), "fresh id must be minted (id stripped)");
        assertFalse(model.getValue().containsKey("createdBy"), "audit trail must be stripped");
        assertEquals(TGT_ENV, model.getValue().get("envId"), "envId stamped to target");
        assertEquals("Customer", model.getValue().get("modelName"));

        ArgumentCaptor<List<Map<String, Object>>> fields = ArgumentCaptor.forClass(List.class);
        verify(ms).createList(eq("DesignField"), fields.capture());
        Map<String, Object> field = fields.getValue().getFirst();
        assertFalse(field.containsKey("id"));
        assertEquals(NEW_MODEL_ID, field.get("modelId"), "field re-parented to the cloned model's new id");
        assertEquals(TGT_ENV, field.get("envId"));

        ArgumentCaptor<List<Map<String, Object>>> indexes = ArgumentCaptor.forClass(List.class);
        verify(ms).createList(eq("DesignModelIndex"), indexes.capture());
        assertEquals(NEW_MODEL_ID, indexes.getValue().getFirst().get("modelId"), "index re-parented to new model id");

        ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass(List.class);
        verify(ms).createList(eq("DesignOptionItem"), items.capture());
        Map<String, Object> item = items.getValue().getFirst();
        assertEquals(NEW_SET_ID, item.get("optionSetId"), "item re-parented to the cloned option set's new id");
        assertEquals(TGT_ENV, item.get("envId"));
    }

    @Test
    @DisplayName("a childless aggregate clones the parent only — no empty child insert")
    void childlessAggregateInsertsNoChildren() {
        ModelService<Long> ms = mock(ModelService.class);
        when(ms.searchList(eq("DesignModel"), any(FlexQuery.class))).thenReturn(List.of(
                row("id", 1L, "appId", APP, "envId", SRC_ENV, "modelName", "Empty")));
        when(ms.searchList(eq("DesignField"), any(FlexQuery.class))).thenReturn(List.of());
        when(ms.searchList(eq("DesignModelIndex"), any(FlexQuery.class))).thenReturn(List.of());
        when(ms.searchList(eq("DesignOptionSet"), any(FlexQuery.class))).thenReturn(List.of());
        when(ms.searchList(eq("DesignOptionItem"), any(FlexQuery.class))).thenReturn(List.of());
        when(ms.createOne(eq("DesignModel"), any())).thenReturn(NEW_MODEL_ID);

        int created = new DesignEnvCloner(ms).cloneEnv(APP, SRC_ENV, TGT_ENV);

        assertEquals(1, created);
        verify(ms, never()).createList(eq("DesignField"), any());
        verify(ms, never()).createList(eq("DesignModelIndex"), any());
    }

    @Test
    @DisplayName("restore (replaceEnvDesign): wipes current design then re-materializes — fresh ids + FK remap")
    void replaceEnvDesignWipesThenReMaterializes() {
        ModelService<Long> ms = mock(ModelService.class);
        // Snapshot rows carry their original ids; restore must NOT depend on preserving them (the create
        // pipeline regenerates ids when system.enable-insert-id is off — the default).
        DesignRows snapshot = new DesignRows(
                List.of(row("id", 1L, "envId", TGT_ENV, "modelName", "Customer")),
                List.of(row("id", 10L, "envId", TGT_ENV, "modelId", 1L, "fieldName", "code")),
                List.of(), List.of(), List.of());
        when(ms.createOne(eq("DesignModel"), any())).thenReturn(NEW_MODEL_ID);
        when(ms.createList(eq("DesignField"), any())).thenReturn(List.of(101L));

        new DesignEnvCloner(ms).replaceEnvDesign(TGT_ENV, snapshot);

        // Current design wiped — one delete per meta-table (children before parents).
        verify(ms, times(5)).deleteByFilters(any(), any());
        // Model re-materialized: source id DROPPED (pipeline mints fresh — flag-independent).
        ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
        verify(ms).createOne(eq("DesignModel"), model.capture());
        assertFalse(model.getValue().containsKey("id"), "source id dropped — pipeline mints fresh (not id-preserving)");
        assertEquals("Customer", model.getValue().get("modelName"), "model re-materialized by business key");
        // Field re-parented onto the NEW model id (FK remap), not the snapshot's old parent id.
        ArgumentCaptor<List<Map<String, Object>>> fields = ArgumentCaptor.forClass(List.class);
        verify(ms).createList(eq("DesignField"), fields.capture());
        assertEquals(NEW_MODEL_ID, fields.getValue().getFirst().get("modelId"), "child FK remapped to new parent id");
        // Empty meta-tables insert nothing.
        verify(ms, never()).createOne(eq("DesignOptionSet"), any());
        verify(ms, never()).createList(eq("DesignModelIndex"), any());
        verify(ms, never()).createList(eq("DesignOptionItem"), any());
    }
}
