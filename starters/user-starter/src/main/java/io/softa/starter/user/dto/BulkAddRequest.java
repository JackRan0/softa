package io.softa.starter.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import io.softa.starter.user.enums.RoleSource;

/**
 * Request body for {@code POST /admin/user-role/bulk} — the Wizard's
 * entry C (M users × N roles matrix flattened to explicit pairs).
 *
 * <p>The FE pre-classifies pairs via {@code classifyRoleForUser} and the
 * bulk decision dialog, then submits only the pairs the admin
 * confirmed. Backend applies partial-success semantics — each pair
 * commits via its own SAVEPOINT; failures populate {@code skipped[]} on
 * the {@link BulkAddResult} response with a technical reason but don't
 * abort the batch.
 *
 * <p>{@code source} defaults to {@link RoleSource#MANUAL} when omitted —
 * DYNAMIC rows are rebuilt by {@code DynamicRoleSyncJob} and shouldn't
 * be created through this endpoint.
 */
@Schema(description = "Entry C body — explicit user-role pairs (≤ 1000)")
public record BulkAddRequest(
        @NotEmpty
        @Schema(description = "User-role pairs (≤ 1000)")
        List<@Valid UserRolePair> pairs,

        @Schema(description = "Source; defaults to MANUAL when omitted")
        RoleSource source
) {
}
