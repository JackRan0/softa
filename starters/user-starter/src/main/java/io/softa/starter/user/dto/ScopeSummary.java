package io.softa.starter.user.dto;

import java.util.List;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.user.enums.ScopeType;

/**
 * Aggregated scope summary for a role — flattens all role_navigation rows into
 * a single shape the frontend can feed into classifyRoleForUser().
 *
 * Aggregation is performed in the frontend via React Query select hook (see
 * design v4 §1.3.3). Backend GET /admin/role returns roleNavigations array;
 * frontend derives ScopeSummary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aggregated scope summary across all role_navigation rows of a role")
public class ScopeSummary {

    @Schema(description = "Has any static rule (admin-fixed, no principal dependency)")
    private boolean hasStatic;

    @Schema(description = "Has any dynamic rule (requires principal.employeeContext)")
    private boolean hasDynamic;

    @Schema(description = "Has any ALL rule — ScopeFilterAspect short-circuits to no WHERE")
    private boolean hasAllShortCircuit;

    @Schema(description = "Union of all required principal fields across dynamic rules")
    private Set<String> requiredFieldsUnion;

    @Schema(description = "Total rule count")
    private int totalRules;

    @Schema(description = "Per-rule summary entries")
    private List<RuleEntry> rules;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One rule's summary")
    public static class RuleEntry {

        @Schema(description = "Navigation this rule attaches to")
        private String navigationId;

        @Schema(description = "Navigation display label (for UI)")
        private String navigationLabel;

        @Schema(description = "Scope type")
        private ScopeType scopeType;

        @Schema(description = "True if rule is static (not principal-dependent)")
        private boolean isStatic;

        @Schema(description = "Principal fields required when isStatic=false")
        private List<String> requiredFields;

        @Schema(description = "Anchor value (DEPT_SUBTREE deptId / CUSTOM expr / etc.) — UI display only")
        private Object anchor;

        @Schema(description = "Human-readable description (UI display)")
        private String description;
    }
}
