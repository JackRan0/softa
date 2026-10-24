package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copy entry points must reject models declared {@code @Model(copyable = false)}
 * before any read or write happens.
 */
class ModelServiceCopyRejectionTest {

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @Test
    void copyByIds_rejectsNonCopyableModel() {
        ModelServiceImpl<Serializable> service = new ModelServiceImpl<>();
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class)) {
            mock.when(() -> ModelManager.isCopyableModel("AuditLog")).thenReturn(false);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.copyByIds("AuditLog", List.of(1L)));
            assertTrue(ex.getMessage().contains("not copyable"));
        }
    }

    @Test
    void getCopyableFields_rejectsNonCopyableModel() {
        ModelServiceImpl<Serializable> service = new ModelServiceImpl<>();
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class)) {
            mock.when(() -> ModelManager.isCopyableModel("AuditLog")).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> service.getCopyableFields("AuditLog", 1L));
        }
    }

    @Test
    void getCopyableFields_readsAsAFormDoesAndKeepsOnlyCopyableFields() {
        // References come back with their display names — a bare id in a copied form has nothing to
        // show in a picker whose first page lacks it — and only the copyable fields are kept.
        ModelServiceImpl<Serializable> service = Mockito.spy(new ModelServiceImpl<>());
        Map<String, Object> row = new HashMap<>();
        row.put("label", "x");
        row.put("optionSetId", Map.of("id", 9L, "displayName", "Anchor Operator"));
        row.put("tenantId", 7L);
        Mockito.doReturn(Optional.of(row)).when(service)
                .getById(eq("Item"), eq(1L), anyCollection(), isNull(), eq(ConvertType.REFERENCE));
        try (MockedStatic<ModelManager> mock = Mockito.mockStatic(ModelManager.class)) {
            mock.when(() -> ModelManager.isCopyableModel("Item")).thenReturn(true);
            mock.when(() -> ModelManager.getModelCopyableFields("Item")).thenReturn(List.of("label", "optionSetId"));

            Map<String, Object> copy = service.getCopyableFields("Item", 1L);

            assertEquals(Map.of("label", "x", "optionSetId", Map.of("id", 9L, "displayName", "Anchor Operator")), copy);
        }
    }
}
