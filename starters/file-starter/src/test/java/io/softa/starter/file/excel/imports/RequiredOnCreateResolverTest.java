package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.excel.imports.handler.DefaultHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A required column left blank under ignoreEmpty fails a row that creates a record, and is fine on a
 * row that updates one — a blank there keeps the stored value.
 */
class RequiredOnCreateResolverTest {

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);

    private RequiredOnCreateResolver resolver() {
        RequiredOnCreateResolver resolver = new RequiredOnCreateResolver();
        ReflectionTestUtils.setField(resolver, "modelService", modelService);
        return resolver;
    }

    private static ImportTemplateDTO template(ImportRule rule) {
        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName("Employee");
        template.setImportRule(rule);
        template.setUniqueConstraints(List.of("code"));
        return template;
    }

    private static Map<String, Object> row(String code, String... blankRequired) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        for (String header : blankRequired) {
            RequiredOnCreateResolver.record(row, header);
        }
        return row;
    }

    /** E1 exists, anything else does not; the matched id is written into the rows it is given. */
    private void existing(String code) {
        when(modelService.splitByExistence(anyString(), any(), any())).thenAnswer(inv -> {
            List<Map<String, Object>> rows = inv.getArgument(1);
            List<Map<String, Object>> created = new ArrayList<>();
            List<Map<String, Object>> updated = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                if (code.equals(r.get("code"))) {
                    r.put("id", 1L);
                    updated.add(r);
                } else {
                    created.add(r);
                }
            }
            return new CreateOrUpdateResult(created, updated);
        });
    }

    @Test
    void aCreatingRowWithABlankRequiredColumnFails() {
        existing("E1");
        Map<String, Object> creating = row("E9", "Name", "Hire Date");

        resolver().resolve(template(ImportRule.CREATE_OR_UPDATE), List.of(creating), true);

        assertThat(creating.get(FileConstant.FAILED_REASON))
                .isEqualTo("The field `Name` is required; The field `Hire Date` is required");
        assertThat(creating).doesNotContainKey(RequiredOnCreateResolver.MARKER);
    }

    @Test
    void anUpdatingRowWithABlankRequiredColumnPasses() {
        existing("E1");
        Map<String, Object> updating = row("E1", "Name");

        resolver().resolve(template(ImportRule.CREATE_OR_UPDATE), List.of(updating), true);

        assertThat(updating).doesNotContainKey(FileConstant.FAILED_REASON)
                .doesNotContainKey(RequiredOnCreateResolver.MARKER)
                .as("the lookup ran on a copy; the write decides ids itself").doesNotContainKey("id");
    }

    @Test
    void onlyUpdateDecidesTheSameWay() {
        existing("E1");
        Map<String, Object> updating = row("E1", "Name");

        resolver().resolve(template(ImportRule.ONLY_UPDATE), List.of(updating), true);

        assertThat(updating).doesNotContainKey(FileConstant.FAILED_REASON);
    }

    @Test
    void onlyCreateFailsEveryNotedRowWithoutAQuery() {
        Map<String, Object> row = row("E1", "Name");

        resolver().resolve(template(ImportRule.ONLY_CREATE), List.of(row), true);

        assertThat(row.get(FileConstant.FAILED_REASON)).isEqualTo("The field `Name` is required");
        verifyNoInteractions(modelService);
    }

    @Test
    void withoutSkipExceptionACreatingRowThrows() {
        existing("E1");
        List<Map<String, Object>> rows = List.of(row("E9", "Name"));

        ValidationException e = assertThrows(ValidationException.class,
                () -> resolver().resolve(template(ImportRule.CREATE_OR_UPDATE), rows, false));
        assertThat(e.getMessage()).isEqualTo("The field `Name` is required");
    }

    @Test
    void aRowWithAnIncompleteKeyCreates() {
        Map<String, Object> row = row(null, "Name");

        resolver().resolve(template(ImportRule.CREATE_OR_UPDATE), List.of(row), true);

        assertThat(row.get(FileConstant.FAILED_REASON)).isEqualTo("The field `Name` is required");
        verifyNoInteractions(modelService);
    }

    @Test
    void aRowThatAlreadyFailedIsNotLookedUpAndKeepsItsReason() {
        Map<String, Object> row = row("E1", "Name");
        row.put(FileConstant.FAILED_REASON, "An earlier row in this file already has code=E1");

        resolver().resolve(template(ImportRule.CREATE_OR_UPDATE), List.of(row), true);

        assertThat(row.get(FileConstant.FAILED_REASON)).isEqualTo("An earlier row in this file already has code=E1");
        assertThat(row).doesNotContainKey(RequiredOnCreateResolver.MARKER);
        verifyNoInteractions(modelService);
    }

    // ───────────────────────── the field handler side ─────────────────────────

    private static DefaultHandler handler(boolean required, boolean ignoreEmpty) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(metaField, "label", "Name");
        ReflectionTestUtils.setField(metaField, "fieldName", "name");
        ReflectionTestUtils.setField(metaField, "modelName", "Employee");
        ImportFieldDTO field = new ImportFieldDTO();
        field.setHeader("Full Name");
        field.setRequired(required);
        field.setIgnoreEmpty(ignoreEmpty);
        return new DefaultHandler(metaField, field);
    }

    @Test
    void underIgnoreEmptyTheHandlerNotesTheBlankInsteadOfFailing() {
        Map<String, Object> row = new LinkedHashMap<>(Map.of("code", "E1"));
        row.put("name", " ");

        handler(true, true).handleRows(new ArrayList<>(List.of(row)), true);

        assertThat(row).doesNotContainKey("name").doesNotContainKey(FileConstant.FAILED_REASON);
        assertThat(row.get(RequiredOnCreateResolver.MARKER)).asString().contains("Full Name");
    }

    @Test
    void withoutIgnoreEmptyTheHandlerStillFailsTheRowAtOnce() {
        Map<String, Object> row = new LinkedHashMap<>(Map.of("code", "E1"));

        handler(true, false).handleRows(new ArrayList<>(List.of(row)), true);

        assertThat(row.get(FileConstant.FAILED_REASON)).isEqualTo("The field `Full Name` is required");
        assertThat(row).doesNotContainKey(RequiredOnCreateResolver.MARKER);
    }
}
