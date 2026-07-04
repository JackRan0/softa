package io.softa.starter.metadata.checksum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure two-level Merkle aggregate checksum (Phase 0).
 * These pin the invariants the whole desired-state deploy relies on: determinism,
 * child-order independence, exclusion of non-schema attrs, null/absent equivalence,
 * and that every schema-relevant change (including an index change) moves the hash.
 */
class AggregateChecksumTest {

    private static Map<String, Object> field(String name, String type, Object length) {
        Map<String, Object> f = new HashMap<>();
        f.put("fieldName", name);
        f.put("columnName", name);
        f.put("modelName", "Customer");
        f.put("fieldType", type);
        f.put("length", length);
        f.put("required", Boolean.TRUE);
        return f;
    }

    private static Map<String, Object> model() {
        Map<String, Object> m = new HashMap<>();
        m.put("modelName", "Customer");
        m.put("label", "Customer");
        m.put("tableName", "customer");
        m.put("businessKey", List.of("code"));
        return m;
    }

    private static Map<String, Object> index(String name, List<String> fields, Boolean unique) {
        Map<String, Object> i = new HashMap<>();
        i.put("indexName", name);
        i.put("indexFields", fields);
        i.put("uniqueIndex", unique);
        return i;
    }

    @Test
    @DisplayName("deterministic: identical input → identical hash")
    void deterministic() {
        List<Map<String, Object>> fields = List.of(field("name", "STRING", 100), field("age", "INTEGER", null));
        List<Map<String, Object>> idx = List.of(index("uk_code", List.of("code"), true));
        assertEquals(AggregateChecksum.ofModel(model(), fields, idx),
                AggregateChecksum.ofModel(model(), fields, idx));
    }

    @Test
    @DisplayName("child order does not affect the aggregate hash")
    void childOrderIndependent() {
        Map<String, Object> a = field("a", "STRING", 64);
        Map<String, Object> b = field("b", "STRING", 64);
        String h1 = AggregateChecksum.ofModel(model(), List.of(a, b), List.of());
        String h2 = AggregateChecksum.ofModel(model(), List.of(b, a), List.of());
        assertEquals(h1, h2);
    }

    @Test
    @DisplayName("excluded attrs (id / ownership / audit) never change the hash")
    void excludedAttrsIgnored() {
        Map<String, Object> base = model();
        Map<String, Object> withNoise = model();
        withNoise.put("id", 12345L);
        withNoise.put("ownership", "PLATFORM_MAINTAINED");
        withNoise.put("appCode", "demo-app");
        withNoise.put("createdTime", "2026-06-17T00:00:00");
        withNoise.put("aggregateChecksum", "deadbeef");
        assertEquals(AggregateChecksum.ofModel(base, List.of(), List.of()),
                AggregateChecksum.ofModel(withNoise, List.of(), List.of()));
    }

    @Test
    @DisplayName("a key attribute change moves the hash")
    void keyAttrChangeMovesHash() {
        Map<String, Object> changed = model();
        changed.put("tableName", "customer_v2");
        assertNotEquals(AggregateChecksum.ofModel(model(), List.of(), List.of()),
                AggregateChecksum.ofModel(changed, List.of(), List.of()));
    }

    @Test
    @DisplayName("an index change moves the Model aggregate hash (refinement #2)")
    void indexChangeMovesModelHash() {
        String before = AggregateChecksum.ofModel(model(), List.of(),
                List.of(index("uk_code", List.of("code"), true)));
        String afterUnique = AggregateChecksum.ofModel(model(), List.of(),
                List.of(index("uk_code", List.of("code"), false)));
        String afterAdded = AggregateChecksum.ofModel(model(), List.of(),
                List.of(index("uk_code", List.of("code"), true), index("idx_name", List.of("name"), false)));
        assertNotEquals(before, afterUnique);
        assertNotEquals(before, afterAdded);
    }

    @Test
    @DisplayName("null and absent attribute hash identically (cross-side stability)")
    void nullEqualsAbsent() {
        Map<String, Object> absent = new HashMap<>();
        absent.put("optionSetCode", "tier");
        absent.put("label", "Tier");
        Map<String, Object> explicitNull = new HashMap<>(absent);
        explicitNull.put("description", null);
        explicitNull.put("active", null);
        assertEquals(AggregateChecksum.ofOptionSet(absent, List.of()),
                AggregateChecksum.ofOptionSet(explicitNull, List.of()));
    }

    @Test
    @DisplayName("type-tagging: string \"1\" never collides with number 1")
    void typeTagsPreventCollision() {
        Map<String, Object> asString = new HashMap<>();
        asString.put("fieldName", "x");
        asString.put("length", "64");
        Map<String, Object> asNumber = new HashMap<>(asString);
        asNumber.put("length", 64);
        assertNotEquals(AggregateChecksum.ofChild(asString, AggregateChecksum.FIELD_ATTRS),
                AggregateChecksum.ofChild(asNumber, AggregateChecksum.FIELD_ATTRS));
    }

    @Test
    @DisplayName("optionset item change moves the set aggregate hash")
    void itemChangeMovesSetHash() {
        Map<String, Object> set = new HashMap<>();
        set.put("optionSetCode", "tier");
        Map<String, Object> gold = new HashMap<>();
        gold.put("optionSetCode", "tier");
        gold.put("itemCode", "g");
        gold.put("label", "Gold");
        List<Map<String, Object>> oneItem = new ArrayList<>(List.of(gold));
        String before = AggregateChecksum.ofOptionSet(set, oneItem);

        Map<String, Object> silver = new HashMap<>();
        silver.put("optionSetCode", "tier");
        silver.put("itemCode", "s");
        silver.put("label", "Silver");
        oneItem.add(silver);
        assertNotEquals(before, AggregateChecksum.ofOptionSet(set, oneItem));
    }
}
