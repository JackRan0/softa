package io.softa.starter.metadata.scanner.annotation.inference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link TypeInference}.
 *
 * <p>Covers three tiers of the Java type → FieldType mapping:
 * exact mapping, default-with-override, and must-specify.
 */
class TypeInferenceTest {

    // --- enums + POJOs used as fixtures ---------------------------------

    enum Tier { GOLD, SILVER }

    @Model
    static class FixtureModel {
        // empty body — only the @Model presence matters for relation inference
    }

    // ------- Tier 1: exact ---------------------------------------------

    @Test
    void exact_primitiveAndWrapper() {
        assertEquals(FieldType.INTEGER, TypeInference.infer(Integer.class, null).fieldType());
        assertEquals(FieldType.INTEGER, TypeInference.infer(int.class, null).fieldType());
        assertEquals(FieldType.DOUBLE, TypeInference.infer(Double.class, null).fieldType());
        assertEquals(FieldType.DOUBLE, TypeInference.infer(double.class, null).fieldType());
        assertEquals(FieldType.BIG_DECIMAL, TypeInference.infer(BigDecimal.class, null).fieldType());
        assertEquals(FieldType.BOOLEAN, TypeInference.infer(Boolean.class, null).fieldType());
        assertEquals(FieldType.BOOLEAN, TypeInference.infer(boolean.class, null).fieldType());
        assertEquals(FieldType.DATE, TypeInference.infer(LocalDate.class, null).fieldType());
        assertEquals(FieldType.DATE_TIME, TypeInference.infer(LocalDateTime.class, null).fieldType());
        assertEquals(FieldType.TIME, TypeInference.infer(LocalTime.class, null).fieldType());
        assertEquals(FieldType.FILTERS, TypeInference.infer(Filters.class, null).fieldType());
        assertEquals(FieldType.ORDERS, TypeInference.infer(Orders.class, null).fieldType());
    }

    @Test
    void enumSubclass_isOption_andCarriesOptionSetCode() {
        TypeInference.FieldTypeResolution r = TypeInference.infer(Tier.class, null);
        assertEquals(FieldType.OPTION, r.fieldType());
        assertEquals("Tier", r.optionSetCode());
    }

    @Test
    void pojoWithModelAnnotation_isManyToOne_andCarriesRelatedModel() {
        TypeInference.FieldTypeResolution r = TypeInference.infer(FixtureModel.class, null);
        assertEquals(FieldType.MANY_TO_ONE, r.fieldType());
        assertEquals("FixtureModel", r.relatedModel());
    }

    // ------- Tier 2: default-with-override -----------------------------

    @Test
    void string_defaultsToString() {
        assertEquals(FieldType.STRING, TypeInference.infer(String.class, null).fieldType());
    }

    @Test
    void longDefaultsToLong() {
        assertEquals(FieldType.LONG, TypeInference.infer(Long.class, null).fieldType());
        assertEquals(FieldType.LONG, TypeInference.infer(long.class, null).fieldType());
    }

    // ------- List<X> --------------------------------------------------

    @Test
    void listOfString_isMultiString() {
        assertEquals(FieldType.MULTI_STRING,
                TypeInference.infer(List.class, String.class).fieldType());
    }

    @Test
    void listOfEnum_isMultiOption_withOptionSetCode() {
        TypeInference.FieldTypeResolution r =
                TypeInference.infer(List.class, Tier.class);
        assertEquals(FieldType.MULTI_OPTION, r.fieldType());
        assertEquals("Tier", r.optionSetCode());
    }

    @Test
    void listOfPojoWithModel_isOneToMany_withRelatedModel() {
        TypeInference.FieldTypeResolution r =
                TypeInference.infer(List.class, FixtureModel.class);
        assertEquals(FieldType.ONE_TO_MANY, r.fieldType());
        assertEquals("FixtureModel", r.relatedModel());
    }

    @Test
    void rawList_raises() {
        assertThrows(IllegalStateException.class,
                () -> TypeInference.infer(List.class, null));
    }

    @Test
    void listOfLong_raises_becauseAmbiguous() {
        assertThrows(IllegalStateException.class,
                () -> TypeInference.infer(List.class, Long.class));
    }

    // ------- Tier 3: must-specify -------------------------------------

    @Test
    void byteArray_raises() {
        assertThrows(IllegalStateException.class,
                () -> TypeInference.infer(byte[].class, null));
    }

    @Test
    void mapType_raises() {
        assertThrows(IllegalStateException.class,
                () -> TypeInference.infer(Map.class, null));
    }
}
