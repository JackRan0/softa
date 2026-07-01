package io.softa.framework.orm.utils;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure directed-graph algorithm tests for {@link GraphUtils}. The CASCADE-specific wiring (graph
 * construction + fail-fast messages) is covered separately in {@code ModelManagerTest}.
 */
class GraphUtilsTest {

    // ---- findCycle -----------------------------------------------------------------------------

    @Test
    void findCycle_acyclic_returnsEmpty() {
        // A -> B -> C, A -> C (diamond-free DAG)
        Map<String, List<String>> graph = Map.of(
                "A", List.of("B", "C"),
                "B", List.of("C"));
        assertThat(GraphUtils.findCycle(graph)).isEmpty();
    }

    @Test
    void findCycle_selfLoop_returnsClosedPair() {
        Map<String, List<String>> graph = Map.of("A", List.of("A"));
        assertThat(GraphUtils.findCycle(graph)).containsExactly("A", "A");
    }

    @Test
    void findCycle_twoNodeCycle_returnsClosedCycle() {
        Map<String, List<String>> graph = Map.of(
                "A", List.of("B"),
                "B", List.of("A"));
        // Starts at whichever key the map yields first; the cycle is closed (first == last).
        List<String> cycle = GraphUtils.findCycle(graph);
        assertThat(cycle).hasSize(3);
        assertThat(cycle.getFirst()).isEqualTo(cycle.getLast());
        assertThat(cycle).contains("A", "B");
    }

    @Test
    void findCycle_backEdgeDeepInGraph_isDetected() {
        // A -> B -> C -> B
        Map<String, List<String>> graph = Map.of(
                "A", List.of("B"),
                "B", List.of("C"),
                "C", List.of("B"));
        // The reported rotation depends on key iteration order (Map.of / DFS root); assert a valid
        // closed cycle over the back edge, not one specific rotation. The acyclic prefix A is excluded.
        List<String> cycle = GraphUtils.findCycle(graph);
        assertThat(cycle).hasSize(3);
        assertThat(cycle.getFirst()).isEqualTo(cycle.getLast());
        assertThat(cycle).contains("B", "C").doesNotContain("A");
    }

    // ---- longestPath ---------------------------------------------------------------------------

    @Test
    void longestPath_chain_returnsAllNodesInOrder() {
        Map<String, List<String>> graph = Map.of(
                "A", List.of("B"),
                "B", List.of("C"),
                "C", List.of("D"));
        assertThat(GraphUtils.longestPath(graph)).containsExactly("A", "B", "C", "D");
    }

    @Test
    void longestPath_diamond_countsReconvergentNodeOnce() {
        // A -> B -> D, A -> C -> D, D -> E : longest is 4 nodes (A,?,D,E), not 5.
        Map<String, List<String>> graph = Map.of(
                "A", List.of("B", "C"),
                "B", List.of("D"),
                "C", List.of("D"),
                "D", List.of("E"));
        List<String> longest = GraphUtils.longestPath(graph);
        assertThat(longest).hasSize(4);
        assertThat(longest).startsWith("A");
        assertThat(longest).endsWith("D", "E");
    }

    @Test
    void longestPath_emptyGraph_returnsEmpty() {
        assertThat(GraphUtils.longestPath(Map.<String, List<String>>of())).isEmpty();
    }

    @Test
    void longestPath_cyclicGraph_throws() {
        Map<String, List<String>> graph = Map.of(
                "A", List.of("B"),
                "B", List.of("A"));
        assertThatThrownBy(() -> GraphUtils.longestPath(graph))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acyclic");
    }
}
