package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.entity.DesignOptionSet;
import io.softa.starter.studio.release.dto.DesignMetaTables;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.dto.RowChangeOp;

/**
 * The unified differ ({@link DesignAggregateDiffer#diff}): rows pair purely by their
 * <b>business key</b> (model=modelName; field=modelName+fieldName) — there is no logicalId surrogate.
 * A field/optionItem rename is surfaced as an UPDATE carrying the old name in
 * {@code previousValuesForChangedFields} (not drop+add), bridged by {@code renamedFrom}; the per-env
 * surrogate {@code id} is never a compared attr; and a no-op when DESIRED matches OBSERVED.
 */
class DesignAggregateDifferTest {

    private final DesignAggregateDiffer differ = new DesignAggregateDiffer();

    /** A model row keyed by modelName. The surrogate {@code id} is intentionally distinct to prove it is ignored. */
    private static Map<String, Object> model(long id, String name, String table) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("modelName", name);
        m.put("tableName", table);
        m.put("label", name);
        return m;
    }

    /** A field row keyed by modelName+fieldName. The surrogate {@code id} is intentionally distinct to prove it is ignored. */
    private static Map<String, Object> field(long id, String modelName, String fieldName, String columnName) {
        Map<String, Object> f = new HashMap<>();
        f.put("id", id);
        f.put("modelName", modelName);
        f.put("fieldName", fieldName);
        f.put("columnName", columnName);
        f.put("fieldType", "STRING");
        return f;
    }

    private static DesignRows rows(List<Map<String, Object>> models,
                                                          List<Map<String, Object>> fields) {
        return new DesignRows(models, fields, List.of(), List.of(), List.of());
    }

    /** An option set keyed by optionSetCode. The surrogate {@code id} is intentionally distinct to prove it is ignored. */
    private static Map<String, Object> optionSet(long id, String code, String label) {
        Map<String, Object> s = new HashMap<>();
        s.put("id", id);
        s.put("optionSetCode", code);
        s.put("label", label);
        return s;
    }

    /** An option item keyed by optionSetCode+itemCode. The surrogate {@code id} is intentionally distinct to prove it is ignored. */
    private static Map<String, Object> optionItem(long id, String setCode, String itemCode, String label) {
        Map<String, Object> it = new HashMap<>();
        it.put("id", id);
        it.put("optionSetCode", setCode);
        it.put("itemCode", itemCode);
        it.put("label", label);
        return it;
    }

    private static DesignRows optionRows(List<Map<String, Object>> optionSets, List<Map<String, Object>> items) {
        return new DesignRows(List.of(), List.of(), List.of(), optionSets, items);
    }

    /** Regroup the flat row-change list per design meta-model, then pick one table's bucket. */
    private static ModelChangesDTO byModel(List<RowChangeDTO> all, String model) {
        return DesignMetaTables.group(all).stream()
                .filter(c -> c.getModelName().equals(model)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("business-key pairing: create / delete / rename-update; the surrogate id is not a change")
    void diffsByBusinessKey() {
        // DESIRED: Customer(unchanged) + Order(new); Customer.code's column changes code→cust_code (field name
        // unchanged — same business key Customer.code, so it pairs by business key and the column-change is an UPDATE).
        DesignRows desired = rows(
                List.of(model(1, "Customer", "customer"), model(2, "Order", "orders")),
                List.of(field(11, "Customer", "code", "cust_code"), field(12, "Customer", "name", "name")));
        // OBSERVED (its runtime, carrying its OWN surrogate ids): Customer + Legacy; code's column still "code".
        DesignRows observed = rows(
                List.of(model(900, "Customer", "customer"), model(903, "Legacy", "legacy")),
                List.of(field(990, "Customer", "code", "code"), field(991, "Customer", "name", "name")));

        List<RowChangeDTO> changes = differ.diff(desired, observed);

        ModelChangesDTO models = byModel(changes, DesignModel.class.getSimpleName());
        assertEquals(1, models.getCreatedRows().size());
        assertEquals("Order", models.getCreatedRows().getFirst().getFullRow().get("modelName"));  // keyed by modelName
        assertEquals(1, models.getDeletedRows().size());
        assertEquals("Legacy", models.getDeletedRows().getFirst().getFullRow().get("modelName"));
        // Customer's surrogate id differs (1 vs 900) but is not a business attr → no spurious UPDATE.
        assertTrue(models.getUpdatedRows().isEmpty());

        ModelChangesDTO fields = byModel(changes, DesignField.class.getSimpleName());
        assertEquals(1, fields.getUpdatedRows().size());
        RowChangeDTO change = fields.getUpdatedRows().getFirst();
        assertEquals("Customer.code", change.getFullRow().get("modelName") + "." + change.getFullRow().get("fieldName"));
        assertEquals(RowChangeOp.UPDATE, change.getOp());
        // Same business key (Customer.code), changed columnName → UPDATE carrying the OLD column (→ CHANGE COLUMN).
        assertEquals("code", change.getPreviousValuesForChangedFields().get("columnName"));
        assertEquals("cust_code", change.getFullRow().get("columnName"));
        assertEquals(List.of("columnName"), List.copyOf(change.getPreviousValuesForChangedFields().keySet()));
    }

    @Test
    @DisplayName("#4: a field rename pairs via renamedFrom — UPDATE, not drop+add")
    void bridgesRenameViaRenamedFrom() {
        // DESIRED: ModelA + field renamed code→partnerCode; design carries renamedFrom="code" (captured 2a).
        Map<String, Object> renamed = field(11, "ModelA", "partnerCode", "partner_code");
        renamed.put("renamedFrom", "code");
        DesignRows desired = rows(List.of(model(1, "ModelA", "model_a")), List.of(renamed));
        // OBSERVED runtime: ModelA + field still "code". Exact business key (ModelA.partnerCode) misses;
        // ONLY the renamedFrom bridge (ModelA.code) can pair.
        DesignRows observed = rows(
                List.of(model(900, "ModelA", "model_a")),
                List.of(field(990, "ModelA", "code", "code")));

        List<RowChangeDTO> changes = differ.diff(desired, observed);

        ModelChangesDTO fields = byModel(changes, DesignField.class.getSimpleName());
        assertEquals(1, fields.getUpdatedRows().size(), "rename pairs as one UPDATE");
        assertTrue(fields.getCreatedRows().isEmpty(), "no drop+add CREATE (the #4 divorce is closed)");
        assertTrue(fields.getDeletedRows().isEmpty(), "no drop+add DELETE");
        RowChangeDTO update = fields.getUpdatedRows().getFirst();
        assertEquals("partnerCode", update.getFullRow().get("fieldName"));
        assertEquals("code", update.getRenamedFrom());                                 // carried to the wire
        assertEquals("code", update.getPreviousValuesForChangedFields().get("fieldName"));  // old name → CHANGE COLUMN
    }

    @Test
    @DisplayName("renamedFrom bridge is order-independent — a new row reusing the renamed-FROM name keeps its exact match")
    void bridgeIsSubordinateToAllExactMatches() {
        // ModelA stable. DESIRED: field renamed code→partnerCode (renamedFrom=code) AND a SEPARATE new field
        // that happens to be named "code". OBSERVED: a field still named "code". Correct (and
        // order-independent): observed "code" pairs EXACTLY with the new "code" (adoption); partnerCode is a
        // fresh CREATE — the bridge must NOT steal observed "code" as a rename regardless of desired order.
        Map<String, Object> renamed = field(50, "ModelA", "partnerCode", "partner_code");
        renamed.put("renamedFrom", "code");
        Map<String, Object> freshCode = field(51, "ModelA", "code", "code");
        DesignRows observed = rows(
                List.of(model(900, "ModelA", "model_a")),
                List.of(field(990, "ModelA", "code", "code")));

        for (List<Map<String, Object>> order : List.of(List.of(renamed, freshCode), List.of(freshCode, renamed))) {
            DesignRows desired = rows(List.of(model(1, "ModelA", "model_a")), order);
            ModelChangesDTO fields = byModel(differ.diff(desired, observed), DesignField.class.getSimpleName());
            assertEquals(1, fields.getCreatedRows().size(), "partnerCode is a fresh CREATE");
            assertEquals("partnerCode", fields.getCreatedRows().getFirst().getFullRow().get("fieldName"));
            // The new 'code' exactly matches observed 'code' (identical content) → consumed as a no-op, NOT
            // an UPDATE (no logicalId to converge anymore) and NOT renamed by the bridge.
            assertTrue(fields.getUpdatedRows().isEmpty(), "exact-match 'code' is identical → no-op, not an update");
            assertTrue(fields.getDeletedRows().isEmpty(), "observed code consumed by exact match, not deleted");
        }
    }

    @Test
    @DisplayName("empty observed (first publish) → every desired row is a CREATE")
    void firstPublishIsAllCreates() {
        DesignRows desired = rows(
                List.of(model(1, "Customer", "customer")),
                List.of(field(11, "Customer", "code", "code")));

        List<RowChangeDTO> changes = differ.diff(desired, rows(List.of(), List.of()));

        assertEquals(1, byModel(changes, DesignModel.class.getSimpleName()).getCreatedRows().size());
        RowChangeDTO created = byModel(changes, DesignModel.class.getSimpleName()).getCreatedRows().getFirst();
        assertEquals(RowChangeOp.CREATE, created.getOp());
        assertEquals("customer", created.getFullRow().get("tableName"));
        assertEquals(1, byModel(changes, DesignField.class.getSimpleName()).getCreatedRows().size());
    }

    @Test
    @DisplayName("business-key pairing: a desired row with a changed attribute pairs the observed row → UPDATE, not create+delete")
    void businessKeyPairsRowWithChangedAttr() {
        // The Customer model is present on both sides (identical) so the field is not an excluded orphan.
        // The field has the same business key (Customer.code) on both sides but a changed columnName.
        DesignRows desired = rows(
                List.of(model(1, "Customer", "customer")),
                List.of(field(11, "Customer", "code", "cust_code")));
        DesignRows observed = rows(
                List.of(model(1, "Customer", "customer")),
                List.of(field(990, "Customer", "code", "code")));

        ModelChangesDTO fields = byModel(differ.diff(desired, observed), DesignField.class.getSimpleName());

        assertTrue(fields.getCreatedRows().isEmpty(), "must pair by business key, not create a duplicate");
        assertTrue(fields.getDeletedRows().isEmpty(), "the observed row is matched, not deleted");
        assertEquals(1, fields.getUpdatedRows().size());
        assertEquals("cust_code", fields.getUpdatedRows().getFirst().getFullRow().get("columnName"));
    }

    @Test
    @DisplayName("same business key, identical content (different surrogate id) → no-op, not create+delete")
    void sameBusinessKeyIdenticalContentIsNoOp() {
        // DEV and TEST each independently added ModelA.state → identical business content, different surrogate
        // ids. The business-key pairing must treat them as the same row → no change (NOT create+delete, which
        // would collide on the env's UNIQUE(business key)). There is no logicalId to converge anymore.
        DesignRows desired = rows(
                List.of(model(1, "ModelA", "model_a")),
                List.of(field(50, "ModelA", "state", "state")));     // source surrogate id 50
        DesignRows observed = rows(
                List.of(model(1, "ModelA", "model_a")),
                List.of(field(70, "ModelA", "state", "state")));     // target surrogate id 70, same content

        // Identical on both sides (model + field) → the diff is empty: no create/delete (which would collide
        // on UNIQUE(env_id, business key)) and no update (surrogate id excluded; no logicalId to converge).
        assertTrue(differ.diff(desired, observed).isEmpty(),
                "same business key + identical content → no changes at all");
    }

    @Test
    @DisplayName("orphan child (parent model absent) is excluded — matches the gate, never publishes a parentless field")
    void orphanChildWithAbsentParentIsExcluded() {
        // A field whose model row is absent (e.g. the model was deleted without cascading to its fields).
        // The R5 checksum gate cannot see it (no parent aggregate to hash it under), so the differ MUST
        // agree and emit no change for it — otherwise the gate's inSync short-circuit would silently drop
        // a publish. (Publishing a field for a non-existent model would also create a runtime orphan.)
        DesignRows desired = rows(
                List.of(model(1, "Customer", "customer")),
                List.of(field(11, "Customer", "code", "code"),
                        field(99, "Ghost", "email", "email")));   // orphan — no "Ghost" model
        DesignRows observed = rows(
                List.of(model(1, "Customer", "customer")),
                List.of(field(11, "Customer", "code", "code")));

        assertTrue(differ.diff(desired, observed).isEmpty(),
                "the orphan 'Ghost.email' must not surface as a change");
    }

    @Test
    @DisplayName("DESIRED already matches OBSERVED → empty diff (no-op)")
    void inSyncProducesNoChanges() {
        DesignRows desired = rows(
                List.of(model(1, "Customer", "customer")),
                List.of(field(11, "Customer", "code", "code")));
        DesignRows observed = rows(
                List.of(model(900, "Customer", "customer")),
                List.of(field(990, "Customer", "code", "code")));

        assertTrue(differ.diff(desired, observed).isEmpty());
    }

    @Test
    @DisplayName("import direction: an OBSERVED row's own renamedFrom bridges to the DESIRED current key — UPDATE, not drop+add")
    void bridgesObservedRenameViaRenamedFrom() {
        // The import half of the two-pass matcher (obsByOldKey / byObservedRename): DESIRED (studio design)
        // still holds ModelA.code; OBSERVED (the runtime/source) already renamed it to partnerCode, carrying
        // renamedFrom="code". Every other rename test drives the DEPLOY direction (desired carries
        // renamedFrom); this pins the mirror path so an import rename pairs in place instead of divorcing
        // into drop(code)+add(partnerCode).
        DesignRows desired = rows(
                List.of(model(1, "ModelA", "model_a")),
                List.of(field(11, "ModelA", "code", "code")));
        Map<String, Object> observedRenamed = field(990, "ModelA", "partnerCode", "partner_code");
        observedRenamed.put("renamedFrom", "code");   // the OBSERVED side carries the prior name
        DesignRows observed = rows(List.of(model(900, "ModelA", "model_a")), List.of(observedRenamed));

        ModelChangesDTO fields = byModel(differ.diff(desired, observed), DesignField.class.getSimpleName());

        assertEquals(1, fields.getUpdatedRows().size(), "observed-rename bridges to one UPDATE");
        assertTrue(fields.getCreatedRows().isEmpty(), "no drop+add CREATE");
        assertTrue(fields.getDeletedRows().isEmpty(), "no drop+add DELETE");
        RowChangeDTO update = fields.getUpdatedRows().getFirst();
        assertEquals("code", update.getFullRow().get("fieldName"));   // desired current key
        // The matched OBSERVED (renamed) value — proves the pairing came through obsByOldKey, not an exact key.
        assertEquals("partnerCode", update.getPreviousValuesForChangedFields().get("fieldName"));
    }

    @Test
    @DisplayName("option sets/items diff by business key: set add/delete + optionItem add/delete/rename-bridge")
    void diffsOptionSetsAndItems() {
        // Parent set Tier is present on BOTH sides so its items are diffed (not orphan-excluded).
        // DESIRED: Tier + Priority(new); items Tier.premium (renamedFrom=gold) + Tier.silver(new).
        // OBSERVED: Tier + Legacy(gone); items Tier.gold + Tier.bronze(gone).
        Map<String, Object> renamedItem = optionItem(31, "Tier", "premium", "Premium");
        renamedItem.put("renamedFrom", "gold");
        DesignRows desired = optionRows(
                List.of(optionSet(1, "Tier", "Tier"), optionSet(2, "Priority", "Priority")),
                List.of(renamedItem, optionItem(32, "Tier", "silver", "Silver")));
        DesignRows observed = optionRows(
                List.of(optionSet(900, "Tier", "Tier"), optionSet(903, "Legacy", "Legacy")),
                List.of(optionItem(990, "Tier", "gold", "Premium"), optionItem(991, "Tier", "bronze", "Bronze")));

        List<RowChangeDTO> changes = differ.diff(desired, observed);

        ModelChangesDTO sets = byModel(changes, DesignOptionSet.class.getSimpleName());
        assertEquals(1, sets.getCreatedRows().size());
        assertEquals("Priority", sets.getCreatedRows().getFirst().getFullRow().get("optionSetCode"));
        assertEquals(1, sets.getDeletedRows().size());
        assertEquals("Legacy", sets.getDeletedRows().getFirst().getFullRow().get("optionSetCode"));
        assertTrue(sets.getUpdatedRows().isEmpty(), "Tier is identical → no set update");

        ModelChangesDTO items = byModel(changes, DesignOptionItem.class.getSimpleName());
        assertEquals(1, items.getCreatedRows().size());
        assertEquals("silver", items.getCreatedRows().getFirst().getFullRow().get("itemCode"));
        assertEquals(1, items.getDeletedRows().size());
        assertEquals("bronze", items.getDeletedRows().getFirst().getFullRow().get("itemCode"));
        // premium bridges observed gold via renamedFrom (ITEM_CODE is bridge-enabled) → UPDATE, not drop+add.
        assertEquals(1, items.getUpdatedRows().size());
        RowChangeDTO renamed = items.getUpdatedRows().getFirst();
        assertEquals("premium", renamed.getFullRow().get("itemCode"));
        assertEquals("gold", renamed.getRenamedFrom());
        assertEquals("gold", renamed.getPreviousValuesForChangedFields().get("itemCode"));
    }
}
