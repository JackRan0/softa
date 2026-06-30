package io.softa.starter.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Per-navigation Wizard stage 3 options — emitted by {@code GET
 * /admin/navigationConfigOptions} for each navigation id the FE wizard
 * needs to render. Composed from:
 *
 * <ul>
 *   <li>{@code primaryModel} / {@code primaryModelLabel} — resolved via
 *       {@code NavigationModelResolver}; {@code label} falls back to the
 *       model name when MetaModel has no display label.</li>
 *   <li>{@code applicableScopes} — every {@code ScopeType} that
 *       {@code ScopeRuleCompiler} would actually produce a non-empty
 *       filter for on this model. ALL + CUSTOM always included; the rest
 *       depend on the model's column shape.</li>
 *   <li>{@code applicableSensitiveFieldSets} — union of SFS bound to
 *       this model directly (SFS.model == nav.primaryModel) AND SFS that
 *       declared {@code attachedTo} containing this model. The
 *       {@link SfsRef} carries the minimum data the wizard renders
 *       (checkbox label + setId).</li>
 * </ul>
 *
 * <p>Returns null from the controller's {@code buildOne} when the nav has
 * no primary model (GROUP / pure container MENU) — FE renders the row
 * collapsed in that case.
 */
@Schema(description = "Per-navigation Wizard stage 3 options")
public record NavConfigOptions(
        @Schema(description = "Primary model name (PascalCase)")
        String primaryModel,

        @Schema(description = "Display label of primary model")
        String primaryModelLabel,

        @Schema(description = "Applicable scope types for this model (filtered by column availability)")
        List<String> applicableScopes,

        @Schema(description = "Sensitive field sets bound to this model directly + attached via SFS.attachedTo")
        List<SfsRef> applicableSensitiveFieldSets
) {

    /**
     * Minimum SFS reference the Wizard needs to render a checkbox row.
     * Nested inside {@link NavConfigOptions} because it has no use outside
     * this response shape — keeping it co-located avoids a 4-line file in
     * the dto/ directory.
     */
    @Schema(description = "Sensitive field set reference — id + display name")
    public record SfsRef(
            @Schema(description = "SFS id (kebab-case, e.g. 'employee-bank')")
            String id,

            @Schema(description = "Display name")
            String name
    ) {
    }
}
