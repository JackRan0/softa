package io.softa.starter.studio.release.desired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.softa.starter.metadata.checksum.AggregateChecksumIndex;
import io.softa.starter.studio.release.dto.MetaKeys;

/**
 * Aggregate-level desired-state comparison: assemble the
 * per-meta-table rows into business aggregates (a Model = its model row + its field rows
 * + its index rows; an OptionSet = its set row + its item rows), reduce each aggregate to
 * its {@link io.softa.starter.metadata.checksum.AggregateChecksum}, and classify each aggregate by WHERE it lives between the
 * design (desired) and runtime (observed) sides — direction-neutral (see {@link AggregateChecksumDiff.Delta}). The
 * {@code identical} set is the skip set; what to do with the rest is the caller's call
 * (deploy DELETES runtime-only to converge — safe because studio is the complete source of
 * truth for the app's metadata; import treats runtime-only as new-to-studio).
 *
 * <p>Both lanes link children to their parent by the <b>business key</b> ({@code modelName} /
 * {@code optionSetCode}) — the same key {@link DesignAggregateDiffer} pairs rows by. This is
 * load-bearing for the R5 checksum gate: the gate (this class) and the row differ MUST assemble
 * identical aggregates, or the gate could report a child-bearing aggregate "identical" while the
 * differ still has a row change for it (a child linked by the studio's surrogate {@code modelId}
 * would be dropped from the checksum if its {@code modelId} were null/dangling, yet the differ keys
 * it by {@code modelName.fieldName} — silent missed change). Linking both sides by business key
 * closes that gap; the studio's surrogate {@code design_field.modelId} is irrelevant here (it is not
 * a checksum attr). Each side reduces to an aggregate keyed by the business key, so a design
 * aggregate and its runtime counterpart hash equal iff their schema-relevant state is equal
 * (pinned by {@code CrossLaneChecksumGoldenTest}).
 *
 * <p>Rows are camelCase attribute-keyed maps (as returned by {@code modelService.searchList}
 * / the runtime export), matching the checksum allow-lists. Pure / stateless.
 */
public final class AggregateChecksumDiff {

    private AggregateChecksumDiff() {
    }

    // The business keys both lanes link children by (camelCase, derived once in MetaKeys via method refs so
    // a rename breaks compilation, not silently). These are also the OUTPUT keys, making the two sides
    // comparable. Kept here (package-private) so DesignRows / DesignAggregateDiffer resolve them via this class.
    static final String MODEL_NAME = MetaKeys.MODEL_NAME;
    static final String OPTION_SET_CODE = MetaKeys.OPTION_SET_CODE;

    /**
     * Direction-neutral aggregate classification between two {@code businessKey → checksum}
     * maps. Sets are named by WHERE the aggregate lives; the ACTION depends on the operation:
     * <ul>
     *   <li>{@code onlyInDesign} — studio has it, runtime doesn't.</li>
     *   <li>{@code differing} — present both sides, checksums differ.</li>
     *   <li>{@code onlyInRuntime} — runtime has it, studio doesn't.</li>
     *   <li>{@code identical} — present both sides with equal checksum (the skip set).</li>
     * </ul>
     * <b>Deploy</b> (design→runtime) converges the runtime to <i>exactly</i> design:
     * {@code onlyInDesign}→CREATE, {@code differing}→OVERWRITE, {@code onlyInRuntime}→DELETE
     * (catalog rows removed; the physical table/column DROP stays warn-only per never-auto-DROP).
     * Deleting {@code onlyInRuntime} is what makes the env actually converge — ignoring it would
     * leave permanent drift — and is safe because studio is the COMPLETE source of truth for the
     * app's metadata (framework / system models are maintained through studio too): the export
     * returns the full {@code appCode}-scoped catalog, so {@code onlyInRuntime} is a genuinely
     * removed aggregate, not one belonging to a separate channel.
     * <p>
     * <b>Import</b> (runtime→design) is the mirror: {@code onlyInRuntime}→CREATE-in-design (new
     * to studio), {@code differing}→OVERWRITE-design, {@code onlyInDesign}→DELETE-from-design.
     */
    public record Delta(Set<String> onlyInDesign, Set<String> differing,
                        Set<String> onlyInRuntime, Set<String> identical) {

        /** True when both sides hold the same aggregates with equal checksums (nothing to converge). */
        public boolean inSync() {
            return onlyInDesign.isEmpty() && differing.isEmpty() && onlyInRuntime.isEmpty();
        }
    }

    /** Model aggregate checksums keyed by {@code modelName}; children linked by {@code modelName}. */
    public static Map<String, String> modelChecksums(List<Map<String, Object>> modelRows,
                                                      List<Map<String, Object>> fieldRows,
                                                      List<Map<String, Object>> indexRows) {
        return AggregateChecksumIndex.models(modelRows, fieldRows, indexRows, MODEL_NAME, MODEL_NAME);
    }

    /** OptionSet aggregate checksums keyed by {@code optionSetCode}; items linked by {@code optionSetCode}. */
    public static Map<String, String> optionSetChecksums(List<Map<String, Object>> setRows,
                                                          List<Map<String, Object>> itemRows) {
        return AggregateChecksumIndex.optionSets(setRows, itemRows, OPTION_SET_CODE, OPTION_SET_CODE);
    }

    /** Classify design (desired) vs runtime (observed) aggregate checksums (direction-neutral). */
    public static Delta diff(Map<String, String> design, Map<String, String> runtime) {
        Set<String> onlyInDesign = new TreeSet<>();
        Set<String> differing = new TreeSet<>();
        Set<String> onlyInRuntime = new TreeSet<>();
        Set<String> identical = new TreeSet<>();
        for (Map.Entry<String, String> e : design.entrySet()) {
            String rt = runtime.get(e.getKey());
            if (rt == null) {
                onlyInDesign.add(e.getKey());
            } else if (rt.equals(e.getValue())) {
                identical.add(e.getKey());
            } else {
                differing.add(e.getKey());
            }
        }
        for (String key : runtime.keySet()) {
            if (!design.containsKey(key)) {
                onlyInRuntime.add(key);
            }
        }
        return new Delta(onlyInDesign, differing, onlyInRuntime, identical);
    }

}
