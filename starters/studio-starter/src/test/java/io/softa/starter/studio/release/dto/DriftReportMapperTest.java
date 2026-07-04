package io.softa.starter.studio.release.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.studio.release.enums.DriftKind;

/**
 * {@link DriftReportMapper} reprojects the deploy-direction {@link RowChangeDTO} graph into the
 * operator-perspective drift report. The direction flip is the whole point of the class, so it is pinned
 * here: a deploy CREATE reads as {@code RUNTIME_DELETED}, a deploy DELETE as {@code RUNTIME_ADDED}, and a
 * deploy UPDATE keeps expected(design)=new value / actual(runtime)=previous value.
 */
class DriftReportMapperTest {

    private static RowChangeDTO row(MetaTable table, RowChangeOp op, Map<String, Object> fullRow,
                                    Map<String, Object> previous) {
        RowChangeDTO change = new RowChangeDTO();
        change.setTable(table);
        change.setOp(op);
        change.setFullRow(fullRow);
        if (previous != null) {
            change.setPreviousValuesForChangedFields(previous);
        }
        return change;
    }

    private static DriftRowDTO onlyRow(List<DriftReportDTO> reports) {
        assertEquals(1, reports.size(), "one model group expected");
        assertEquals(1, reports.get(0).getRows().size(), "one drift row expected");
        return reports.get(0).getRows().get(0);
    }

    @Test
    @DisplayName("null / empty drift → empty report")
    void emptyDrift() {
        assertTrue(DriftReportMapper.toReport(null).isEmpty());
        assertTrue(DriftReportMapper.toReport(List.of()).isEmpty());
    }

    @Test
    @DisplayName("deploy CREATE (design has, runtime doesn't) flips to RUNTIME_DELETED with the design row as expected")
    void createFlipsToRuntimeDeleted() {
        Map<String, Object> fullRow = Map.of("modelName", "Customer", "label", "Customer");
        List<DriftReportDTO> reports = DriftReportMapper.toReport(
                List.of(row(MetaTable.MODEL, RowChangeOp.CREATE, fullRow, null)));

        DriftReportDTO report = reports.get(0);
        assertEquals("DesignModel", report.getModel(), "report is keyed by the Design* model name");
        DriftRowDTO drift = onlyRow(reports);
        assertEquals(DriftKind.RUNTIME_DELETED, drift.getKind());
        assertEquals(fullRow, drift.getExpected(), "design side carries the full row");
        assertNull(drift.getActual(), "runtime has no row");
        assertNull(drift.getChangedFields());
    }

    @Test
    @DisplayName("deploy DELETE (runtime has, design doesn't) flips to RUNTIME_ADDED with the runtime row as actual")
    void deleteFlipsToRuntimeAdded() {
        Map<String, Object> fullRow = Map.of("modelName", "Legacy", "label", "Legacy");
        List<DriftReportDTO> reports = DriftReportMapper.toReport(
                List.of(row(MetaTable.MODEL, RowChangeOp.DELETE, fullRow, null)));

        DriftRowDTO drift = onlyRow(reports);
        assertEquals(DriftKind.RUNTIME_ADDED, drift.getKind());
        assertNull(drift.getExpected(), "design has no row");
        assertEquals(fullRow, drift.getActual(), "runtime side carries the full row");
        assertNull(drift.getChangedFields());
    }

    @Test
    @DisplayName("deploy UPDATE → RUNTIME_MODIFIED: expected=design(new) values, actual=runtime(previous) values, only changed fields")
    void updateProjectsChangedFieldsBothSides() {
        // A field whose length diverges: design (fullRow) wants 100, runtime (previous) has 64.
        Map<String, Object> fullRow = Map.of("modelName", "Customer", "fieldName", "name",
                "length", 100, "label", "Name");
        Map<String, Object> previous = Map.of("length", 64);
        List<DriftReportDTO> reports = DriftReportMapper.toReport(
                List.of(row(MetaTable.FIELD, RowChangeOp.UPDATE, fullRow, previous)));

        assertEquals("DesignField", reports.get(0).getModel());
        DriftRowDTO drift = onlyRow(reports);
        assertEquals(DriftKind.RUNTIME_MODIFIED, drift.getKind());
        assertEquals(Map.of("length", 100), drift.getExpected(),
                "expected = full row projected onto ONLY the changed columns (design value)");
        assertEquals(previous, drift.getActual(), "actual = previous (runtime) values");
        assertEquals(previous.keySet(), drift.getChangedFields(), "only the diverged field is reported");
    }
}
