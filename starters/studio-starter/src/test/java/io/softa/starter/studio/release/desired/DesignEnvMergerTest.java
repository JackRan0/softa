package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.service.ModelService;

/**
 * {@link DesignEnvMerger} converges a TARGET env's design to a SOURCE
 * env's purely by <b>business key</b> (logicalId removed) — source-only aggregates created (with parent
 * FK remapped onto the target parent that shares the source parent's business key), shared-but-changed
 * aggregates updated in place (rename stable via {@code renamedFrom}), target-only aggregates deleted.
 * Children created/updated before parents deleted.
 */
@SuppressWarnings("unchecked")
class DesignEnvMergerTest {

    private static final long APP = 7L;
    private static final long SRC = 100L;
    private static final long TGT = 200L;
    private static final long NEW_ORDER_TARGET_ID = 900L;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static DesignRows rows(List<Map<String, Object>> models,
                                                          List<Map<String, Object>> fields) {
        return new DesignRows(models, fields, List.of(), List.of(), List.of());
    }

    private static DesignRows optionRows(List<Map<String, Object>> optionSets,
                                         List<Map<String, Object>> items) {
        return new DesignRows(List.of(), List.of(), List.of(), optionSets, items);
    }

    @Test
    @DisplayName("merge applies create (FK-remapped) / update (rename) / delete onto the target env by business key")
    void mergesByBusinessKey() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignEnvSource envSource = mock(DesignEnvSource.class);
        DesignAggregateDiffer differ = new DesignAggregateDiffer();

        // SOURCE: Customer (table renamed customer2) + its field code renamed cust_code;
        //         Order (source-only) + its field qty.
        DesignRows source = rows(
                List.of(row("id", 1L, "modelName", "Customer", "label", "Customer", "tableName", "customer2"),
                        row("id", 2L, "modelName", "Order", "label", "Order", "tableName", "orders")),
                List.of(row("id", 11L, "modelId", 1L, "modelName", "Customer", "fieldName", "code", "columnName", "cust_code", "fieldType", "STRING"),
                        row("id", 12L, "modelId", 2L, "modelName", "Order", "fieldName", "qty", "columnName", "qty", "fieldType", "INTEGER")));
        // TARGET (different surrogate ids + modelIds): Customer (table customer) + code ("code");
        //         Legacy (target-only) + its field old.
        DesignRows target = rows(
                List.of(row("id", 201L, "modelName", "Customer", "label", "Customer", "tableName", "customer"),
                        row("id", 203L, "modelName", "Legacy", "label", "Legacy", "tableName", "legacy")),
                List.of(row("id", 211L, "modelId", 201L, "modelName", "Customer", "fieldName", "code", "columnName", "code", "fieldType", "STRING"),
                        row("id", 213L, "modelId", 203L, "modelName", "Legacy", "fieldName", "old", "columnName", "old", "fieldType", "STRING")));

        when(envSource.load(APP, SRC)).thenReturn(source);
        when(envSource.load(APP, TGT)).thenReturn(target);
        when(ms.createOne(eq("DesignModel"), any())).thenReturn(NEW_ORDER_TARGET_ID);
        when(ms.createList(eq("DesignField"), any())).thenReturn(List.of(901L));

        DesignEnvMerger.MergeResult result =
                new DesignEnvMerger(ms, envSource, differ).merge(APP, SRC, TGT, null);

        // Order model + qty field created; Customer model + code field updated; Legacy model + old field deleted.
        assertEquals(2, result.created());
        assertEquals(2, result.updated());
        assertEquals(2, result.deleted());

        // CREATE Order model: id stripped, target env stamped.
        ArgumentCaptor<Map<String, Object>> createdModel = ArgumentCaptor.forClass(Map.class);
        verify(ms).createOne(eq("DesignModel"), createdModel.capture());
        assertFalse(createdModel.getValue().containsKey("id"), "fresh id minted");
        assertEquals("Order", createdModel.getValue().get("modelName"));
        assertEquals(TGT, createdModel.getValue().get("envId"), "stamped onto the target env");

        // CREATE qty field: parent FK remapped onto the Order target id minted just above.
        ArgumentCaptor<List<Map<String, Object>>> createdFields = ArgumentCaptor.forClass(List.class);
        verify(ms).createList(eq("DesignField"), createdFields.capture());
        Map<String, Object> qty = createdFields.getValue().getFirst();
        assertEquals("qty", qty.get("fieldName"));
        assertEquals(NEW_ORDER_TARGET_ID, qty.get("modelId"), "child re-parented to the source parent's target counterpart");

        // UPDATE Customer model in place by its TARGET surrogate id (201), the changed table name. modelId untouched.
        verify(ms).updateOne(eq("DesignModel"), eq(row("id", 201L, "tableName", "customer2")));
        // UPDATE code field rename in place by its TARGET id (211). modelId NOT touched.
        verify(ms).updateOne(eq("DesignField"), eq(row("id", 211L, "columnName", "cust_code")));

        // DELETE target-only Legacy model + its field by TARGET surrogate ids.
        verify(ms).deleteByIds(eq("DesignModel"), eq(List.of(203L)));
        verify(ms).deleteByIds(eq("DesignField"), eq(List.of(213L)));
    }

    @Test
    @DisplayName("selective merge applies only the chosen aggregate roots (with children); unselected create/update/delete skipped")
    void selectiveMergeAppliesOnlySelectedAggregates() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignEnvSource envSource = mock(DesignEnvSource.class);
        DesignAggregateDiffer differ = new DesignAggregateDiffer();

        // Same shape as mergesByBusinessKey: source-only Order (create), shared Customer (rename-update),
        // target-only Legacy (delete).
        DesignRows source = rows(
                List.of(row("id", 1L, "modelName", "Customer", "label", "Customer", "tableName", "customer2"),
                        row("id", 2L, "modelName", "Order", "label", "Order", "tableName", "orders")),
                List.of(row("id", 11L, "modelId", 1L, "modelName", "Customer", "fieldName", "code", "columnName", "cust_code", "fieldType", "STRING"),
                        row("id", 12L, "modelId", 2L, "modelName", "Order", "fieldName", "qty", "columnName", "qty", "fieldType", "INTEGER")));
        DesignRows target = rows(
                List.of(row("id", 201L, "modelName", "Customer", "label", "Customer", "tableName", "customer"),
                        row("id", 203L, "modelName", "Legacy", "label", "Legacy", "tableName", "legacy")),
                List.of(row("id", 211L, "modelId", 201L, "modelName", "Customer", "fieldName", "code", "columnName", "code", "fieldType", "STRING"),
                        row("id", 213L, "modelId", 203L, "modelName", "Legacy", "fieldName", "old", "columnName", "old", "fieldType", "STRING")));

        when(envSource.load(APP, SRC)).thenReturn(source);
        when(envSource.load(APP, TGT)).thenReturn(target);
        when(ms.createOne(eq("DesignModel"), any())).thenReturn(NEW_ORDER_TARGET_ID);
        when(ms.createList(eq("DesignField"), any())).thenReturn(List.of(901L));

        // Select ONLY the Order aggregate (by business key). Customer's rename-UPDATE and Legacy's DELETE are out.
        MergeSelection selection = new MergeSelection(Set.of("Order"), Set.of());
        DesignEnvMerger.MergeResult result =
                new DesignEnvMerger(ms, envSource, differ).merge(APP, SRC, TGT, selection);

        assertEquals(2, result.created());   // Order model + qty field
        assertEquals(0, result.updated());   // Customer rename not selected
        assertEquals(0, result.deleted());   // Legacy delete not selected
        verify(ms).createOne(eq("DesignModel"), any());
        verify(ms).createList(eq("DesignField"), any());
        verify(ms, never()).updateOne(any(), any());
        verify(ms, never()).deleteByIds(any(), any());
        // The recorded change set carries exactly the applied (selected) rows.
        assertEquals(2, result.changes().size());
    }

    @Test
    @DisplayName("a source-only child re-parents by parent modelName onto the target parent (no modelId=null orphan)")
    void mergeReparentsChildByBusinessKey() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignEnvSource envSource = mock(DesignEnvSource.class);
        DesignAggregateDiffer differ = new DesignAggregateDiffer();

        // ModelA exists in BOTH envs with identical business attributes (same business key); the source/target
        // surrogate ids + modelIds differ per env. SOURCE adds a new field `extra` under it. The child must
        // re-parent onto the TARGET ModelA by modelName — never via a per-env surrogate id (which would
        // mismatch source 1 vs target 201 → a modelId=null orphan).
        DesignRows source = rows(
                List.of(row("id", 1L, "modelName", "ModelA", "label", "ModelA", "tableName", "model_a")),
                List.of(row("id", 60L, "modelId", 1L, "modelName", "ModelA", "fieldName", "extra", "columnName", "extra", "fieldType", "STRING")));
        DesignRows target = rows(
                List.of(row("id", 201L, "modelName", "ModelA", "label", "ModelA", "tableName", "model_a")),
                List.of());

        when(envSource.load(APP, SRC)).thenReturn(source);
        when(envSource.load(APP, TGT)).thenReturn(target);
        when(ms.createList(eq("DesignField"), any())).thenReturn(List.of(901L));

        DesignEnvMerger.MergeResult result = new DesignEnvMerger(ms, envSource, differ).merge(APP, SRC, TGT, null);

        // ModelA: identical business attributes on both sides → no UPDATE. `extra`: source-only → CREATE.
        assertEquals(1, result.created());
        assertEquals(0, result.updated());
        assertEquals(0, result.deleted());

        // The `extra` field is re-parented to the TARGET ModelA (id 201) by modelName — NOT via a per-env
        // surrogate id (source 1 vs target 201 → modelId=null orphan, the old bug).
        ArgumentCaptor<List<Map<String, Object>>> createdFields = ArgumentCaptor.forClass(List.class);
        verify(ms).createList(eq("DesignField"), createdFields.capture());
        Map<String, Object> extra = createdFields.getValue().getFirst();
        assertEquals("extra", extra.get("fieldName"));
        assertEquals(201L, ((Number) extra.get("modelId")).longValue(),
                "re-parented to target ModelA by business key — not a null orphan");
    }

    @Test
    @DisplayName("merge locates a renamed field's target row by its OLD business key (renamedFrom)")
    void mergeUpdatesRenamedFieldByOldBusinessKey() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignEnvSource envSource = mock(DesignEnvSource.class);
        DesignAggregateDiffer differ = new DesignAggregateDiffer();

        // SOURCE renamed ModelA.code → ModelA.partnerCode (design carries renamedFrom="code"). TARGET still
        // holds ModelA.code. The merge must locate the target by the OLD key (old-key fallback) and
        // rename it in place by its real surrogate id (270) — never drop+add.
        DesignRows source = rows(
                List.of(row("id", 1L, "modelName", "ModelA", "label", "ModelA", "tableName", "model_a")),
                List.of(row("id", 11L, "modelId", 1L, "modelName", "ModelA", "fieldName", "partnerCode", "columnName", "partner_code", "fieldType", "STRING", "renamedFrom", "code")));
        DesignRows target = rows(
                List.of(row("id", 201L, "modelName", "ModelA", "label", "ModelA", "tableName", "model_a")),
                List.of(row("id", 270L, "modelId", 201L, "modelName", "ModelA", "fieldName", "code", "columnName", "code", "fieldType", "STRING")));

        when(envSource.load(APP, SRC)).thenReturn(source);
        when(envSource.load(APP, TGT)).thenReturn(target);

        DesignEnvMerger.MergeResult result = new DesignEnvMerger(ms, envSource, differ).merge(APP, SRC, TGT, null);

        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        assertEquals(0, result.deleted());
        // Located by the OLD key (ModelA.code) → the target's real id (270); renamed in place to partnerCode.
        verify(ms).updateOne(eq("DesignField"),
                eq(row("id", 270L, "fieldName", "partnerCode", "columnName", "partner_code")));
    }

    @Test
    @DisplayName("merge applies option sets/items: source-only set+item created (optionSetId remapped), item rename located by old key, target-only deleted")
    void mergesOptionSetsAndItemsByBusinessKey() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignEnvSource envSource = mock(DesignEnvSource.class);
        DesignAggregateDiffer differ = new DesignAggregateDiffer();

        // SOURCE: Tier (both) + Priority (source-only → create); items Tier.premium (renamed from gold) +
        //         Priority.high (source-only → create under the new set).
        DesignRows source = optionRows(
                List.of(row("id", 50L, "optionSetCode", "Tier", "label", "Tier"),
                        row("id", 51L, "optionSetCode", "Priority", "label", "Priority")),
                List.of(row("id", 60L, "optionSetId", 50L, "optionSetCode", "Tier", "itemCode", "premium", "label", "Gold", "renamedFrom", "gold"),
                        row("id", 61L, "optionSetId", 51L, "optionSetCode", "Priority", "itemCode", "high", "label", "High")));
        // TARGET (different surrogate ids): Tier + Legacy (target-only → delete); items Tier.gold + Legacy.x.
        DesignRows target = optionRows(
                List.of(row("id", 250L, "optionSetCode", "Tier", "label", "Tier"),
                        row("id", 251L, "optionSetCode", "Legacy", "label", "Legacy")),
                List.of(row("id", 270L, "optionSetId", 250L, "optionSetCode", "Tier", "itemCode", "gold", "label", "Gold"),
                        row("id", 271L, "optionSetId", 251L, "optionSetCode", "Legacy", "itemCode", "x", "label", "X")));

        when(envSource.load(APP, SRC)).thenReturn(source);
        when(envSource.load(APP, TGT)).thenReturn(target);
        when(ms.createOne(eq("DesignOptionSet"), any())).thenReturn(950L);
        when(ms.createList(eq("DesignOptionItem"), any())).thenReturn(List.of(960L));

        DesignEnvMerger.MergeResult result =
                new DesignEnvMerger(ms, envSource, differ).merge(APP, SRC, TGT, null);

        // Priority set + high item created; Tier.gold→premium item renamed (update); Legacy set + Legacy.x deleted.
        assertEquals(2, result.created());
        assertEquals(1, result.updated());
        assertEquals(2, result.deleted());

        // CREATE Priority set: id stripped, target env stamped.
        ArgumentCaptor<Map<String, Object>> createdSet = ArgumentCaptor.forClass(Map.class);
        verify(ms).createOne(eq("DesignOptionSet"), createdSet.capture());
        assertFalse(createdSet.getValue().containsKey("id"), "fresh id minted");
        assertEquals("Priority", createdSet.getValue().get("optionSetCode"));
        assertEquals(TGT, createdSet.getValue().get("envId"));

        // CREATE high item: optionSetId remapped onto the Priority target id minted just above (by optionSetCode).
        ArgumentCaptor<List<Map<String, Object>>> createdItems = ArgumentCaptor.forClass(List.class);
        verify(ms).createList(eq("DesignOptionItem"), createdItems.capture());
        Map<String, Object> high = createdItems.getValue().getFirst();
        assertEquals("high", high.get("itemCode"));
        assertEquals(950L, high.get("optionSetId"), "child re-parented onto the source set's target counterpart");

        // UPDATE the renamed item located by its OLD key (Tier.gold) → the target's real id (270).
        verify(ms).updateOne(eq("DesignOptionItem"), eq(row("id", 270L, "itemCode", "premium")));

        // DELETE target-only Legacy set + its item by TARGET surrogate ids.
        verify(ms).deleteByIds(eq("DesignOptionSet"), eq(List.of(251L)));
        verify(ms).deleteByIds(eq("DesignOptionItem"), eq(List.of(271L)));
    }
}
