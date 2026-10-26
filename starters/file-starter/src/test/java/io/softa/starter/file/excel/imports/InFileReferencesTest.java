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
    void aChainAsDeepAsTheFileMakesItDoesNotOverflowTheStack() {
        // Recursion turned a long chain into a StackOverflowError that failed the whole import with no
        // row to blame. Listed bottom up, so every row has to wait for the one after it.
        int length = 20_000;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            rows.add(row("E" + (length - i)));
        }
        List<InFileReferences.Reference> references = new ArrayList<>();
        for (int i = 0; i < length - 1; i++) {
            references.add(manager(rows.get(i), rows.get(i + 1)));
        }

        InFileReferences.settle(references, true);
        List<List<Integer>> layers = InFileReferences.layers(rows, references);

        assertThat(rows).noneMatch(r -> r.containsKey(FileConstant.FAILED_REASON));
        assertThat(layers).hasSize(length);
        assertThat(layers.getFirst()).containsExactly(length - 1);
        assertThat(layers.getLast()).containsExactly(0);
    }

    @Test
    void aLongLoopIsFoundWithoutRecursingAlongIt() {
        int length = 20_000;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            rows.add(row("E" + i));
        }
        List<InFileReferences.Reference> references = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            references.add(manager(rows.get(i), rows.get((i + 1) % length)));
        }

        InFileReferences.settle(references, true);

        assertThat(rows).allMatch(r -> r.containsKey(FileConstant.FAILED_REASON));
    }

    @Test
    void aHandlerCanAskWhichRowOfTheFileARowNames() {
        // Before the write fills it in, the field is empty: a department whose parent is in the same
        // file looks like a root to any rule that reads it.
        Map<String, Object> child = row("D2");
        Map<String, Object> parent = row("D1");
        List<InFileReferences.Reference> references = List.of(new InFileReferences.Reference(
                child, "parentId", "Parent Department", List.of("code"), List.of("D1"), parent));
        List<Object> seen = new ArrayList<>();

        InFileReferences.exposedTo(references, () -> {
            seen.add(InFileReferences.targetOf(child, "parentId"));
            seen.add(InFileReferences.targetOf(parent, "parentId"));
            seen.add(InFileReferences.targetOf(child, "managerId"));
        });

        assertThat(seen.get(0)).isSameAs(parent);
        assertThat(seen.get(1)).isNull();
        assertThat(seen.get(2)).isNull();
        // Outside a handler there is nothing to ask about.
        assertThat(InFileReferences.targetOf(child, "parentId")).isNull();
    }

    @Test
    void comparesKeysAsTheyReadNotAsTheCellHeldThem() {
        assertThat(InFileReferences.ownKey(Map.of("code", " E1 "), List.of("code"))).containsExactly("E1");
        assertThat(InFileReferences.ownKey(Map.of("code", 1001), List.of("code"))).containsExactly("1001");
        assertThat(InFileReferences.ownKey(Map.of("code", "  "), List.of("code"))).isNull();
    }
}
