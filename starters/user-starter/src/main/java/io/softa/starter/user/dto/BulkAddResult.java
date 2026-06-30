package io.softa.starter.user.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of POST /admin/user-role/bulk.
 * Partial-success: each pair is committed via its own SAVEPOINT. Skipped pairs
 * carry a technical reason (USER_INACTIVE / ALREADY_ASSIGNED / FK error / ...) —
 * backend never carries compat semantics; that's frontend-only (classifyRoleForUser).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Bulk user-role assignment result")
public class BulkAddResult {

    @Schema(description = "Successfully inserted pairs")
    private List<AddedItem> added;

    @Schema(description = "Skipped pairs with technical reason")
    private List<SkippedItem> skipped;

    @Schema(description = "Summary counts")
    private Summary summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Added pair")
    public static class AddedItem {

        @Schema(description = "User ID")
        private Long userId;

        @Schema(description = "Role ID")
        private Long roleId;

        @Schema(description = "Inserted user_role row ID")
        private Long userRoleId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Skipped pair")
    public static class SkippedItem {

        @Schema(description = "User ID")
        private Long userId;

        @Schema(description = "Role ID")
        private Long roleId;

        @Schema(description = "Skip reason: USER_INACTIVE / ROLE_INACTIVE / USER_NOT_FOUND / ROLE_NOT_FOUND / ALREADY_ASSIGNED / CROSS_TENANT")
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Counts")
    public static class Summary {

        private int requested;
        private int added;
        private int skipped;
    }
}
