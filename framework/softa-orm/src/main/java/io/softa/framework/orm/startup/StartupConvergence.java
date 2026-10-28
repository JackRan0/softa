package io.softa.framework.orm.startup;

/**
 * Brings database rows up to date with the running build at startup, before any {@link StartupSnapshot} is built
 * from them — seed-starter's load of the seed files the build owns, for one.
 *
 * <p>{@link StartupSnapshots} runs them in {@code @Order}, before the application takes traffic, and they are the
 * only writers the snapshots' source models accept. A failure is thrown: the application does not start on rows
 * that do not match the build.
 */
public interface StartupConvergence {

    /** Bring the rows up to date. */
    void converge();
}
