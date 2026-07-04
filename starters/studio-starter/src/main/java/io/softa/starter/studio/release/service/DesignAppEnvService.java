package io.softa.starter.studio.release.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.desired.MergeSelection;
import io.softa.starter.studio.release.dto.AggregateChangeReport;
import io.softa.starter.studio.release.dto.DriftEnvelopeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * DesignAppEnv Model Service Interface
 */
public interface DesignAppEnvService extends EntityService<DesignAppEnv, Long> {

    /**
     * Operator-perspective drift view for the UI, computed <b>on demand</b>. Reshapes the
     * deploy-direction diff into rows carrying {@code expected} (design) and {@code actual} (runtime)
     * sides, tagged with a {@code DriftKind} (RUNTIME_ADDED / RUNTIME_DELETED / RUNTIME_MODIFIED). A
     * runtime-unreachable / diff failure is reported as a {@code FAILURE} envelope, not thrown.
     *
     * @param envId Environment ID
     * @return drift envelope; {@code reports} is empty when there is no drift
     */
    DriftEnvelopeDTO getDriftEnvelope(Long envId);

    /**
     * Runtime-drift preview: how the runtime has drifted from the last deployed state —
     * <b>runtime vs the last PUBLISH snapshot</b>, projected as an {@link AggregateChangeReport} reading
     * base(snapshot) → after(runtime). Read-only, for the UI; deploy still converges to the live runtime.
     * An empty report when the env has never been published (no baseline); option sets are excluded for a
     * physical (JDBC) runtime, which cannot observe them.
     *
     * @param envId Environment ID
     * @return aggregate-grouped runtime drift; empty when there is no baseline or no drift
     */
    AggregateChangeReport previewRuntimeDrift(Long envId);

    /**
     * Issue a fresh Ed25519 keypair for the given env and return the base64-encoded
     * public key for the operator to install on the runtime side.
     * <p>
     * The private key is written to {@code DesignAppEnv.privateKey} (ORM-encrypted at
     * rest) and never returned by any read API. The runtime trusts the new key once
     * the operator has updated its {@code system.metadata.public-key}
     * entry —
     * since each runtime pairs with exactly one env, this is an atomic swap rather
     * than a multi-key rotation.
     *
     * @param envId Environment ID
     * @return the newly-issued public key, base64-encoded X.509
     */
    IssuedKey issueKey(Long envId);

    /**
     * Overwrite design-time metadata with the current runtime state of the given env
     * (import-from-runtime). Covers (a) first-time init where design-time is empty and the runtime
     * already carries the app's metadata, and (b) drift repair where the operator accepts the runtime
     * as the new truth.
     * <p>
     * Acquires the per-env mutex, computes the drift <b>fresh</b> on demand (no cache), and
     * inverts it onto the Design models — runtime-only rows → CREATE, matched-but-different → UPDATE
     * with runtime values, design-only → DELETE (inserts parent→child, deletes child→parent) — then
     * releases the mutex. No-op when the env is already in sync.
     *
     * @param envId Environment ID
     */
    void applyDrift(Long envId);

    /**
     * Seed (clone) a target env's full design from a source env (per-env design).
     * <p>
     * The pure-peer model has no canonical workspace: a new env is initialised by cloning an
     * existing one. This also backfills the Phase-1 migration — after V16 assigns the legacy
     * single workspace to each app's canonical active env, the operator seeds the remaining
     * active envs from that canonical one so every env owns a full design set. Every cloned row
     * gets a fresh per-env id with parent FKs remapped, and copies the source row's business-key
     * columns verbatim (the cross-env correlation key).
     * <p>
     * <b>Idempotent / non-destructive</b>: refuses to seed a target that already has design rows
     * (returns 0) so re-running is safe and an established env is never clobbered. Source and
     * target must belong to the same app.
     *
     * @param targetEnvId the env to seed (must currently have no design rows)
     * @param sourceEnvId the env to clone from
     * @return number of design rows created (0 if the target was already populated)
     */
    int seedFromSource(Long targetEnvId, Long sourceEnvId);

    /**
     * Env↔env merge (Phase 3): single-direction, overwrite-style converge of {@code targetEnvId}'s
     * design to {@code sourceEnvId}'s, correlated by <b>business key</b> (no three-way merge): aggregates
     * only in the source are created in the target, shared aggregates whose business attrs differ are
     * updated in place (a field / optionItem rename bridged by {@code renamedFrom}), and aggregates only
     * in the target are deleted. Applies only the chosen aggregate roots (and their children); a {@code
     * null}/empty {@code selection} is a full merge. Source and target must belong to the same app.
     * <p>
     * Runs in a single transaction under the target env mutex ({@code STABLE} → {@code MERGING}); there
     * is no remote RPC (design↔design is Studio-local), so a failure rolls back wholesale. Writes a
     * {@link io.softa.starter.studio.release.entity.DesignActivity} of kind {@code MERGE} whose change set
     * reflects exactly the applied (selected) changes. The target's runtime is unaffected until a
     * subsequent {@link #publish(Long)}.
     *
     * @param sourceEnvId the env whose design is merged from
     * @param targetEnvId the env whose design is converged to (mutex acquired here)
     * @param selection   the aggregate roots (by business key) to merge; {@code null}/empty = full
     * @return the DesignActivity audit record id
     */
    Long merge(Long sourceEnvId, Long targetEnvId, MergeSelection selection);

    /**
     * Publish this env's design to its runtime: converge the runtime catalog to the env's
     * per-env design rows via a business-key design↔runtime diff → rename-aware DDL + whole-aggregate
     * overwrite. Runs under the per-env deploy mutex; renames currently degrade to drop+add (in-place
     * rename is Phase 4).
     *
     * @param envId Environment ID to publish
     */
    void publish(Long envId);

    /**
     * Retry a FAILED publish by re-publishing its env. Operates on the
     * {@link io.softa.starter.studio.release.entity.DesignActivity} audit record.
     *
     * @param activityId the FAILURE PUBLISH activity to retry
     */
    void retryPublish(Long activityId);

    /**
     * Cancel a stuck (RUNNING) publish activity and release its env mutex. No automatic
     * rollback (roll-forward only) — an operator escape hatch for an env pinned in {@code DEPLOYING}.
     *
     * @param activityId the RUNNING PUBLISH activity to cancel
     */
    void cancelPublish(Long activityId);

    /**
     * Roll an env back to the design captured by a prior activity (restore): overwrite the
     * env's per-env {@code design_*} rows from that activity's {@link
     * io.softa.starter.studio.release.entity.DesignSnapshot} (the business-key columns carried verbatim),
     * then {@link #publish(Long)} to converge the runtime. The restore-publish records its own PUBLISH
     * activity (+ snapshot). Any succeeded activity that captured a snapshot is restorable
     * (PUBLISH / MERGE / IMPORT / REVERSE all snapshot their post-operation design).
     *
     * @param activityId the activity whose post-operation snapshot to restore
     */
    void restore(Long activityId);

    /**
     * Plaintext return payload for {@link #issueKey(Long)} — never holds the private
     * half, which stays on the server.
     */
    record IssuedKey(String publicKey) {}
}
