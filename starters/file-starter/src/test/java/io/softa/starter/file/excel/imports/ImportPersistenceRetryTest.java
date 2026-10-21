package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * After a failed batch the rows are retried one by one, from the rows as they were read.
 *
 * <p>The write works on the rows in place: a nested one-to-one row is given the id it was inserted under
 * and its parent row is handed that id in place of the nested value. The failed batch rolls those inserts
 * back, so a retry from the rows as left named records that no longer exist — every row refused for a
 * caller held to its row range, and written pointing at nothing for one who is not.
 */
class ImportPersistenceRetryTest {

    private static final String MODEL = "Employee";

    private static Map<String, Object> row(String code) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("personalEmail", code + "@example.com");
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        row.put("employeeProfileId", profile);
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachRowIsRetriedAsItWasReadNotAsTheFailedBatchLeftIt() {
        ModelService<?> modelService = mock(ModelService.class);
        List<Map<String, Object>> seen = new ArrayList<>();
        doAnswer(invocation -> {
            List<Map<String, Object>> rows = invocation.getArgument(1);
            if (rows.size() > 1) {
                // The batch: nested rows are inserted and linked in place, then the write fails and rolls back.
                long id = 900;
                for (Map<String, Object> row : rows) {
                    ((Map<String, Object>) row.get("employeeProfileId")).put("id", ++id);
                    row.put("employeeProfileId", id);
                }
                throw new IllegalStateException("duplicate key on the second row");
            }
            seen.add(new HashMap<>(rows.getFirst()));
            return List.of(1L);
        }).when(modelService).createList(eq(MODEL), anyList());

        ImportPersistenceService service = new ImportPersistenceService();
        ReflectionTestUtils.setField(service, "modelService", modelService);
        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName(MODEL);
        template.setImportRule(ImportRule.ONLY_CREATE);
        template.setSkipException(true);
        ImportDataDTO data = new ImportDataDTO();
        data.setRows(new ArrayList<>(List.of(row("E1"), row("E2"))));
        data.setOriginalRows(new ArrayList<>(List.of(row("E1"), row("E2"))));

        service.persist(template, data);

        assertThat(seen).hasSize(2);
        for (Map<String, Object> retried : seen) {
            assertThat(retried.get("employeeProfileId"))
                    .as("the nested profile, not the id of one the batch rolled back")
                    .isInstanceOf(Map.class);
            assertThat((Map<String, Object>) retried.get("employeeProfileId")).doesNotContainKey("id");
        }
    }

    @Test
    void theCopyLeavesTheReadRowsUntouchedByAWriteOnIt() {
        List<Map<String, Object>> read = List.of(row("E1"));
        List<Map<String, Object>> copy = ImportPersistenceService.copyRows(read);

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) copy.getFirst().get("employeeProfileId");
        nested.put("id", 901L);
        copy.getFirst().put("employeeProfileId", 901L);

        assertThat(read.getFirst().get("employeeProfileId")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) read.getFirst().get("employeeProfileId");
        assertThat(original).doesNotContainKey("id");
    }
}
