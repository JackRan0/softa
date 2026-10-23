package io.softa.starter.seed.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A seed data operation started in the background; follow it by its batch.
 */
@Schema(name = "BatchStarted", description = "A seed data operation started in the background; follow it by its batch")
public record BatchStarted(

        @Schema(description = "The batch: GET /SeedData/batches/running while it runs, the SeedLoadBatch row afterwards")
        Long batchId
) {
}
