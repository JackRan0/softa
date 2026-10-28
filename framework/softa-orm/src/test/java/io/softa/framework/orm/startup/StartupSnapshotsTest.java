package io.softa.framework.orm.startup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rows converge first, then every snapshot is built from them — and only the convergence may write them.
 */
class StartupSnapshotsTest {

    private final List<String> events = new ArrayList<>();

    @Test
    void theRowsConvergeBeforeAnySnapshotIsBuilt() {
        StartupSnapshots startup = startup(List.of(convergence("seed")), List.of(snapshot("index", "Permission"),
                snapshot("navigation", "Navigation")));

        startup.start();

        assertEquals(List.of("converge seed", "build index", "build navigation"), events);
        assertTrue(startup.isRunning());
    }

    @Test
    void onlyTheConvergenceRunsInsideTheWriteWindow() {
        StartupConvergence probe = () -> events.add("converging " + StartupSnapshots.converging());
        StartupSnapshots startup = startup(List.of(probe), List.of());

        startup.start();

        assertEquals(List.of("converging true"), events);
        assertFalse(StartupSnapshots.converging());
    }

    @Test
    void aFailedConvergenceStopsTheStartBeforeAnySnapshotIsBuilt() {
        StartupConvergence failing = () -> {
            throw new IllegalStateException("Permission.json did not load");
        };
        StartupSnapshots startup = startup(List.of(failing), List.of(snapshot("index", "Permission")));

        assertThrows(IllegalStateException.class, startup::start);
        assertTrue(events.isEmpty(), events.toString());
        assertFalse(startup.isRunning());
    }

    @Test
    void theSourceModelsAreEverySnapshotsModels() {
        StartupSnapshots startup = startup(List.of(), List.of(snapshot("index", "Permission", "Navigation"),
                snapshot("navigation", "Navigation")));

        assertEquals(Set.of("Permission", "Navigation"), startup.sourceModels());
    }

    @SuppressWarnings("unchecked")
    private static StartupSnapshots startup(List<StartupConvergence> convergences, List<StartupSnapshot> snapshots) {
        ObjectProvider<StartupConvergence> convergenceProvider = mock(ObjectProvider.class);
        when(convergenceProvider.orderedStream()).thenAnswer(invocation -> convergences.stream());
        ObjectProvider<StartupSnapshot> snapshotProvider = mock(ObjectProvider.class);
        when(snapshotProvider.orderedStream()).thenAnswer(invocation -> snapshots.stream());
        return new StartupSnapshots(convergenceProvider, snapshotProvider);
    }

    private StartupConvergence convergence(String name) {
        return () -> events.add("converge " + name);
    }

    private StartupSnapshot snapshot(String name, String... models) {
        return new StartupSnapshot() {
            @Override
            public Set<String> sourceModels() {
                return Set.copyOf(Stream.of(models).toList());
            }

            @Override
            public void build() {
                events.add("build " + name);
            }
        };
    }
}
