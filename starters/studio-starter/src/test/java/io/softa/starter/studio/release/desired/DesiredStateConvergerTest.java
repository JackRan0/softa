package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.studio.release.connector.Connector;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.dto.RowChangeOp;

/**
 * Incremental fetch: the converger drives the diff <i>per aggregate</i> from the
 * checksum {@link AggregateChecksumDiff.Delta} the gate computed — fetching only {@code differing ∪
 * onlyInRuntime} and restricting the design side to {@code onlyInDesign ∪ differing}. A mocked
 * {@link DesiredStateComparator} supplies the classification; a real {@link DesignAggregateDiffer}
 * proves the emitted changes are exactly the full-catalog diff, restricted.
 */
class DesiredStateConvergerTest {

    private static Map<String, Object> model(long id, String name, String table) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("modelName", name);
        m.put("tableName", table);
        return m;
    }

    private static Map<String, Object> optionSet(long id, String code) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("optionSetCode", code);
        return m;
    }

    private static DesiredStateComparator.Result result(AggregateChecksumDiff.Delta models) {
        return new DesiredStateComparator.Result(models,
                new AggregateChecksumDiff.Delta(Set.of(), Set.of(), Set.of(), Set.of()));
    }

    private static DesiredStateComparator.Result result(AggregateChecksumDiff.Delta models,
                                                        AggregateChecksumDiff.Delta optionSets) {
        return new DesiredStateComparator.Result(models, optionSets);
    }

    /** A stable signature for a change so two diff runs can be compared without RowChangeDTO.equals. */
    private static String sig(RowChangeDTO c) {
        Object key = c.getFullRow().getOrDefault("modelName", c.getFullRow().get("optionSetCode"));
        return c.getOp() + ":" + c.getTable() + ":" + key;
    }

    @Test
    @DisplayName("in sync → no changes and no schema read at all")
    void inSyncShortCircuits() {
        DesiredStateComparator comparator = mock(DesiredStateComparator.class);
        Connector connector = mock(Connector.class);
        DesignRows design = new DesignRows(List.of(model(1, "A", "a")), List.of(), List.of(), List.of(), List.of());
        when(comparator.compare(connector, "app", design)).thenReturn(
                result(new AggregateChecksumDiff.Delta(Set.of(), Set.of(), Set.of(), Set.of("A"))));

        List<RowChangeDTO> changes = new DesiredStateConverger(comparator, new DesignAggregateDiffer())
                .computeChanges(connector, "app", design);

        assertEquals(List.of(), changes);
        verify(connector, never()).readSchema(any());
        verify(connector, never()).readSchema(any(), any());
    }

    @Test
    @DisplayName("only-in-design aggregates CREATE with no runtime fetch")
    void onlyInDesignNeedsNoFetch() {
        DesiredStateComparator comparator = mock(DesiredStateComparator.class);
        Connector connector = mock(Connector.class);
        DesignRows design = new DesignRows(List.of(model(3, "C", "c")), List.of(), List.of(), List.of(), List.of());
        when(comparator.compare(connector, "app", design)).thenReturn(
                result(new AggregateChecksumDiff.Delta(Set.of("C"), Set.of(), Set.of(), Set.of())));

        List<RowChangeDTO> changes = new DesiredStateConverger(comparator, new DesignAggregateDiffer())
                .computeChanges(connector, "app", design);

        // CREATE C, and the fetch was empty so the connector was never touched for a schema read.
        assertEquals(1, changes.size());
        assertEquals(RowChangeOp.CREATE, changes.getFirst().getOp());
        assertEquals("C", changes.getFirst().getFullRow().get("modelName"));
        verify(connector, never()).readSchema(any(), any());
    }

    @Test
    @DisplayName("fetches only differing ∪ onlyInRuntime, and emits exactly the full-catalog diff restricted")
    void selectiveFetchEqualsFullDiff() {
        // A identical · B differing · C only-in-design · D only-in-runtime.
        DesignRows design = new DesignRows(
                List.of(model(1, "A", "a"), model(2, "B", "b_new"), model(3, "C", "c")),
                List.of(), List.of(), List.of(), List.of());
        DesignRows fullRuntime = new DesignRows(
                List.of(model(1, "A", "a"), model(2, "B", "b_old"), model(4, "D", "d")),
                List.of(), List.of(), List.of(), List.of());
        // The runtime returns ONLY the selectively-fetched aggregates (B + D), never A or C.
        DesignRows fetched = new DesignRows(
                List.of(model(2, "B", "b_old"), model(4, "D", "d")),
                List.of(), List.of(), List.of(), List.of());

        DesiredStateComparator comparator = mock(DesiredStateComparator.class);
        Connector connector = mock(Connector.class);
        when(comparator.compare(connector, "app", design)).thenReturn(
                result(new AggregateChecksumDiff.Delta(Set.of("C"), Set.of("B"), Set.of("D"), Set.of("A"))));
        ArgumentCaptor<AggregateSelection> selection = ArgumentCaptor.forClass(AggregateSelection.class);
        when(connector.readSchema(eq("app"), selection.capture())).thenReturn(fetched);

        List<RowChangeDTO> changes = new DesiredStateConverger(comparator, new DesignAggregateDiffer())
                .computeChanges(connector, "app", design);

        // Fetched exactly the changed/runtime-only aggregates — A (identical) and C (create) are NOT pulled.
        assertEquals(Set.of("B", "D"), selection.getValue().modelNames());
        assertEquals(Set.of(), selection.getValue().optionSetCodes());

        // UPDATE B (tableName changed), CREATE C, DELETE D, nothing for A. (getTable() is the MetaTable
        // enum — DesignModel rows map to MetaTable.MODEL.)
        assertEquals(Set.of("UPDATE:MODEL:B", "CREATE:MODEL:C", "DELETE:MODEL:D"),
                changes.stream().map(DesiredStateConvergerTest::sig).collect(Collectors.toSet()));

        // Equivalence: identical to diffing the WHOLE design against the WHOLE runtime (A drops out as
        // a no-op there too), so the optimization changes performance, not the result.
        List<RowChangeDTO> full = new DesignAggregateDiffer().diff(design, fullRuntime);
        assertEquals(full.stream().map(DesiredStateConvergerTest::sig).collect(Collectors.toSet()),
                changes.stream().map(DesiredStateConvergerTest::sig).collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("option-set lane routes through the selection by optionSetCode, independently of the model lane")
    void optionSetLaneSelectiveFetch() {
        // Empty design, a runtime option set → onlyInRuntime (fetched, then DELETE). Exercises the
        // option-set fetch path (optionSetCode key column) that the model-only tests never hit.
        DesignRows design = new DesignRows(List.of(), List.of(), List.of(), List.of(), List.of());
        DesignRows fetched = new DesignRows(List.of(), List.of(), List.of(),
                List.of(optionSet(10, "status")), List.of());

        DesiredStateComparator comparator = mock(DesiredStateComparator.class);
        Connector connector = mock(Connector.class);
        AggregateChecksumDiff.Delta noModels = new AggregateChecksumDiff.Delta(Set.of(), Set.of(), Set.of(), Set.of());
        AggregateChecksumDiff.Delta sets = new AggregateChecksumDiff.Delta(Set.of(), Set.of(), Set.of("status"), Set.of());
        when(comparator.compare(connector, "app", design)).thenReturn(result(noModels, sets));
        ArgumentCaptor<AggregateSelection> selection = ArgumentCaptor.forClass(AggregateSelection.class);
        when(connector.readSchema(eq("app"), selection.capture())).thenReturn(fetched);

        List<RowChangeDTO> changes = new DesiredStateConverger(comparator, new DesignAggregateDiffer())
                .computeChanges(connector, "app", design);

        assertEquals(Set.of(), selection.getValue().modelNames(), "no model aggregate requested");
        assertEquals(Set.of("status"), selection.getValue().optionSetCodes());
        assertEquals(Set.of("DELETE:OPTION_SET:status"),
                changes.stream().map(DesiredStateConvergerTest::sig).collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("identical aggregate is skipped (not fetched, not emitted); the differing one still diffs")
    void identicalAggregateIsSkipped() {
        // A: content-identical (same business key + same attrs both sides). B: differing (so the env is not
        // in sync and the converger proceeds). The selective path must skip A entirely and only diff B.
        DesignRows design = new DesignRows(
                List.of(model(1, "A", "a"), model(2, "B", "b_new")),
                List.of(), List.of(), List.of(), List.of());
        DesignRows fetchedB = new DesignRows(
                List.of(model(2, "B", "b_old")), List.of(), List.of(), List.of(), List.of());

        DesiredStateComparator comparator = mock(DesiredStateComparator.class);
        Connector connector = mock(Connector.class);
        when(comparator.compare(connector, "app", design)).thenReturn(
                result(new AggregateChecksumDiff.Delta(Set.of(), Set.of("B"), Set.of(), Set.of("A"))));
        ArgumentCaptor<AggregateSelection> selection = ArgumentCaptor.forClass(AggregateSelection.class);
        when(connector.readSchema(eq("app"), selection.capture())).thenReturn(fetchedB);

        List<RowChangeDTO> changes = new DesiredStateConverger(comparator, new DesignAggregateDiffer())
                .computeChanges(connector, "app", design);

        // A (identical) is neither fetched nor emitted; only the differing aggregate B is diffed.
        assertEquals(Set.of("B"), selection.getValue().modelNames(), "A is not fetched");
        assertEquals(1, changes.size());
        assertEquals("B", changes.getFirst().getFullRow().get("modelName"));
        assertEquals(RowChangeOp.UPDATE, changes.getFirst().getOp());

        // Equivalence on the differing side: had A also been content-identical to its runtime row in a full
        // diff, A drops out as a no-op there too (pairing is purely by business key) — so the
        // selective path's result matches the full diff over the same content.
        DesignRows fullRuntime = new DesignRows(
                List.of(model(1, "A", "a"), model(2, "B", "b_old")),
                List.of(), List.of(), List.of(), List.of());
        List<RowChangeDTO> full = new DesignAggregateDiffer().diff(design, fullRuntime);
        assertEquals(Set.of("UPDATE:MODEL:B"),
                full.stream().map(DesiredStateConvergerTest::sig).collect(Collectors.toSet()));
    }
}
