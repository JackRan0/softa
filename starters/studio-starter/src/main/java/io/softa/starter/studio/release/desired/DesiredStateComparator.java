package io.softa.starter.studio.release.desired;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.studio.release.connector.Connector;

/**
 * Studio-side aggregate-level desired-state comparison: given the design
 * (desired) rows, fetch the runtime's per-aggregate checksums once (the lightweight gate) and
 * classify each Model / OptionSet aggregate via {@link AggregateChecksumDiff}. The result tells
 * the deploy which aggregates to CREATE / OVERWRITE / DELETE and which to skip — so full rows
 * are pulled (and DDL computed) only for the ones that actually changed.
 *
 * <p>Intentionally decoupled from <i>where</i> the design rows come from — the caller supplies them.
 * Both sides link aggregate children by the business key ({@code modelName} / {@code optionSetCode}),
 * the same key {@link DesignAggregateDiffer} pairs rows by, so the gate and the row differ assemble
 * identical aggregates (R5 soundness: {@code inSync() ⇒ the row diff is empty}). The runtime side is
 * {@code appCode}-scoped (so {@code onlyInRuntime} is a genuinely removed aggregate safe to converge —
 * see {@link AggregateChecksumDiff.Delta}).
 */
@Component
public class DesiredStateComparator {

    /** Aggregate-level deltas for both aggregate roots. */
    public record Result(AggregateChecksumDiff.Delta models, AggregateChecksumDiff.Delta optionSets) {

        /** True when the env already matches the design — the deploy is a no-op. */
        public boolean inSync() {
            return models.inSync() && optionSets.inSync();
        }
    }

    /**
     * Compare the design rows against the target runtime via its {@link Connector}. Reads the runtime
     * checksums once (both aggregate roots) and diffs each side.
     */
    public Result compare(Connector connector, String appCode, DesignRows design) {
        RuntimeChecksumsDTO runtime = connector.readChecksums(appCode);
        Map<String, String> designModels = AggregateChecksumDiff.modelChecksums(
                design.models(), design.fields(), design.indexes());
        Map<String, String> designOptionSets = AggregateChecksumDiff.optionSetChecksums(
                design.optionSets(), design.items());
        return new Result(
                AggregateChecksumDiff.diff(designModels, runtime.models()),
                AggregateChecksumDiff.diff(designOptionSets, runtime.optionSets()));
    }
}
