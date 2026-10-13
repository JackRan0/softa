package io.softa.framework.orm.jdbc.pipeline.processor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Double field stores a double whatever numeric type the request carried.
 * JSON floats arrive as BigDecimal (see JacksonConfig);
 * left as BigDecimal, an unchanged value would not equal the Double read back from the column, and the update diff would record a change that did not happen.
 */
class NumericProcessorDoubleInputTest {

    private static final String MODEL = "Order";
    private static final String FIELD = "rate";

    private static MetaField field(FieldType fieldType) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", FIELD);
        ReflectionTestUtils.setField(metaField, "columnName", FIELD);
        ReflectionTestUtils.setField(metaField, "label", "Rate");
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        return metaField;
    }

    private static Object write(FieldType fieldType, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put(FIELD, value);
        new NumericProcessor(field(fieldType), AccessType.UPDATE).processInputRow(row);
        return row.get(FIELD);
    }

    /** A BigDecimal written to a Double field becomes the Double the column reads back, so an unchanged value stays equal. */
    @Test
    void decimalIntoDoubleField_becomesTheDoubleReadBack() {
        assertEquals(1.5, write(FieldType.DOUBLE, new BigDecimal("1.5")));
    }

    /** A JSON number without a decimal point arrives as Integer or Long; written to a Double field it becomes Double. */
    @Test
    void integralIntoDoubleField_becomesDouble() {
        assertEquals(2.0, write(FieldType.DOUBLE, 2));
        assertEquals(2.0, write(FieldType.DOUBLE, 2L));
    }

    /** A BigDecimal field is not converted: the value keeps its type and its scale. */
    @Test
    void decimalIntoBigDecimalField_keepsItsScale() {
        assertEquals(new BigDecimal("502.00"), write(FieldType.BIG_DECIMAL, new BigDecimal("502.00")));
    }
}
