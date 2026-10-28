package io.softa.framework.orm.startup;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link StartupSnapshot}s, once per process: after every bean exists and before the application takes
 * traffic, with the {@link StartupConvergence}s run first, so every snapshot is built from rows that match the
 * running build.
 *
 * <p>Why a lifecycle phase and not each snapshot's own {@code @PostConstruct}: a bean is initialized when the
 * dependency graph reaches it, and converging rows needs the whole write pipeline, which the snapshots are tangled
 * into ({@code ModelService ← PermissionService ← the caches}). No initialization order can promise "converge
 * first, then build" — and a snapshot built too early is stale or empty without a word. Here every bean exists,
 * the convergences run, and then the snapshots build, whatever depends on what.
 *
 * <p>{@link #PHASE} sits above the infrastructure that starts early — Redis' connection factory starts at phase 0
 * — and below whatever hands work to the application: the embedded web server (phase {@code 2147481599} in Spring
 * Boot 4.1) and the message listener containers ({@code Integer.MAX_VALUE}). Scheduled tasks and runners start
 * once the context has refreshed, later still. Nothing reads a snapshot before it is built, and a failure here — a
 * convergence that cannot load, a snapshot that cannot read its rows — stops the start before any request is
 * served: in a rolling deploy, the instance it replaces keeps serving.
 */
@Slf4j
@Component
public class StartupSnapshots implements SmartLifecycle {

    /** Below the embedded web server's phase, above the connection factories'. */
    public static final int PHASE = Integer.MAX_VALUE - 4096;

    private static final ScopedValue<Boolean> CONVERGING = ScopedValue.newInstance();

    private final ObjectProvider<StartupConvergence> convergences;
    private final ObjectProvider<StartupSnapshot> snapshots;

    private volatile Set<String> sourceModels;
    private volatile boolean running;

    public StartupSnapshots(ObjectProvider<StartupConvergence> convergences,
                            ObjectProvider<StartupSnapshot> snapshots) {
        this.convergences = convergences;
        this.snapshots = snapshots;
    }

    /** Converge the rows, then build every snapshot from them. A failure is thrown and stops the start. */
    @Override
    public void start() {
        long started = System.currentTimeMillis();
        List<StartupConvergence> orderedConvergences = convergences.orderedStream().toList();
        ScopedValue.where(CONVERGING, Boolean.TRUE)
                .run(() -> orderedConvergences.forEach(StartupConvergence::converge));
        List<StartupSnapshot> orderedSnapshots = snapshots.orderedStream().toList();
        for (StartupSnapshot snapshot : orderedSnapshots) {
            snapshot.build();
        }
        running = true;
        log.info("StartupSnapshots: {} convergence(s) run, {} snapshot(s) built in {} ms",
                orderedConvergences.size(), orderedSnapshots.size(), System.currentTimeMillis() - started);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** The models the snapshots are built from, which only the startup convergence may write. */
    public Set<String> sourceModels() {
        Set<String> models = sourceModels;
        if (models == null) {
            models = snapshots.orderedStream()
                    .flatMap(snapshot -> snapshot.sourceModels().stream())
                    .collect(Collectors.toUnmodifiableSet());
            sourceModels = models;
        }
        return models;
    }

    /** Whether the calling thread runs inside the startup convergence, the one time a source model is written. */
    public static boolean converging() {
        return CONVERGING.isBound();
    }
}
