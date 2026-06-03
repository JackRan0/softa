package io.softa.starter.file.excel.imports;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.excel.imports.handler.BaseImportHandler;
import io.softa.starter.file.excel.imports.handler.TimeHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ImportHandlerFactoryTest {

    @Test
    void createHandlerUsesTimeHandlerForTimeFields() throws Exception {
        ImportHandlerFactory factory = new ImportHandlerFactory();
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.TIME);
        ReflectionTestUtils.setField(metaField, "label", "Clock In");
        ReflectionTestUtils.setField(metaField, "fieldName", "clockIn");
        ReflectionTestUtils.setField(metaField, "modelName", "attendance");

        ImportFieldDTO importFieldDTO = new ImportFieldDTO();
        importFieldDTO.setFieldName("clockIn");

        Method createHandler = ImportHandlerFactory.class
                .getDeclaredMethod("createHandler", MetaField.class, ImportFieldDTO.class);
        createHandler.setAccessible(true);

        BaseImportHandler handler = (BaseImportHandler) createHandler.invoke(factory, metaField, importFieldDTO);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("clockIn", "2:30");
        handler.handleRow(row);

        assertInstanceOf(TimeHandler.class, handler);
        assertEquals("02:30:00", row.get("clockIn"));
    }
}
