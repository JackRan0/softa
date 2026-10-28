package io.softa.framework.orm.startup;

import java.util.Set;

/**
 * An in-memory structure built from database rows once per process — an endpoint index, a lookup table — that
 * requests then read without going back to the database.
 *
 * <p>{@link StartupSnapshots} builds every one of them after the {@link StartupConvergence}s have brought the
 * rows up to date with the running build, and before the application takes any traffic. The rows therefore change
 * only at startup: {@link StartupSnapshotWriteGuard} refuses a write to one of the {@link #sourceModels()} at any
 * other time, because no snapshot would see it before the next start.
 *
 * <p>A snapshot read before it is built has nothing safe to answer — an empty endpoint index refuses every
 * request, an empty set of sensitive fields masks nothing — so it throws instead.
 *
 * <p>Snapshots build in {@code @Order}; one that is built from another orders itself after it.
 */
public interface StartupSnapshot {

    /** The models whose rows the snapshot is built from. */
    Set<String> sourceModels();

    /**
     * Build the snapshot from the rows as they are now, replacing any earlier one. A failure is thrown: the
     * application does not start without the snapshot.
     */
    void build();
}
