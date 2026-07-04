package io.softa.starter.studio.release.desired;

import java.util.Set;

/**
 * Which aggregates a {@link io.softa.starter.studio.release.connector.Connector#readSchema(String,
 * AggregateSelection) selective read} should fetch from the target — the incremental-fetch
 * narrowing. Keyed by aggregate-root business key: {@code modelNames} for the Model aggregate (its
 * model / field / index rows), {@code optionSetCodes} for the OptionSet aggregate (its set / item rows).
 *
 * <p>The {@link DesiredStateConverger} builds this from the checksum {@link AggregateChecksumDiff.Delta}
 * the gate already computed — only the {@code differing} and {@code onlyInRuntime} aggregates need a
 * runtime fetch ({@code identical} is skipped, {@code onlyInDesign} is created from the design side with
 * no fetch). So the export payload is proportional to the change surface, not the catalog size.
 *
 * @param modelNames     Model-aggregate business keys to fetch (empty = fetch no model rows)
 * @param optionSetCodes OptionSet-aggregate business keys to fetch (empty = fetch no option-set rows)
 */
public record AggregateSelection(Set<String> modelNames, Set<String> optionSetCodes) {

    /** True when nothing needs fetching from the target (a pure create / no-op read). */
    public boolean isEmpty() {
        return modelNames.isEmpty() && optionSetCodes.isEmpty();
    }
}
