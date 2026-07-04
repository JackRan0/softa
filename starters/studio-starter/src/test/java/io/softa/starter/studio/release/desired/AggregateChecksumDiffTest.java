package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R5 soundness: the aggregate-level desired-state
 * classification. Both lanes link aggregate children by the business key ({@code modelName} /
 * {@code optionSetCode}) — the same key {@link DesignAggregateDiffer} pairs rows by — so the gate and
 * the row differ assemble identical aggregates, and the studio's surrogate {@code modelId} (which a
 * design row may still carry) is ignored. Proves: identical state across lanes is skipped; a field
 * change marks only its aggregate; create/delete are classified at the business-aggregate grain; and
 * (the R5 regression) a design child with a null/dangling {@code modelId} is still counted by its
 * {@code modelName} — never silently dropped from the checksum.
 */
class AggregateChecksumDiffTest {

    private static Map<String, Object> model(Object id, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("modelName", name);
        m.put("tableName", name.toLowerCase());
        return m;
    }

    private static Map<String, Object> runtimeField(Object id, String modelName, String fieldName) {
        Map<String, Object> f = new HashMap<>();
        f.put("id", id);
        f.put("modelName", modelName);          // both lanes link children by business code
        f.put("fieldName", fieldName);
        f.put("columnName", fieldName);
        f.put("fieldType", "STRING");
        f.put("required", Boolean.TRUE);
        return f;
    }

    /** A design field: same business attrs as a runtime field, plus a surrogate {@code modelId} (ignored). */
    private static Map<String, Object> designField(Object id, Object modelId, String modelName, String fieldName) {
        Map<String, Object> f = runtimeField(id, modelName, fieldName);
        f.put("modelId", modelId);              // surrogate FK — NOT a checksum attr, not the link key
        return f;
    }

    // Runtime side: Customer{code,name}, Order{no}
    private Map<String, String> runtimeModels() {
        return AggregateChecksumDiff.modelChecksums(
                List.of(model(1L, "Customer"), model(2L, "Order")),
                List.of(runtimeField(10L, "Customer", "code"), runtimeField(11L, "Customer", "name"),
                        runtimeField(12L, "Order", "no")),
                List.of());
    }

    // Studio side: same logical models, different surrogate ids — and a surrogate modelId on children.
    private Map<String, String> designModels() {
        return AggregateChecksumDiff.modelChecksums(
                List.of(model(100L, "Customer"), model(200L, "Order")),
                List.of(designField(1000L, 100L, "Customer", "code"), designField(1001L, 100L, "Customer", "name"),
                        designField(2000L, 200L, "Order", "no")),
                List.of());
    }

    @Test
    @DisplayName("identical logical state across lanes → all unchanged, nothing to deploy (surrogate modelId ignored)")
    void identicalAcrossLanesIsUnchanged() {
        AggregateChecksumDiff.Delta d = AggregateChecksumDiff.diff(designModels(), runtimeModels());
        assertTrue(d.inSync(), "no aggregate should be classified changed");
        assertEquals(Set.of("Customer", "Order"), d.identical());
    }

    @Test
    @DisplayName("a field change moves only that aggregate to 'updated'")
    void fieldChangeMarksOneAggregateUpdated() {
        // design Customer gains an extra field
        Map<String, String> design = AggregateChecksumDiff.modelChecksums(
                List.of(model(100L, "Customer"), model(200L, "Order")),
                List.of(designField(1000L, 100L, "Customer", "code"), designField(1001L, 100L, "Customer", "name"),
                        designField(1002L, 100L, "Customer", "email"), designField(2000L, 200L, "Order", "no")),
                List.of());
        AggregateChecksumDiff.Delta d = AggregateChecksumDiff.diff(design, runtimeModels());
        assertEquals(Set.of("Customer"), d.differing());
        assertEquals(Set.of("Order"), d.identical());
        assertTrue(d.onlyInDesign().isEmpty() && d.onlyInRuntime().isEmpty());
    }

    @Test
    @DisplayName("design-only → onlyInDesign (deploy CREATE); runtime-only → onlyInRuntime (deploy DELETE to converge)")
    void designOnlyAndRuntimeOnlyClassified() {
        Map<String, String> design = AggregateChecksumDiff.modelChecksums(
                List.of(model(100L, "Customer"), model(300L, "Invoice")),
                List.of(designField(1000L, 100L, "Customer", "code"), designField(1001L, 100L, "Customer", "name"),
                        designField(3000L, 300L, "Invoice", "amount")),
                List.of());
        AggregateChecksumDiff.Delta d = AggregateChecksumDiff.diff(design, runtimeModels());
        assertEquals(Set.of("Invoice"), d.onlyInDesign());   // deploy: CREATE on runtime
        assertEquals(Set.of("Order"), d.onlyInRuntime());    // deploy: DELETE (studio removed it) — converges; import: new-to-studio
        assertEquals(Set.of("Customer"), d.identical());     // skip
        assertTrue(d.differing().isEmpty());
    }

    @Test
    @DisplayName("R5 regression: a design field with null modelId is still counted by its modelName")
    void orphanModelIdFieldIsCountedByModelName() {
        // The critical hole the R5 gate had: the design side linked children by surrogate modelId, and a
        // null/dangling modelId dropped the field from the checksum — while DesignAggregateDiffer keys it
        // by modelName.fieldName and would still emit a CREATE. Gate said "identical", a real field was
        // silently dropped. With business-key linking the field is counted, so the gate sees the drift.
        Map<String, String> design = AggregateChecksumDiff.modelChecksums(
                List.of(model(100L, "Customer")),
                // "email" carries NO modelId (e.g. a no-code create that never set the surrogate FK).
                List.of(designField(1000L, 100L, "Customer", "code"), designField(1001L, 100L, "Customer", "name"),
                        designField(1002L, null, "Customer", "email")),
                List.of());
        Map<String, String> runtime = AggregateChecksumDiff.modelChecksums(
                List.of(model(1L, "Customer")),
                List.of(runtimeField(10L, "Customer", "code"), runtimeField(11L, "Customer", "name")),
                List.of());

        AggregateChecksumDiff.Delta d = AggregateChecksumDiff.diff(design, runtime);
        assertFalse(d.inSync(), "the null-modelId 'email' field must change Customer's checksum");
        assertEquals(Set.of("Customer"), d.differing());
    }

    @Test
    @DisplayName("option sets: design (with surrogate optionSetId) vs runtime hash equal for same state")
    void optionSetsCrossLane() {
        Map<String, Object> runtimeSet = new HashMap<>();
        runtimeSet.put("id", 1L);
        runtimeSet.put("optionSetCode", "tier");
        Map<String, Object> runtimeItemG = item(10L, null, "tier", "g", "Gold");
        Map<String, Object> runtimeItemS = item(11L, null, "tier", "s", "Silver");
        Map<String, String> runtime = AggregateChecksumDiff.optionSetChecksums(
                List.of(runtimeSet), List.of(runtimeItemG, runtimeItemS));

        Map<String, Object> designSet = new HashMap<>();
        designSet.put("id", 500L);
        designSet.put("optionSetCode", "tier");
        Map<String, Object> designItemG = item(5000L, 500L, "tier", "g", "Gold");
        Map<String, Object> designItemS = item(5001L, 500L, "tier", "s", "Silver");
        Map<String, String> design = AggregateChecksumDiff.optionSetChecksums(
                List.of(designSet), List.of(designItemG, designItemS));

        assertTrue(AggregateChecksumDiff.diff(design, runtime).inSync());
    }

    private static Map<String, Object> item(Object id, Object setId, String setCode, String code, String label) {
        Map<String, Object> i = new HashMap<>();
        i.put("id", id);
        if (setId != null) {
            i.put("optionSetId", setId);
        }
        i.put("optionSetCode", setCode);
        i.put("itemCode", code);
        i.put("label", label);
        return i;
    }
}
