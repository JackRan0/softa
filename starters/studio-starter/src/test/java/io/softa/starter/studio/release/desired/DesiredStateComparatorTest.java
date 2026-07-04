package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.studio.release.connector.Connector;

/**
 * Phase 3: the studio compare orchestration reads runtime checksums once (via the {@link Connector})
 * and classifies each aggregate, so the deploy knows what to CREATE / OVERWRITE / DELETE / skip. Uses a
 * mocked {@link Connector} (the runtime side is exercised separately by the metadata-starter test).
 */
class DesiredStateComparatorTest {

    private static Map<String, Object> model(Object id, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("modelName", name);
        m.put("tableName", name.toLowerCase());
        return m;
    }

    private static Map<String, Object> designField(Object id, Object modelId, String modelName, String fieldName) {
        Map<String, Object> f = new HashMap<>();
        f.put("id", id);
        f.put("modelId", modelId);
        f.put("modelName", modelName);
        f.put("fieldName", fieldName);
        f.put("columnName", fieldName);
        f.put("fieldType", "STRING");
        return f;
    }

    @Test
    @DisplayName("classifies create / delete / identical from a single runtime fetch")
    void comparesAgainstRuntimeChecksums() {
        // Design has Customer (matches runtime) + Invoice (new). Runtime has Customer + Order.
        DesignRows design = new DesignRows(
                List.of(model(1L, "Customer"), model(2L, "Invoice")),
                List.of(designField(10L, 1L, "Customer", "code"), designField(20L, 2L, "Invoice", "amount")),
                List.of(), List.of(), List.of());

        // The runtime's "Customer" checksum is, by construction, equal to the design's — so it is identical.
        String customerChecksum = AggregateChecksumDiff.modelChecksums(
                List.of(model(1L, "Customer")), List.of(designField(10L, 1L, "Customer", "code")),
                List.of()).get("Customer");
        RuntimeChecksumsDTO runtime = new RuntimeChecksumsDTO(
                Map.of("Customer", customerChecksum, "Order", "runtime-only-hash"), Map.of());

        Connector connector = mock(Connector.class);
        when(connector.readChecksums("demo-app")).thenReturn(runtime);

        DesiredStateComparator.Result result = new DesiredStateComparator().compare(connector, "demo-app", design);

        assertEquals(Set.of("Invoice"), result.models().onlyInDesign());     // deploy: CREATE
        assertEquals(Set.of("Order"), result.models().onlyInRuntime());      // deploy: DELETE (converge)
        assertEquals(Set.of("Customer"), result.models().identical());       // skip
        assertFalse(result.inSync());
    }
}
