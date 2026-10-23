package io.softa.starter.seed.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The platform files to load.
 */
@Schema(name = "PlatformLoadRequest", description = "The platform files to load")
public record PlatformLoadRequest(

        @Schema(description = "Pending platform files to load, by file name. They load in manifest order whatever "
                + "order they are named in, and the others stay pending. Omit for every pending file; an empty list "
                + "is refused")
        List<String> fileNames
) {
}
