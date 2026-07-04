package io.softa.starter.studio.meta.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.service.DesignFieldService;

/**
 * The reference-by-code stamper resolves the referenced column within the SAME
 * env. Since {@code design_field} is per-env (businessKey {envId, modelName, fieldName}), an
 * app-wide lookup would match the same {@code (modelName, fieldName)} in every env and make
 * {@code searchOne} throw once a second env exists. This asserts the env-scoped lookup resolves and
 * mirrors the referenced column's physical width onto the FK row.
 */
class DesignFieldRelationStamperTest {

    @Test
    void stampsReferencedWidthViaEnvScopedLookup() {
        DesignFieldRelationStamper stamper = new DesignFieldRelationStamper();
        DesignFieldService fieldService = mock(DesignFieldService.class);
        ReflectionTestUtils.setField(stamper, "fieldService", fieldService);

        // Currency.code in this env is a 3-char STRING.
        DesignField referenced = new DesignField();
        referenced.setFieldType(FieldType.STRING);
        referenced.setLength(3);
        when(fieldService.searchOne(any(FlexQuery.class))).thenReturn(Optional.of(referenced));

        Map<String, Object> row = new HashMap<>();
        row.put("appId", 100L);
        row.put("envId", 2L);
        row.put("fieldType", FieldType.MANY_TO_ONE);
        row.put("relatedModel", "Currency");
        row.put("relatedField", "code");
        row.put("fieldName", "defaultCurrency");

        stamper.stamp(row);

        // The FK row mirrors the referenced column's logical type + width (resolved in-env).
        assertEquals(FieldType.STRING.getType(), row.get("relatedFieldType"));
        assertEquals(3, row.get("length"));
    }

    @Test
    void updateMergesPersistedRelationAttrsWhenRowIsPartial() {
        DesignFieldRelationStamper stamper = new DesignFieldRelationStamper();
        DesignFieldService fieldService = mock(DesignFieldService.class);
        ReflectionTestUtils.setField(stamper, "fieldService", fieldService);

        // Persisted FK: MANY_TO_ONE -> Currency.code, in env 2 / app 100. The client does NOT re-send
        // fieldType/relatedModel/appId/envId on a partial edit; the stamper must recover them from here.
        DesignField existing = new DesignField();
        existing.setId(5L);
        existing.setFieldType(FieldType.MANY_TO_ONE);
        existing.setRelatedModel("Currency");
        existing.setRelatedField("code");
        existing.setFieldName("defaultCurrency");
        existing.setAppId(100L);
        existing.setEnvId(2L);
        when(fieldService.getById(5L)).thenReturn(Optional.of(existing));

        DesignField referenced = new DesignField();
        referenced.setFieldType(FieldType.STRING);
        referenced.setLength(3);
        when(fieldService.searchOne(any(FlexQuery.class))).thenReturn(Optional.of(referenced));

        // Partial update: touches the relation (relatedField key present) but relies on the persisted
        // fieldType/appId/envId — the whole getById + merge-from-existing branch.
        Map<String, Object> row = new HashMap<>();
        row.put("id", 5L);
        row.put("relatedField", "code");

        stamper.stamp(row);

        verify(fieldService).getById(5L);
        // Resolved against the persisted env, mirroring the referenced column's width.
        assertEquals(FieldType.STRING.getType(), row.get("relatedFieldType"));
        assertEquals(3, row.get("length"));
    }

    @Test
    void clearsRelatedFieldTypeWhenConvertedAwayFromRelation() {
        DesignFieldRelationStamper stamper = new DesignFieldRelationStamper();
        DesignFieldService fieldService = mock(DesignFieldService.class);
        ReflectionTestUtils.setField(stamper, "fieldService", fieldService);

        // Was a FK; the edit changes it to a plain STRING.
        DesignField existing = new DesignField();
        existing.setId(7L);
        existing.setFieldType(FieldType.MANY_TO_ONE);
        existing.setRelatedModel("Currency");
        existing.setRelatedField("code");
        existing.setAppId(100L);
        existing.setEnvId(2L);
        when(fieldService.getById(7L)).thenReturn(Optional.of(existing));

        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("fieldType", FieldType.STRING);

        stamper.stamp(row);

        // A non-FK field must have its stale physical FK type scrubbed (explicit null, not left stale).
        assertTrue(row.containsKey("relatedFieldType"));
        assertNull(row.get("relatedFieldType"));
        // A non-TO_ONE type short-circuits before any referenced-column lookup.
        verify(fieldService, never()).searchOne(any(FlexQuery.class));
    }
}
