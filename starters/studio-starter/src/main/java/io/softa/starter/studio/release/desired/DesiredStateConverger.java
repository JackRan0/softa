package io.softa.starter.studio.release.desired;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import io.softa.starter.studio.release.connector.Connector;
import io.softa.starter.studio.release.dto.RowChangeDTO;

/**
 * The read half of a desired-state converge: turn one env's design rows into the
 * flat {@link RowChangeDTO} change list against a target {@link Connector}, behind the R5 checksum gate.
 * Pairs with {@link DesiredStateDeployService} (the write half). Used by both publish (design↔runtime)
 * and on-demand drift; merge (design↔design) calls {@link DesignAggregateDiffer} directly since it has
 * no remote gate.
 *
 * <p><b>Incremental fetch</b>. The gate ({@link DesiredStateComparator#compare}) reads
 * only the per-aggregate checksums — one cheap call — and classifies every aggregate into the
 * {@link AggregateChecksumDiff.Delta} buckets. The converger then drives the diff <i>per aggregate</i>
 * from that classification, so the expensive schema fetch is proportional to the change surface, not the
 * catalog size:
 * <ul>
 *   <li>{@code identical} — skip: no fetch, no diff (R5 soundness: equal checksum ⇒ no row change).</li>
 *   <li>{@code onlyInDesign} — CREATE from the design side: no runtime fetch (design is in hand).</li>
 *   <li>{@code onlyInRuntime} — DELETE: fetch only these aggregates' rows.</li>
 *   <li>{@code differing} — row-level diff: fetch only these aggregates' rows.</li>
 * </ul>
 * So the observed side is {@link Connector#readSchema(String, AggregateSelection) read selectively} for
 * just {@code differing ∪ onlyInRuntime}, and the design side is restricted to the non-identical
 * aggregates ({@code onlyInDesign ∪ differing}) before the diff. When the gate reports the env wholly in
 * sync, nothing is fetched at all.
 *
 * <p><b>Why this is exactly the full-catalog diff, restricted</b>: each aggregate checksum covers the
 * <i>entire</i> aggregate (root + all children over the same {@code AggregateChecksum.*_ATTRS} the
 * differ's "changed" test uses), so <i>any</i> row change makes its aggregate non-identical. Restricting
 * the inputs to non-identical aggregates therefore never drops a row that has a pending change, and —
 * because a row's business key is unique within an env — never splits a pair across the
 * include/exclude boundary (a moved child changes both its source and destination aggregate, so both are
 * fetched). The emitted change list is identical to diffing the full catalog: an {@code identical}-checksum
 * aggregate is skipped because its two sides are byte-for-byte equal over the checksummed attrs, so there is
 * nothing to converge. (logicalId was removed, so there is no longer any "logicalId-only" difference
 * hiding under an identical checksum — identical means identical.) This matches the converge model (per-env
 * design ↔ that env's runtime) and fetches nothing when the env is wholly in sync.
 *
 * <p><b>#4 (deferred-adoption × studio rename) is closed</b>: identity is now the business key
 * + a single-step {@code renamedFrom}, with no logicalId adoption window. A studio rename pairs by business
 * key (or, when the name changed, by the prior key carried in {@code renamedFrom}) regardless of publish
 * history — so the old "never-adopted row renamed before its first content change → drop+add data divorce"
 * hazard no longer arises. (Field / optionItem renames bridge in place; model / optionSet renames, which
 * have children, are drop+add gated by {@code autoExecuteDDL} / a manual migration.)
 */
@Component
public class DesiredStateConverger {

    private final DesiredStateComparator comparator;
    private final DesignAggregateDiffer aggregateDiffer;

    public DesiredStateConverger(DesiredStateComparator comparator, DesignAggregateDiffer aggregateDiffer) {
        this.comparator = comparator;
        this.aggregateDiffer = aggregateDiffer;
    }

    /**
     * Compute the design↔target row changes for {@code design} against {@code connector}, gated by the
     * R5 checksum compare and fetching only the changed aggregates. Returns an empty list when the target
     * is already in sync.
     */
    public List<RowChangeDTO> computeChanges(Connector connector, String appCode, DesignRows design) {
        DesiredStateComparator.Result cmp = comparator.compare(connector, appCode, design);
        if (cmp.inSync()) {
            return List.of();
        }

        // Design side: the non-identical aggregates (onlyInDesign drives CREATE, differing drives the
        // row diff). Runtime side: only differing ∪ onlyInRuntime need fetching.
        AggregateChecksumDiff.Delta models = cmp.models();
        AggregateChecksumDiff.Delta optionSets = cmp.optionSets();
        DesignRows desiredSide = design.select(
                union(models.onlyInDesign(), models.differing()),
                union(optionSets.onlyInDesign(), optionSets.differing()));
        AggregateSelection fetch = new AggregateSelection(
                union(models.differing(), models.onlyInRuntime()),
                union(optionSets.differing(), optionSets.onlyInRuntime()));
        DesignRows observedSide = fetch.isEmpty() ? DesignRows.empty() : connector.readSchema(appCode, fetch);

        return aggregateDiffer.diff(desiredSide, observedSide);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }
}
