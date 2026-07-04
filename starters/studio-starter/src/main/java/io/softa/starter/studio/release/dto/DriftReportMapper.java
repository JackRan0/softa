package io.softa.starter.studio.release.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.softa.starter.studio.release.enums.DriftKind;

/**
 * Translate the deploy-oriented {@link ModelChangesDTO} graph into the drift-oriented
 * {@link DriftReportDTO} list served by the read-only drift API.
 * <p>
 * Mapping table — note how the deploy-direction labels flip when read as drift:
 * <ul>
 *   <li>{@code createdRows} (design has, runtime doesn't) → {@link DriftKind#RUNTIME_DELETED}</li>
 *   <li>{@code updatedRows} (matched, fields differ)      → {@link DriftKind#RUNTIME_MODIFIED}</li>
 *   <li>{@code deletedRows} (runtime has, design doesn't) → {@link DriftKind#RUNTIME_ADDED}</li>
 * </ul>
 * For RUNTIME_MODIFIED, {@code expected} (design side) is the full row projected onto the changed
 * columns and {@code actual} (runtime side) is {@code previousValuesForChangedFields}; only the diverged
 * fields are carried, never the full row.
 */
public final class DriftReportMapper {

    private DriftReportMapper() {}

    public static List<DriftReportDTO> toReport(List<RowChangeDTO> drift) {
        if (drift == null || drift.isEmpty()) {
            return List.of();
        }
        // The drift diff is a flat row-change list; regroup per meta-table for the per-model report.
        List<ModelChangesDTO> grouped = DesignMetaTables.group(drift);
        List<DriftReportDTO> reports = new ArrayList<>(grouped.size());
        for (ModelChangesDTO modelChanges : grouped) {
            String model = modelChanges.getModelName();
            List<DriftRowDTO> rows = new ArrayList<>(
                    modelChanges.getCreatedRows().size()
                            + modelChanges.getUpdatedRows().size()
                            + modelChanges.getDeletedRows().size());

            for (RowChangeDTO row : modelChanges.getCreatedRows()) {
                rows.add(DriftRowDTO.builder()
                        .model(model)
                        .kind(DriftKind.RUNTIME_DELETED)
                        .expected(row.getFullRow())
                        .actual(null)
                        .changedFields(null)
                        .build());
            }
            for (RowChangeDTO row : modelChanges.getUpdatedRows()) {
                // expected (design side) = the full new row projected onto the changed columns.
                Map<String, Object> previous = row.getPreviousValuesForChangedFields();
                Map<String, Object> expected = new HashMap<>();
                for (String key : previous.keySet()) {
                    expected.put(key, row.getFullRow().get(key));
                }
                rows.add(DriftRowDTO.builder()
                        .model(model)
                        .kind(DriftKind.RUNTIME_MODIFIED)
                        .expected(expected)
                        .actual(previous)
                        .changedFields(previous.keySet())
                        .build());
            }
            for (RowChangeDTO row : modelChanges.getDeletedRows()) {
                rows.add(DriftRowDTO.builder()
                        .model(model)
                        .kind(DriftKind.RUNTIME_ADDED)
                        .expected(null)
                        .actual(row.getFullRow())
                        .changedFields(null)
                        .build());
            }

            reports.add(DriftReportDTO.builder().model(model).rows(rows).build());
        }
        return reports;
    }
}
