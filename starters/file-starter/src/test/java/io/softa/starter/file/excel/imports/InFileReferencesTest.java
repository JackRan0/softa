package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rows naming other rows of the same file: in what order they can be written, and which of them cannot.
 */
class InFileReferencesTest {

    private static Map<String, Object> row(String code) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        return row;
    }

    private static InFileReferences.Reference manager(Map<String, Object> row, Map<String, Object> target) {
        return new InFileReferences.Reference(row, "piEmployeeId", "Manager", List.of("code"),
                List.of(target.get("code")), target);
    }

    @Test
    void writesEachRowAfterTheRowItNamesWhateverTheFileOrder() {
        // A three-level chain listed bottom up, plus a row naming nobody in the file.
        Map<String, Object> staff = row("E3");
        Map<String, Object> lead = row("E2");
        Map<String, Object> head = row("E1");
        Map<String, Object> loner = row("E9");
        List<Map<String, Object>> rows = List.of(staff, lead, head, loner);

        List<List<Integer>> layers = InFileReferences.layers(rows,
                List.of(manager(staff, lead), manager(lead, head)));

        assertThat(layers).containsExactly(List.of(2, 3), List.of(1), List.of(0));
    }

    @Test
    void aRowNamingARowThatFailedFailsTooAndSaysWhy() {
        // The reader is owed the real reason, not "cannot find" for a code they can see in the file —
        // and it carries down the chain.
        Map<String, Object> staff = row("E3");
        Map<String, Object> lead = row("E2");
        Map<String, Object> head = row("E1");
        head.put(FileConstant.FAILED_REASON, "Hire Date is required.");

        InFileReferences.settle(new ArrayList<>(List.of(manager(staff, lead), manager(lead, head))), true);

        assertThat(lead.get(FileConstant.FAILED_REASON))
                .isEqualTo("Manager E1 is a row of this file that was not imported.");
        assertThat(staff.get(FileConstant.FAILED_REASON))
                .isEqualTo("Manager E2 is a row of this file that was not imported.");
    }

    @Test
    void rowsNamingEachOtherRoundALoopFailTogether() {
        // There is no order to write them in.
        Map<String, Object> a = row("E1");
        Map<String, Object> b = row("E2");
        Map<String, Object> outside = row("E3");

        InFileReferences.settle(new ArrayList<>(List.of(manager(a, b), manager(b, a), manager(outside, a))), true);

        assertThat(a.get(FileConstant.FAILED_REASON))
                .isEqualTo("Manager forms a loop within this file: E1 → E2 → E1.");
        assertThat(b.get(FileConstant.FAILED_REASON)).isEqualTo(a.get(FileConstant.FAILED_REASON));
        // Not on the loop, but naming a row that is: refused before anything is written, so a preview
        // reports it rather than the write discovering it.
        assertThat(outside.get(FileConstant.FAILED_REASON))
                .isEqualTo("Manager E1 is a row of this file that was not imported.");
    }

    @Test
    void aFailFastFileStopsAtTheFirstRefusal() {
        Map<String, Object> a = row("E1");
        Map<String, Object> b = row("E2");

        assertThatThrownBy(() -> InFileReferences.settle(List.of(manager(a, b), manager(b, a)), false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("forms a loop");
    }

    @Test
    void comparesKeysAsTheyReadNotAsTheCellHeldThem() {
        assertThat(InFileReferences.ownKey(Map.of("code", " E1 "), List.of("code"))).containsExactly("E1");
        assertThat(InFileReferences.ownKey(Map.of("code", 1001), List.of("code"))).containsExactly("1001");
        assertThat(InFileReferences.ownKey(Map.of("code", "  "), List.of("code"))).isNull();
    }
}
