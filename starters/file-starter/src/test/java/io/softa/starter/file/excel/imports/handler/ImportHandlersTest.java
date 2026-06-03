package io.softa.starter.file.excel.imports.handler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaOptionItem;
import io.softa.framework.orm.meta.OptionManager;
import io.softa.starter.file.dto.ImportFieldDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportHandlersTest {

    @AfterEach
    void tearDown() {
        optionSetMap().clear();
    }

    @Test
    void dateHandlerFormatsDateString() {
        DateHandler handler = new DateHandler(metaField(FieldType.DATE, "Start Date", "startDate", null), new ImportFieldDTO());

        assertEquals("2024-09-01", handler.handleValue("2024-9"));
    }

    @Test
    void dateHandlerRejectsInvalidDateString() {
        DateHandler handler = new DateHandler(metaField(FieldType.DATE, "Start Date", "startDate", null), new ImportFieldDTO());

        assertThrows(ValidationException.class, () -> handler.handleValue("invalid-date"));
    }

    @Test
    void dateTimeHandlerFormatsDateTimeString() {
        DateTimeHandler handler = new DateTimeHandler(metaField(FieldType.DATE_TIME, "Start Time", "startTime", null),
                new ImportFieldDTO());

        assertEquals("2024-09-05 01:30:00", handler.handleValue("2024-9-5 1:30"));
    }

    @Test
    void dateTimeHandlerRejectsInvalidDateTimeString() {
        DateTimeHandler handler = new DateTimeHandler(metaField(FieldType.DATE_TIME, "Start Time", "startTime", null),
                new ImportFieldDTO());

        assertThrows(ValidationException.class, () -> handler.handleValue("2024-13-40 25:61"));
    }

    @Test
    void timeHandlerFormatsTimeString() {
        TimeHandler handler = new TimeHandler(metaField(FieldType.TIME, "Clock In", "clockIn", null), new ImportFieldDTO());

        assertEquals("02:30:00", handler.handleValue("2:30"));
    }

    @Test
    void timeHandlerRejectsInvalidTimeString() {
        TimeHandler handler = new TimeHandler(metaField(FieldType.TIME, "Clock In", "clockIn", null), new ImportFieldDTO());

        assertThrows(ValidationException.class, () -> handler.handleValue("25:61"));
    }

    @Test
    void optionHandlerResolvesOptionNameToCode() {
        registerOptionSet("status_set", optionItem("OPEN", "Open"));
        OptionHandler handler = new OptionHandler(metaField(FieldType.OPTION, "Status", "status", "status_set"),
                new ImportFieldDTO());

        assertEquals("OPEN", handler.handleValue("Open"));
    }

    @Test
    void optionHandlerRejectsUnknownOption() {
        registerOptionSet("status_set", optionItem("OPEN", "Open"));
        OptionHandler handler = new OptionHandler(metaField(FieldType.OPTION, "Status", "status", "status_set"),
                new ImportFieldDTO());

        assertThrows(ValidationException.class, () -> handler.handleValue("Unknown"));
    }

    @Test
    void multiOptionHandlerResolvesMixedCodesAndNames() {
        registerOptionSet("tag_set", optionItem("ACTIVE", "Active"), optionItem("PENDING", "Pending"));
        MultiOptionHandler handler = new MultiOptionHandler(metaField(FieldType.MULTI_OPTION, "Tags", "tags", "tag_set"),
                new ImportFieldDTO());

        assertEquals(List.of("ACTIVE", "PENDING"), handler.handleValue("ACTIVE,Pending"));
    }

    @Test
    void multiOptionHandlerRejectsUnknownOption() {
        registerOptionSet("tag_set", optionItem("ACTIVE", "Active"));
        MultiOptionHandler handler = new MultiOptionHandler(metaField(FieldType.MULTI_OPTION, "Tags", "tags", "tag_set"),
                new ImportFieldDTO());

        assertThrows(ValidationException.class, () -> handler.handleValue("Missing"));
    }

    private MetaField metaField(FieldType fieldType, String label, String fieldName, String optionSetCode) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        ReflectionTestUtils.setField(metaField, "label", label);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "modelName", "demo.model");
        ReflectionTestUtils.setField(metaField, "optionSetCode", optionSetCode);
        return metaField;
    }

    private MetaOptionItem optionItem(String itemCode, String label) {
        MetaOptionItem metaOptionItem = new MetaOptionItem();
        ReflectionTestUtils.setField(metaOptionItem, "itemCode", itemCode);
        ReflectionTestUtils.setField(metaOptionItem, "label", label);
        return metaOptionItem;
    }

    private void registerOptionSet(String optionSetCode, MetaOptionItem... items) {
        Map<String, MetaOptionItem> optionItems = new LinkedHashMap<>();
        for (MetaOptionItem item : items) {
            optionItems.put(item.getItemCode(), item);
        }
        optionSetMap().put(optionSetCode, optionItems);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, MetaOptionItem>> optionSetMap() {
        return (Map<String, Map<String, MetaOptionItem>>) ReflectionTestUtils.getField(OptionManager.class,
                "META_OPTION_SET_MAP");
    }
}
