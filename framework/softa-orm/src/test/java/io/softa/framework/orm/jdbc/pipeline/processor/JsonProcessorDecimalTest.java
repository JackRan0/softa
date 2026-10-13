package io.softa.framework.orm.jdbc.pipeline.processor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JSON column keeps the scale of its amounts on the way out: {@code 502.00} in the column is {@code 502.00} in the tree, not the double {@code 502.0}.
 * The processor reads through {@code JsonUtils}, whose mapper carries the policy whether or not an application context is up.
 */
class JsonProcessorDecimalTest {

    private static final String MODEL = "Order";
    private static final String FIELD = "summary";

    private static MetaField field() {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", FIELD);
        ReflectionTestUtils.setField(metaField, "columnName", FIELD);
        ReflectionTestUtils.setField(metaField, "label", "Summary");
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.JSON);
        return metaField;
    }

    /** Reading a JSON column turns a nested amount into a BigDecimal with its scale and leaves integers integral. */
    @Test
    void readsAmountsAsBigDecimalWithTheirScale() {
        Map<String, Object> row = new HashMap<>();
        row.put(FIELD, "{\"total\":{\"amount\":502.00},\"count\":30}");

        new JsonProcessor(field(), AccessType.READ).processOutputRow(row);

        JsonNode content = (JsonNode) row.get(FIELD);
        assertTrue(content.get("total").get("amount").isBigDecimal());
        assertEquals(new BigDecimal("502.00"), content.get("total").get("amount").decimalValue());
        assertTrue(content.get("count").isIntegralNumber());
    }
}
