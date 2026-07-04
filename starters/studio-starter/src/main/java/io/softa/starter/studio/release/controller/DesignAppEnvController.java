package io.softa.starter.studio.release.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.desired.MergeSelection;
import io.softa.starter.studio.release.dto.AggregateChangeReport;
import io.softa.starter.studio.release.dto.DriftEnvelopeDTO;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.service.DesignAppEnvService;

/**
 * DesignAppEnv Model Controller
 */
@Tag(name = "DesignAppEnv")
@RestController
@RequestMapping("/DesignAppEnv")
public class DesignAppEnvController extends EntityController<DesignAppEnvService, DesignAppEnv, Long> {

    /**
     * Compare the env's design with the actual runtime metadata for the given environment.
     * Detects drift caused by direct SQL changes, unsynced runtime modifications, etc.
     * <p>
     * Returns a drift-oriented view where each row carries {@code expected} (design) and
     * {@code actual} (runtime) sides directly with a {@link io.softa.starter.studio.release.enums.DriftKind}
     * label, instead of the deploy-direction {@code RowChangeDTO} graph used internally.
     *
     * @param id Environment ID
     * @return drift grouped by model
     */
    @Operation(description = "Compare the env's design with runtime metadata for an environment.")
    @GetMapping(value = "/compareDesignWithRuntime")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<DriftEnvelopeDTO> compareDesignWithRuntime(@RequestParam Long id) {
        return ApiResponse.success(service.getDriftEnvelope(id));
    }

    /**
     * Runtime-drift preview: how the runtime has drifted from the last deployed state —
     * runtime vs the last PUBLISH snapshot, as an aggregate-root before/after report (base = snapshot,
     * after = runtime). Read-only visualisation: a drift means someone changed the runtime out of band
     * since the last deploy. Empty when the env has never been published.
     *
     * @param id Environment ID
     * @return aggregate-grouped runtime drift
     */
    @Operation(description = "Preview how the runtime has drifted from the last deployed snapshot (runtime vs snapshot).")
    @GetMapping(value = "/previewRuntimeDrift")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<AggregateChangeReport> previewRuntimeDrift(@RequestParam Long id) {
        return ApiResponse.success(service.previewRuntimeDrift(id));
    }

    /**
     * Issue a fresh Ed25519 keypair for this env.
     * <p>
     * Writes the new private key (encrypted at rest) onto the env row and returns
     * the public key half — the operator copies this into the runtime's
     * {@code system.metadata.public-key} entry so the runtime recognises
     * requests signed with the new key. Calling this again atomically replaces the
     * keypair: previous signatures stop validating as soon as the operator updates
     * the runtime yml.
     *
     * @param id Environment ID
     * @return base64-encoded public key
     */
    @Operation(description = "Issue / rotate the Ed25519 keypair used to sign studio → runtime requests for this env.")
    @PostMapping(value = "/issueKey")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<DesignAppEnvService.IssuedKey> issueKey(@RequestParam Long id) {
        return ApiResponse.success(service.issueKey(id));
    }

    /**
     * Overwrite design-time metadata with the current runtime state of this env (computed fresh on
     * demand). Serves both operator intents, which are the same operation:
     * <ul>
     *   <li><b>drift repair</b> — accept out-of-band runtime changes as the new design-time truth;</li>
     *   <li><b>first-time import</b> — seed a new studio app from a runtime that already owns the
     *       authoritative metadata.</li>
     * </ul>
     * The service picks IMPORT (Softa connector) vs REVERSE (JDBC connector) by the env's connector type.
     *
     * @param id Environment ID
     */
    @Operation(description = "Overwrite design-time metadata with the env's current runtime state "
            + "(drift repair / first-time import).")
    @PostMapping(value = "/applyDrift")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<Void> applyDrift(@RequestParam Long id) {
        service.applyDrift(id);
        return ApiResponse.success();
    }

    /**
     * Seed (clone) this env's full design from a source env (per-env design). Used to
     * initialise a new env from an existing one, and to backfill the Phase-1 migration (seed the
     * remaining active envs from the app's canonical env). Idempotent: returns 0 without writing
     * if the target already owns design rows. Source and target must belong to the same app.
     *
     * @param id       target Environment ID (must currently have no design rows)
     * @param sourceId source Environment ID to clone from
     * @return number of design rows created (0 if the target was already populated)
     */
    @Operation(description = "Seed (clone) this env's full design from a source env. Idempotent; same-app only.")
    @PostMapping(value = "/seedFromSource")
    @Parameter(name = "id", description = "Target Environment ID")
    @Parameter(name = "sourceId", description = "Source Environment ID to clone from")
    public ApiResponse<Integer> seedFromSource(@RequestParam Long id, @RequestParam Long sourceId) {
        return ApiResponse.success(service.seedFromSource(id, sourceId));
    }

    /**
     * Publish this env's design to its runtime ({@code publish(envId)}): converge the runtime
     * catalog to the env's per-env design via a business-key design↔runtime diff → rename-aware DDL +
     * whole-aggregate overwrite. The per-env replacement for the version-based deploy.
     *
     * @param id Environment ID to publish
     */
    @Operation(description = "Publish this env's design to its runtime (design↔runtime converge).")
    @PostMapping(value = "/publish")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<Void> publish(@RequestParam Long id) {
        service.publish(id);
        return ApiResponse.success();
    }

    /**
     * Merge a source env's design into this (target) env's design (Phase 3 env↔env merge):
     * single-direction, overwrite-style converge by {@code business key}. Same-app only; writes
     * a DesignActivity (kind MERGE) audit record and returns its id. The target's runtime is unaffected
     * until a subsequent {@code /publish}.
     * <p>
     * An optional {@link MergeSelection} body restricts the merge to the chosen aggregate roots (by business
     * key — {@code modelName} / {@code optionSetCode}; selective merge); no body (or an empty
     * selection) = full merge.
     *
     * @param id        target Environment ID (converged to; mutex acquired here)
     * @param sourceId  source Environment ID (merged from)
     * @param selection optional aggregate-root selection; {@code null}/empty = full merge
     * @return the DesignActivity audit record id
     */
    @Operation(description = "Merge a source env's design into this env's design (env↔env merge, same-app). "
            + "Optional body selects aggregate roots by business key (modelName / optionSetCode).")
    @PostMapping(value = "/merge")
    @Parameter(name = "id", description = "Target Environment ID")
    @Parameter(name = "sourceId", description = "Source Environment ID to merge from")
    public ApiResponse<Long> merge(@RequestParam Long id, @RequestParam Long sourceId,
                                   @RequestBody(required = false) MergeSelection selection) {
        return ApiResponse.success(service.merge(sourceId, id, selection));
    }

    /**
     * Delete an environment and (cascade) its full per-env design workspace.
     * <p>
     * Claimed away from the generic {@code ModelController} so the delete runs through the typed
     * {@link DesignAppEnvService} — letting
     * {@link io.softa.starter.studio.release.service.impl.DesignAppEnvServiceImpl#deleteByIds} refuse a
     * {@code protectedEnv} env and cascade-delete the env's {@code design_*} rows, instead of
     * falling through to the generic delete that would leave them orphaned with a dangling {@code env_id}.
     *
     * @param id Environment ID
     */
    @Operation(description = "Delete an env and cascade-delete its per-env design workspace; refuses a protected env.")
    @PostMapping(value = "/deleteById")
    @Parameter(name = "id", description = "Environment ID")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        Assert.notNull(id, "`id` cannot be null when deleting data!");
        return ApiResponse.success(service.deleteById(id));
    }

    /**
     * Delete multiple environments, each cascading its per-env design workspace. The batch is one
     * transaction — a single {@code protectedEnv} member aborts the whole delete.
     *
     * @param ids Environment IDs
     */
    @Operation(description = "Delete envs and cascade-delete their per-env design workspaces; refuses a protected env.")
    @PostMapping(value = "/deleteByIds")
    @Parameter(name = "ids", description = "Environment IDs")
    public ApiResponse<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        Assert.allNotNull(ids, "ids cannot contain null values: {0}", ids);
        this.validateBatchSize(ids.size());
        return ApiResponse.success(service.deleteByIds(ids));
    }
}
