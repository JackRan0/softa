package io.softa.starter.metadata.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.FileObject;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreDataFormatParserTest {

    private final PreDataFormatParser parser = new PreDataFormatParser();

    @BeforeAll
    static void ensureSystemConfig() {
        // Framework IllegalArgumentException construction reaches I18n via BaseException,
        // which requires SystemConfig.env to be non-null. Raw unit tests must seed it.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    private static FileObject file(String name, FileType type, String content) {
        return new FileObject(name, type, content);
    }

    private static MetaField field(FieldType type) {
        MetaField metaField = mock(MetaField.class);
        when(metaField.getFieldType()).thenReturn(type);
        return metaField;
    }

    @Test
    void blankContentYieldsEmptyMap() {
        assertTrue(parser.parse(file("Item.json", FileType.JSON, "")).isEmpty());
        assertTrue(parser.parse(file("Item.json", FileType.JSON, "   ")).isEmpty());
    }

    @Test
    void parseJsonSingleMapKeepsModelKeyAndRow() {
        LinkedHashMap<String, Object> result =
                parser.parse(file("any.json", FileType.JSON, "{\"Item\":{\"id\":\"i1\",\"name\":\"Widget\"}}"));

        assertEquals(1, result.size());
        Map<?, ?> row = assertInstanceOf(Map.class, result.get("Item"));
        assertEquals("i1", row.get("id"));
        assertEquals("Widget", row.get("name"));
    }

    @Test
    void parseJsonListKeepsRowsAsList() {
        LinkedHashMap<String, Object> result =
                parser.parse(file("any.json", FileType.JSON, "{\"Item\":[{\"id\":\"i1\"},{\"id\":\"i2\"}]}"));

        List<?> rows = assertInstanceOf(List.class, result.get("Item"));
        assertEquals(2, rows.size());
    }

    @Test
    void parseJsonPreservesModelDeclarationOrder() {
        LinkedHashMap<String, Object> result = parser.parse(
                file("any.json", FileType.JSON, "{\"Bravo\":{\"id\":\"b\"},\"Alpha\":{\"id\":\"a\"}}"));

        // LinkedHashMap so dependent rows load after the rows they reference.
        assertEquals(List.of("Bravo", "Alpha"), new ArrayList<>(result.keySet()));
    }

    @Test
    void parseCsvTypesCellsAndRetainsRelationPreIds() {
        String csv = """
                id,name,qty,owner
                i1,Widget,5,o1
                i2,Gadget,9,o2
                """;
        // Build the MetaField mocks up front — creating/stubbing a mock inside a
        // MockedStatic when().thenReturn() chain trips Mockito's unfinished-stubbing guard.
        MetaField idField = field(FieldType.STRING);
        MetaField nameField = field(FieldType.STRING);
        MetaField qtyField = field(FieldType.INTEGER);
        MetaField ownerField = field(FieldType.MANY_TO_ONE);
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Item")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("Item", "id")).thenReturn(idField);
            mm.when(() -> ModelManager.getModelField("Item", "name")).thenReturn(nameField);
            mm.when(() -> ModelManager.getModelField("Item", "qty")).thenReturn(qtyField);
            mm.when(() -> ModelManager.getModelField("Item", "owner")).thenReturn(ownerField);

            LinkedHashMap<String, Object> result = parser.parse(file("Item.csv", FileType.CSV, csv));

            assertEquals(List.of("Item"), new ArrayList<>(result.keySet()));
            List<?> rows = assertInstanceOf(List.class, result.get("Item"));
            assertEquals(2, rows.size());

            Map<?, ?> first = assertInstanceOf(Map.class, rows.get(0));
            assertEquals("i1", first.get("id"));                  // id retained as String preId
            assertEquals("Widget", first.get("name"));            // STRING converted to itself
            assertEquals(Integer.valueOf(5), first.get("qty"));   // INTEGER converted from "5"
            assertEquals("o1", first.get("owner"));               // ManyToOne retained as String preId
        }
    }

    @Test
    void parseCsvRejectsUnknownModel() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Ghost")).thenReturn(false);
            assertThrows(IllegalArgumentException.class,
                    () -> parser.parse(file("Ghost.csv", FileType.CSV, "id\nx1")));
        }
    }

    @Test
    void unsupportedFileTypeFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file("notes.txt", FileType.TXT, "anything")));
    }

    @Test
    void xmlFormatIsNotYetSupported() {
        // Fail-fast rather than silently loading nothing (former TODO no-op).
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(file("Item.xml", FileType.XML, "<data/>")));
    }
}
