package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DesignRows#select} restricts a catalog to whole aggregates by business key —
 * roots by their own key, children by their parent key — so a design and runtime catalog restrict
 * identically and a selected aggregate is never split.
 */
class DesignRowsTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("keeps named model aggregates (root + children by parent key), drops the rest")
    void selectKeepsWholeModelAggregates() {
        DesignRows all = new DesignRows(
                List.of(row("modelName", "Keep"), row("modelName", "Drop")),
                List.of(row("modelName", "Keep", "fieldName", "a"),
                        row("modelName", "Drop", "fieldName", "b")),
                List.of(row("modelName", "Keep", "indexName", "ix"),
                        row("modelName", "Drop", "indexName", "ix2")),
                List.of(), List.of());

        DesignRows kept = all.select(Set.of("Keep"), Set.of());

        assertEquals(1, kept.models().size());
        assertEquals("Keep", kept.models().getFirst().get("modelName"));
        assertEquals(1, kept.fields().size());
        assertEquals("a", kept.fields().getFirst().get("fieldName"));
        assertEquals(1, kept.indexes().size());
        assertTrue(kept.optionSets().isEmpty());
    }

    @Test
    @DisplayName("keeps named option-set aggregates by optionSetCode; an unmatched key keeps nothing")
    void selectKeepsOptionSetAggregatesAndDropsUnmatched() {
        DesignRows all = new DesignRows(
                List.of(row("modelName", "M")), List.of(), List.of(),
                List.of(row("optionSetCode", "status"), row("optionSetCode", "tier")),
                List.of(row("optionSetCode", "status", "itemCode", "open"),
                        row("optionSetCode", "tier", "itemCode", "gold")));

        DesignRows kept = all.select(Set.of(), Set.of("status"));

        assertTrue(kept.models().isEmpty(), "no model key requested → no models");
        assertEquals(1, kept.optionSets().size());
        assertEquals("status", kept.optionSets().getFirst().get("optionSetCode"));
        assertEquals(1, kept.items().size());
        assertEquals("open", kept.items().getFirst().get("itemCode"));
    }

    @Test
    @DisplayName("empty() is an all-empty catalog")
    void emptyIsAllEmpty() {
        DesignRows empty = DesignRows.empty();
        assertTrue(empty.models().isEmpty() && empty.fields().isEmpty() && empty.indexes().isEmpty()
                && empty.optionSets().isEmpty() && empty.items().isEmpty());
    }
}
