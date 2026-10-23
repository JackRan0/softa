package io.softa.starter.seed.dto;

import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tenant seed file a tenant is due, against what the tenant last ran of it.
 */
@Schema(name = "TenantFileStatus", description = "A tenant seed file a tenant is due, against what the tenant last "
        + "ran of it")
public record TenantFileStatus(

        @Schema(description = "File name, as the manifest and the load records name it")
        String fileName,

        @Schema(description = "The plan module the file is for; null for every plan")
        String module,

        @Schema(description = "The countries the file is for; empty for every country")
        List<String> countries,

        @Schema(description = "Whether the file runs again when it changes (overwrite: true in the manifest)")
        boolean overwrite,

        @Schema(description = "Where the file stands in this tenant; NOT_LOADED and FAILED run next time, and so does "
                + "CHANGED for an overwrite file")
        FileState state,

        @Schema(description = "SHA-256 of the file in the running build, CRLF read as LF")
        String checksum,

        @Schema(description = "Checksum of the version the tenant last ran successfully; null when never run")
        String loadedChecksum,

        @Schema(description = "When the tenant last ran it successfully")
        LocalDateTime loadedAt,

        @Schema(description = "Why the latest attempt failed, when it did")
        String lastError
) {
}
