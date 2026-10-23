package io.softa.starter.seed.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How a platform load went. Nothing is loaded when it fails: the files load in one transaction.
 */
@Schema(name = "PlatformLoadResult", description = "How a platform load went. The files load in one transaction, so "
        + "nothing is loaded when it fails")
public record PlatformLoadResult(

        @Schema(description = "The Platform batch")
        Long batchId,

        @Schema(description = "Whether every file loaded")
        boolean succeeded,

        @Schema(description = "The files loaded, in load order; empty when it failed")
        List<String> loadedFiles,

        @Schema(description = "The file that failed; null when it succeeded, or when it failed before the first file")
        String failedFile,

        @Schema(description = "Why it failed")
        String errorMessage
) {
}
