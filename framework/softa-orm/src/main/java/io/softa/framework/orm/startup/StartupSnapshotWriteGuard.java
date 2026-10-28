package io.softa.framework.orm.startup;

import java.io.Serializable;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;

/**
 * Refuses writes to the models the {@link StartupSnapshot}s are built from, except during the startup
 * convergence.
 *
 * <p>Each snapshot is built once per process, so a row written afterwards would reach no snapshot until the next
 * start — on each instance at whatever moment it restarts. Refusing the write says so at once, instead of a change
 * that looks saved and silently does not apply. These rows change where the build changes them, in the seed files
 * that load at startup.
 *
 * <p>Applied at the write roots like every {@link ModelWriteValidator}, so no path is left open: the generic
 * routes, imports, flow write nodes and direct service calls, system context included.
 */
@Component
@Order(0)
public class StartupSnapshotWriteGuard implements ModelWriteValidator {

    private final StartupSnapshots startupSnapshots;

    public StartupSnapshotWriteGuard(StartupSnapshots startupSnapshots) {
        this.startupSnapshots = startupSnapshots;
    }

    @Override
    public boolean supports(String modelName) {
        return startupSnapshots.sourceModels().contains(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        refuseOutsideConvergence(ctx.modelName());
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        refuseOutsideConvergence(ctx.modelName());
    }

    @Override
    public void validateDelete(String modelName, List<? extends Serializable> ids) {
        refuseOutsideConvergence(modelName);
    }

    private static void refuseOutsideConvergence(String modelName) {
        if (!StartupSnapshots.converging()) {
            throw new WriteValidationException("{0} rows are read into memory once, at startup, so a change made now "
                    + "would reach no instance until it restarts. They change at startup, from the seed files of "
                    + "the build.", modelName);
        }
    }
}
