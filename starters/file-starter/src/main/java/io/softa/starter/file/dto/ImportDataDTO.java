package io.softa.starter.file.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.file.excel.imports.InFileReferences;

/**
 * The DTO of import data.
 */
@Data
@NoArgsConstructor
public class ImportDataDTO {

    private List<Map<String, Object>> rows;

    private List<Map<String, Object>> failedRows;

    private Map<String, Object> env;

    private List<Map<String, Object>> originalRows;

    /**
     * Every row as read, in file order — the same row objects {@link #originalRows} starts with.
     *
     * <p>{@code originalRows} loses each row that fails, which moves to {@link #failedRows} carrying its
     * Failed Reason; this list keeps them all where they were. A row here is therefore failed exactly
     * when it carries a Failed Reason, which is what the result file is written from.
     */
    private List<Map<String, Object>> sourceRows;

    /**
     * Rows that name another row of this same file through a many-to-one onto their own model — left
     * unresolved by the relation lookup, which only sees stored rows, and filled in when the file is
     * written in layers. Empty for most imports.
     */
    private List<InFileReferences.Reference> inFileReferences = new ArrayList<>();
}
