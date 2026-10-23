package io.softa.starter.seed.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The platform files to load while initializing an environment.
 */
@Schema(name = "InitializeRequest", description = "The platform files to load while initializing an environment")
public record InitializeRequest(

        @Schema(description = "Pending platform files to load, by file name (e.g. Navigation.Hcm.json). Omit for every "
                + "pending file — refused in an environment that already holds platform seed data, which names the "
                + "files the Seed Data page needs (its menu and permissions) and loads the rest from that page. An "
                + "empty list is refused.")
        List<String> fileNames
) {
}
