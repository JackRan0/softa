package io.softa.starter.permission.sensitive;

import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.startup.StartupSnapshot;
import io.softa.starter.permission.spi.SensitiveFieldSetSource;
import io.softa.starter.permission.spi.SensitiveFieldSetSource.SensitiveFieldSetDef;

/**
 * In-memory snapshot of {@code sensitive_field_set} rows, indexed for
 * O(1) lookups by both consumers in the user-starter sensitive-field
 * stack:
 *
 * <ul>
 *   <li> FieldFilter — response field mask
 *       ({@code @RestControllerAdvice}).</li>
 *   <li> FieldWriteGuardAspect — request-side write guard that
 *       rejects blocked fields (AOP around ModelService write methods).</li>
 * </ul>
 *
 * <p>A StartupSnapshot: built once at startup, after the seed files
 * the build owns have loaded and before the web server accepts requests.
 * {@code sensitive_field_set} rows change only at startup, so it never needs
 * reloading. Built that early on purpose, not on
 * {@code ApplicationReadyEvent}, which fires AFTER the server is already
 * accepting requests — a fail-OPEN window where the mask + write guard would
 * see no sensitive fields and skip masking / write rejection. For the same
 * reason a read before the build throws rather than answering "nothing is
 * sensitive".
 *
 * <p>Why this is a separate component (not inline on FieldFilter): so the
 * read advice and the write aspect read the SAME snapshot. The data shape
 * (per-model union + setId expansion) is the same for both consumers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveFieldSetCache implements StartupSnapshot {

    private final SensitiveFieldSetSource source;

    /**
     * The snapshot, published whole by {@link #build()}; null before, when there is nothing safe to answer.
     *
     * @param allSensitiveByModel   model → set of all field codes covered by ANY sensitive_field_set on that
     *                              model. The "needs grant to read/write" union — both layers consume this.
     * @param setIdToFieldCodes     setId → set of field codes (raw expansion table). Used to translate a user's
     *                              {@code modelSensitiveFieldSetsMap} grant ({@code Set<setId>}) into the actual
     *                              field-code allow-list.
     * @param setIdToModel          setId → model. Lets the consumer ignore grants that nominally exist but point
     *                              at a different model than the current request's target.
     * @param setIdToName           setId → display name. Used by the Wizard endpoint
     *                              ({@code NavigationConfigOptionsController}) so the admin UI can render SFS
     *                              checkbox labels without re-querying the {@code sensitive_field_set} table on
     *                              every wizard open.
     * @param setIdToShortName      setId → the short noun refusals name its fields by; only sets that declare one.
     * @param setIdsByAttachedModel attachmentModel → list of setIds that declared {@code attachedTo} containing
     *                              that model. Powers Wizard "extra rows" so a SFS bound to
     *                              {@code EmpBankAccount} but UI-aggregated under {@code Employee} appears as a
     *                              checkbox on the Employee nav row. NOT consumed by mask / write-guard — those
     *                              use {@code setIdToModel} (the SFS's canonical {@code model}) as the authority.
     */
    private record Sets(Map<String, Set<String>> allSensitiveByModel,
                        Map<String, Set<String>> setIdToFieldCodes,
                        Map<String, String> setIdToModel,
                        Map<String, String> setIdToName,
                        Map<String, String> setIdToShortName,
                        Map<String, Set<String>> setIdsByAttachedModel) {
    }

    private volatile Sets sets;

    @Override
    public Set<String> sourceModels() {
        return Set.of("SensitiveFieldSet");
    }

    @Override
    public void build() {
        List<SensitiveFieldSetDef> defs;
        try {
            defs = source.getSensitiveFieldSets();
        } catch (Throwable t) {
            // Fail-LOUD, not fail-OPEN. Catching and leaving the snapshot
            // empty would turn the field mask (`hasSensitiveFieldsOn` returns
            // false for every model) and the write guard (same signal) both
            // silently OFF, so every sensitive field becomes readable and
            // writable for the lifetime of this pod.
            //
            // Throwing here fails the startup → pod fails its readiness probe
            // → doesn't accept traffic → the deploy keeps the old pod, or the
            // operator restarts once the DB is reachable. Other pods that
            // loaded successfully keep serving. A DB flap during rolling
            // deploy must NOT leave a working-looking pod with sensitive-field
            // enforcement permanently disabled.
            log.error("SensitiveFieldSetCache.build — failed to load sensitive_field_set rows; "
                    + "refusing to start with an empty cache (would disable the field mask + write guard). "
                    + "Fix the DB / migration and restart.", t);
            throw new IllegalStateException(
                    "SensitiveFieldSetCache load failed — cannot start with sensitive-field enforcement disabled", t);
        }
        Map<String, Set<String>> byModel = new HashMap<>();
        Map<String, Set<String>> bySetId = new HashMap<>();
        Map<String, String> setToModel = new HashMap<>();
        Map<String, String> setToName = new HashMap<>();
        Map<String, String> setToShortName = new HashMap<>();
        Map<String, Set<String>> byAttached = new HashMap<>();
        for (SensitiveFieldSetDef s : defs) {
            if (s.id() == null || s.model() == null) continue;
            Set<String> codes = s.fieldCodes() == null ? new HashSet<>() : new HashSet<>(s.fieldCodes());
            bySetId.put(s.id(), codes);
            setToModel.put(s.id(), s.model());
            if (s.name() != null) setToName.put(s.id(), s.name());
            if (s.shortName() != null && !s.shortName().isBlank()) setToShortName.put(s.id(), s.shortName());
            byModel.computeIfAbsent(s.model(), k -> new HashSet<>()).addAll(codes);
            // Index UI attachment hints. attachedTo lets a SFS bound to
            // model A appear as a Wizard option under nav rows whose
            // primary model is B — does NOT affect mask authority.
            if (s.attachedTo() != null) {
                for (String attached : s.attachedTo()) {
                    if (attached == null || attached.isEmpty()) continue;
                    byAttached.computeIfAbsent(attached, k -> new HashSet<>()).add(s.id());
                }
            }
        }
        // Published whole — readers see either the previous snapshot or this
        // one, never half of each.
        this.sets = new Sets(Map.copyOf(byModel), Map.copyOf(bySetId), Map.copyOf(setToModel),
                Map.copyOf(setToName), Map.copyOf(setToShortName), Map.copyOf(byAttached));
        log.info("SensitiveFieldSetCache — loaded {} sensitive_field_set defs across {} models ({} attachment hints)",
                defs.size(), byModel.size(), byAttached.size());
    }

    private Sets built() {
        Sets built = sets;
        if (built == null) {
            throw new IllegalStateException("SensitiveFieldSetCache is read before it is built: it is built at "
                    + "startup, once the seed files the build owns have loaded, and nothing may ask it before.");
        }
        return built;
    }

    /** The short noun a refusal names the set's fields by ("Pay" → "Pay fields"); falls back to
     *  {@link #nameOf}, then null. */
    public String shortNameOf(String setId) {
        if (setId == null) return null;
        String shortName = built().setIdToShortName().get(setId);
        return shortName != null ? shortName : nameOf(setId);
    }

    /** Display name of a {@code setId}, or null if unknown. Powers Wizard
     *  UI rendering. */
    public String nameOf(String setId) {
        if (setId == null) return null;
        return built().setIdToName().get(setId);
    }

    /** All setIds whose canonical {@code model} equals {@code modelName}.
     *  Powers Wizard UI for the "own model" SFS rows on a nav whose primary
     *  model is {@code modelName}. */
    public Set<String> setIdsOwnedBy(String modelName) {
        if (modelName == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : built().setIdToModel().entrySet()) {
            if (modelName.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** All field codes that ANY sensitive_field_set covers on this model.
     *  Empty/missing → no sensitive fields registered (model unrestricted). */
    public Set<String> allSensitiveFieldsOn(String modelName) {
        if (modelName == null) return Set.of();
        Set<String> codes = built().allSensitiveByModel().get(modelName);
        return codes == null ? Set.of() : codes;
    }

    /** Every model with at least one sensitive field registered. */
    public Set<String> modelsWithSensitiveFields() {
        Set<String> out = new HashSet<>();
        built().allSensitiveByModel().forEach((model, codes) -> {
            if (codes != null && !codes.isEmpty()) out.add(model);
        });
        return out;
    }

    /** True iff this model has at least one sensitive_field_set defined.
     *  Hot-path optimization: callers skip the user-grant lookup entirely
     *  when the model has no sensitive fields. */
    public boolean hasSensitiveFieldsOn(String modelName) {
        if (modelName == null) return false;
        Set<String> codes = built().allSensitiveByModel().get(modelName);
        return codes != null && !codes.isEmpty();
    }

    /** Canonical {@code model} of a {@code setId}, or null if the set is
     *  unknown. Used by {@code PermissionInfoEnricher} (user-starter)
     *  to route grants into {@code modelSensitiveFieldSetsMap} keyed by the
     *  SFS's own model (not the granting nav's primary model), so a SFS
     *  attached to multiple nav rows still resolves correctly at mask time. */
    public String modelOf(String setId) {
        if (setId == null) return null;
        return built().setIdToModel().get(setId);
    }

    /** The field codes of a {@code setId}, on the set's own model; empty when the set is unknown. */
    public Set<String> fieldCodesOf(String setId) {
        if (setId == null) return Set.of();
        Set<String> codes = built().setIdToFieldCodes().get(setId);
        return codes == null ? Set.of() : codes;
    }

    /** All setIds whose {@code attachedTo} declares {@code modelName}.
     *  Powers Wizard UI aggregation — these SFS appear as additional
     *  rows under nav whose primary model is {@code modelName}, in
     *  addition to the SFS that own that model directly. Empty/missing
     *  → no UI attachments declared for this model. */
    public Set<String> setIdsAttachedTo(String modelName) {
        if (modelName == null) return Set.of();
        Set<String> ids = built().setIdsByAttachedModel().get(modelName);
        return ids == null ? Set.of() : ids;
    }

    /** The setIds bound to {@code modelName} whose field codes include {@code fieldCode} — for naming,
     *  in a refusal, the set a field belongs to. */
    public Set<String> setIdsContaining(String modelName, String fieldCode) {
        if (modelName == null || fieldCode == null) return Set.of();
        Sets built = built();
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : built.setIdToFieldCodes().entrySet()) {
            if (modelName.equals(built.setIdToModel().get(e.getKey()))
                    && e.getValue() != null && e.getValue().contains(fieldCode)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Translate granted {@code setIds} to the union of their field codes,
     *  filtered to only those sets bound to {@code modelName}. Sets bound
     *  to a different model are silently ignored — admin grants are stored
     *  per (model, setId) tuple, but the PermissionInfo flattens them per
     *  model, so a stray grant on a different model can't escape its model
     *  scope here. */
    public Set<String> grantedFieldsFor(String modelName, Set<String> grantedSetIds) {
        if (grantedSetIds == null || grantedSetIds.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        Sets built = built();
        for (String sid : grantedSetIds) {
            if (!modelName.equals(built.setIdToModel().get(sid))) continue;
            Set<String> codes = built.setIdToFieldCodes().get(sid);
            if (codes != null) out.addAll(codes);
        }
        return out;
    }

    /**
     * Per-model "fields the caller does NOT have access to" = all sensitive
     * field codes on the model − granted set's field codes. Used by both
     * the field mask FieldFilter — what to mask in the response)
     * and the write guard FieldWriteGuardAspect — what to reject
     * on write).
     *
     * <p>Returns empty set when the model registers no sensitive fields,
     * or when the granted sets cover everything sensitive on the model.
     * Hot-path callers can short-circuit on an empty result without doing
     * any per-row work.
     */
    public Set<String> computeForbiddenFields(String modelName, Set<String> grantedSetIds) {
        if (!hasSensitiveFieldsOn(modelName)) return Set.of();
        Set<String> all = allSensitiveFieldsOn(modelName);
        Set<String> granted = grantedFieldsFor(modelName, grantedSetIds);
        if (granted.isEmpty()) return all.isEmpty() ? Set.of() : new HashSet<>(all);
        Set<String> diff = new HashSet<>(all);
        diff.removeAll(granted);
        return diff;
    }

}
