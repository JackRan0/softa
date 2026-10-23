package io.softa.starter.file.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.constant.FileConstant;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.excel.imports.ImportFailureCollector;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rows of an import's result file: every row of the uploaded file, in the order it was read, each
 * marked with its outcome — not only the failed ones, and not failures first.
 */
class ImportResultRowsTest {

    private static Map<String, Object> row(String code) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        return row;
    }

    /** Built the way the import builds it: the working rows, a copy as read, and the full list of that copy. */
    private static ImportDataDTO read(String... codes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> originals = new ArrayList<>();
        for (String code : codes) {
            rows.add(row(code));
            originals.add(row(code));
        }
        ImportDataDTO dto = new ImportDataDTO();
        dto.setRows(rows);
        dto.setOriginalRows(originals);
        dto.setSourceRows(new ArrayList<>(originals));
        return dto;
    }

    @Test
    void everyRowInFileOrderWithItsOutcome() {
        ImportDataDTO dto = read("E1", "E2", "E3", "E4");
        // The pipeline marks rows 2 and 4; the collector moves them out of the working lists.
        dto.getRows().get(1).put(FileConstant.FAILED_REASON, "Name is required");
        dto.getRows().get(3).put(FileConstant.FAILED_REASON, "Duplicate code");
        new ImportFailureCollector().collect(dto);
        assertThat(dto.getFailedRows()).hasSize(2);

        List<Map<String, Object>> result = ImportServiceImpl.resultRows(dto, "Success", "Failed");

        assertThat(result).extracting(r -> r.get("code")).containsExactly("E1", "E2", "E3", "E4");
        assertThat(result).extracting(r -> r.get("Result"))
                .containsExactly("Success", "Failed", "Success", "Failed");
        assertThat(result).extracting(r -> r.get(FileConstant.FAILED_REASON))
                .containsExactly(null, "Name is required", null, "Duplicate code");
    }

    @Test
    void aFullySuccessfulImportStillListsEveryRow() {
        ImportDataDTO dto = read("E1", "E2");
        new ImportFailureCollector().collect(dto);

        assertThat(ImportServiceImpl.resultRows(dto, "Success", "Failed"))
                .extracting(r -> r.get("Result")).containsExactly("Success", "Success");
    }

    @Test
    void theSourceRowsAreNotModified() {
        ImportDataDTO dto = read("E1");
        new ImportFailureCollector().collect(dto);

        ImportServiceImpl.resultRows(dto, "Success", "Failed");

        assertThat(dto.getSourceRows().getFirst()).doesNotContainKey("Result");
    }
}
