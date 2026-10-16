package io.softa.framework.orm.utils;

import java.util.List;

import io.softa.framework.base.exception.IllegalArgumentException;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Field lookups by name must find a field wherever it is declared on the class chain.
 *
 * <p>An entity may take some of its fields from an abstract base class — the audit fields always have,
 * and a model whose framework-read fields live on a shared base now does too. Row-to-object
 * conversion never minded (it goes through bean properties); these name-based lookups did, and threw
 * {@code NoSuchFieldException} for anything not declared on the concrete class.
 */
class BeanToolInheritedFieldTest {

    @Data
    abstract static class Base {
        private String country;
        private Integer headcount;
        private List<String> tags;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    static class Concrete extends Base {
        private String own;
    }

    @Test
    @DisplayName("reads the value of a field declared on the superclass")
    void readsInheritedValue() {
        Concrete c = new Concrete();
        c.setCountry("SG");
        assertEquals("SG", BeanTool.getFieldValue(c, "country"));
    }

    @Test
    @DisplayName("converts a value to the type of a field declared on the superclass")
    void convertsToInheritedFieldType() {
        assertEquals(42, BeanTool.convertPropertyValue(Concrete.class, "headcount", 42));
        assertEquals(List.of(1, 2), BeanTool.convertPropertyValues(Concrete.class, "headcount", List.of(1, 2)));
    }

    @Test
    @DisplayName("still finds a field declared on the class itself")
    void findsOwnField() {
        Concrete c = new Concrete();
        c.setOwn("x");
        assertEquals("x", BeanTool.getFieldValue(c, "own"));
    }

    @Test
    @DisplayName("still rejects a name that is declared nowhere on the chain")
    void rejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> BeanTool.getFieldValue(new Concrete(), "missing"));
    }
}
