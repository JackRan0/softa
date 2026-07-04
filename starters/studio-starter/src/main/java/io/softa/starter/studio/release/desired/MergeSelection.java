package io.softa.starter.studio.release.desired;

import java.util.Set;

/**
 * Selective env↔env merge input — the set of aggregate roots, by <b>business key</b>
 * ({@code modelName} / {@code optionSetCode}; logicalId removed), the operator chose to merge.
 * Granularity is the <b>aggregate root</b>: selecting a Model carries its fields and indexes; selecting an
 * OptionSet carries its items. Unselected aggregates' source changes are skipped wholesale (children too).
 *
 * <p>A {@code null} or {@link #isEmpty() empty} selection means a full merge (every changed aggregate).
 * The business key is the selection key because it is the identity the diff/preview already exposes and the
 * sole identity in the slimmed model — it aligns with "what the operator sees".
 *
 * @param modelNames      {@code modelName}s of the Model aggregates to merge
 * @param optionSetCodes  {@code optionSetCode}s of the OptionSet aggregates to merge
 */
public record MergeSelection(Set<String> modelNames, Set<String> optionSetCodes) {

    public boolean isEmpty() {
        return (modelNames == null || modelNames.isEmpty())
                && (optionSetCodes == null || optionSetCodes.isEmpty());
    }
}
