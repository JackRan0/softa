package io.softa.starter.studio.meta.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.studio.meta.support.DesignWriteStamper;

/**
 * {@link AbstractDesignWriteController#onUpdate} records the prior name in
 * {@code renamedFrom} (single-step replace) when an update renames the entity, so publish/merge can pair
 * the renamed row by its old name. No-op when the name is unchanged or the entity is not renameable.
 */
@SuppressWarnings("unchecked")
class DesignWriteRenameCaptureTest {

    private static <C> C controller(C instance, ModelService<Long> modelService) {
        ReflectionTestUtils.setField(instance, "modelService", modelService);
        ReflectionTestUtils.setField(instance, "designWriteStamper", mock(DesignWriteStamper.class));
        return instance;
    }

    @Test
    void capturesPriorNameOnRename() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignModelController c = controller(new DesignModelController(), ms);
        when(ms.getById(eq("DesignModel"), eq(7L), anyCollection()))
                .thenReturn(Optional.of(Map.of("modelName", "OldName")));

        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("modelName", "NewName");
        c.onUpdate(row);

        assertEquals("OldName", row.get("renamedFrom"));
    }

    @Test
    void noCaptureWhenNameUnchanged() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignModelController c = controller(new DesignModelController(), ms);
        when(ms.getById(eq("DesignModel"), eq(7L), anyCollection()))
                .thenReturn(Optional.of(Map.of("modelName", "Same")));

        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("modelName", "Same");
        c.onUpdate(row);

        assertFalse(row.containsKey("renamedFrom"));
    }

    @Test
    void noCaptureWhenUpdateDoesNotTouchName() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignModelController c = controller(new DesignModelController(), ms);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("label", "Just a label edit");   // no modelName in the update
        c.onUpdate(row);

        assertFalse(row.containsKey("renamedFrom"));
        verify(ms, never()).getById(eq("DesignModel"), eq(7L), anyCollection());
    }

    @Test
    void noCaptureForNonRenameableEntity() {
        ModelService<Long> ms = mock(ModelService.class);
        DesignModelIndexController c = controller(new DesignModelIndexController(), ms);

        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("indexName", "NewIndexName");   // index has no renameKeyField → never captured
        c.onUpdate(row);

        assertFalse(row.containsKey("renamedFrom"));
        verify(ms, never()).getById(eq("DesignModelIndex"), eq(7L), anyCollection());
    }
}
