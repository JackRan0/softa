package io.softa.framework.orm.utils;

import java.util.HashMap;
import java.util.Map;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.entity.AbstractModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An entity may hold a to-one as its related model's object rather than as the id. Read expanded, the
 * row carries the related row; read as stored, it carries only the id — and the object then holds just
 * that id, instead of the read failing on a type mismatch.
 */
class BeanToolToOneObjectTest {

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class Policy extends AbstractModel {
        private Long id;
        private String name;
    }

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class Request extends AbstractModel {
        private Long id;
        private Policy policyId;
    }

    @Test
    @DisplayName("a to-one read as stored becomes its model's object holding the id")
    void storedIdBecomesObjectWithId() {
        Request request = BeanTool.mapToObject(row(7L), Request.class);

        assertEquals(7L, request.getPolicyId().getId());
        assertNull(request.getPolicyId().getName());
    }

    @Test
    @DisplayName("a to-one read expanded still becomes the full object")
    void expandedRowBecomesFullObject() {
        Request request = BeanTool.mapToObject(row(Map.of("id", 7L, "name", "Standard")), Request.class);

        assertEquals(7L, request.getPolicyId().getId());
        assertEquals("Standard", request.getPolicyId().getName());
    }

    @Test
    @DisplayName("an empty to-one stays empty")
    void emptyStaysEmpty() {
        assertNull(BeanTool.mapToObject(row(null), Request.class).getPolicyId());
    }

    private static Map<String, Object> row(Object policy) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("policyId", policy);
        return row;
    }
}
