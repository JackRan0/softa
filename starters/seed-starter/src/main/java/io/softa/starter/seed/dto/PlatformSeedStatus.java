package io.softa.starter.seed.dto;

import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The platform seed files and what is going on with them.
 */
@Schema(name = "PlatformSeedStatus", description = "The platform seed files and what is going on with them")
public record PlatformSeedStatus(

        @Schema(description = "Version of the running build")
        String appVersion,

        @Schema(description = "When the running build was made (Spring Boot build-info); null without build-info")
        LocalDateTime buildTime,

        @Schema(description = "When the instance answering started")
        LocalDateTime instanceStartedAt,

        @Schema(description = "End of the latest successful platform load")
        LocalDateTime lastLoadedAt,

        @Schema(description = "The environment has been initialized, which its first platform load does; until then "
                + "tenant runs are refused")
        boolean initialized,

        @Schema(description = "The operation in progress, or null")
        RunningBatch runningBatch,

        @Schema(description = "Every platform file, in manifest (load) order")
        List<PlatformFileStatus> files
) {
}
