package io.softa.starter.seed.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How a platform load went. Nothing is loaded when it fails: the files load in one transaction. The environment's
 * first load initializes it as well, and says what that recorded.
 */
@Schema(name = "PlatformLoadResult", description = "How a platform load went. The files load in one transaction, so "
        + "nothing is loaded when it fails. The environment's first load initializes it as well")
public record PlatformLoadResult(

        @Schema(description = "The Platform batch; null when nothing was loaded because the initialization failed")
        Long batchId,

        @Schema(description = "Whether every file loaded")
        boolean succeeded,

        @Schema(description = "The files loaded, in load order; empty when it failed")
        List<String> loadedFiles,

        @Schema(description = "The file that failed; null when it succeeded, or when it failed before the first file")
        String failedFile,

        @Schema(description = "Why it failed")
        String errorMessage,

        @Schema(description = "What the environment's first load recorded before loading; null on every later load")
        SeedInitialization initialization
) {

    /** A load in an environment that was initialized already. */
    public PlatformLoadResult(Long batchId, boolean succeeded, List<String> loadedFiles, String failedFile,
                              String errorMessage) {
        this(batchId, succeeded, loadedFiles, failedFile, errorMessage, null);
    }

    /** This load, as the one that initialized the environment. */
    public PlatformLoadResult initializing(SeedInitialization recorded) {
        return new PlatformLoadResult(batchId, succeeded, loadedFiles, failedFile, errorMessage, recorded);
    }
}
