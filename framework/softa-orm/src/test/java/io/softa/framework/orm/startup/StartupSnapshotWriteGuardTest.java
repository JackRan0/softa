package io.softa.framework.orm.startup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A model a startup snapshot is built from accepts writes from the startup convergence and from nothing else.
 */
class StartupSnapshotWriteGuardTest {

    private final StartupSnapshotWriteGuard guard;

    StartupSnapshotWriteGuardTest() {
        StartupSnapshots snapshots = mock(StartupSnapshots.class);
        when(snapshots.sourceModels()).thenReturn(Set.of("Permission"));
        guard = new StartupSnapshotWriteGuard(snapshots);
    }

    @Test
    void itGuardsOnlyTheSnapshotsModels() {
        assertTrue(guard.supports("Permission"));
        assertFalse(guard.supports("Currency"));
    }

    @Test
    void aWriteAfterStartupIsRefused() {
        WriteValidationException refused = assertThrows(WriteValidationException.class,
                () -> guard.validateCreate(create("Permission")));

        assertTrue(refused.getMessage().contains("at startup"), refused.getMessage());
        assertThrows(WriteValidationException.class, () -> guard.validateUpdate(create("Permission")));
        assertThrows(WriteValidationException.class, () -> guard.validateDelete("Permission", List.of("p1")));
    }

    @Test
    void theStartupConvergenceWrites() {
        StartupConvergence seedLoad = () -> {
            guard.validateCreate(create("Permission"));
            guard.validateUpdate(create("Permission"));
            guard.validateDelete("Permission", List.of("p1"));
        };

        assertDoesNotThrow(() -> startupRunning(seedLoad).start());
    }

    private static WriteContext create(String model) {
        return WriteContext.of(model, AccessType.CREATE, Map.of("id", "p1"), null);
    }

    @SuppressWarnings("unchecked")
    private static StartupSnapshots startupRunning(StartupConvergence convergence) {
        ObjectProvider<StartupConvergence> convergences = mock(ObjectProvider.class);
        when(convergences.orderedStream()).thenAnswer(invocation -> Stream.of(convergence));
        ObjectProvider<StartupSnapshot> snapshots = mock(ObjectProvider.class);
        when(snapshots.orderedStream()).thenAnswer(invocation -> Stream.empty());
        return new StartupSnapshots(convergences, snapshots);
    }
}
