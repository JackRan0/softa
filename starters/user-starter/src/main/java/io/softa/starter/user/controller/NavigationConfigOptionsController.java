package io.softa.starter.user.controller;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.NavConfigOptions;
import io.softa.starter.user.dto.NavConfigOptions.SfsRef;
import io.softa.starter.user.dto.RoleModelConfigOption;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.filter.SensitiveFieldSetCache;
import io.softa.starter.user.scope.ScopeApplicabilityResolver;
import io.softa.starter.user.service.NavigationModelResolver;

/**
 * Wizard stage 3 endpoint: per-navigation options the FE wizard needs to
 * render — model label, applicable scope types, applicable sensitive field
 * sets, and any filter-related field options.
 *
 * <h3>Data sources (all in-memory, no per-request DB scan)</h3>
 * <ul>
 *   <li><b>primary model</b> — {@link NavigationModelResolver} (boot-loaded
 *       Navigation tree).</li>
 *   <li><b>applicable scopes</b> — {@link ScopeApplicabilityResolver} (model
 *       column metadata).</li>
 *   <li><b>applicable sensitive field sets</b> — {@link SensitiveFieldSetCache}
 *       (boot-loaded SFS table). Reading from cache avoids the per-request
 *       {@code modelService.searchList("SensitiveFieldSet", …)} that would
 *       otherwise be subject to {@code ScopeFilterAspect} — admins without
 *       an explicit scope grant on the {@code SensitiveFieldSet} model would
 *       see an empty SFS list (chicken-and-egg: the wizard is precisely
 *       where they would configure such grants).</li>
 * </ul>
 *
 * <p>Per-request cost is constant — a few HashMap lookups regardless of
 * total SFS / model count.
 *
 * <h3>Grant ceiling</h3>
 * Current policy: only super-admin can hit this endpoint (endpoint-gate
 * check on {@code /admin/*} wizard endpoints). Every caller here therefore
 * sees the full option set for each nav — no per-editor filtering. If
 * non-super-admin role editing is reintroduced, restore
 * {@code GrantCeilingValidator} to trim scope / SFS / nav options per
 * editor's own grants (grant-ceiling gap).
 */
@Slf4j
@Tag(name = "Admin Navigation Config Options")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class NavigationConfigOptionsController {

    private final NavigationModelResolver navResolver;
    private final ScopeApplicabilityResolver scopeApplicability;
    private final SensitiveFieldSetCache sfsCache;

    /**
     * Relation kinds that trigger an INDEPENDENT query of the related model —
     * ManyToOne / ManyToMany are resolved via a dropdown picker / multi-select
     * that hits the related model's own {@code searchName} / {@code getById},
     * so the related model needs its own data scope. OneToOne / OneToMany
     * children are cascade-loaded under the parent row (bounded by the
     * parent's scope) and are intentionally NOT surfaced as separate models.
     */
    private static final Set<FieldType> LOOKUP_RELATION_TYPES =
            EnumSet.of(FieldType.MANY_TO_ONE, FieldType.MANY_TO_MANY);

    @GetMapping("/navigationConfigOptions")
    @Operation(summary = "Wizard stage 3 data — per-navigation scope + sensitive field set + filter field options")
    public ApiResponse<Map<String, NavConfigOptions>> getOptions(
            @RequestParam("navigation_ids") List<String> navigationIds) {
        if (navigationIds == null || navigationIds.isEmpty()) {
            return ApiResponse.success(Map.of());
        }
        // Dedup + preserve caller order — FE sends ids in render order; map
        // iteration mirrors that, which keeps wire-output stable for diffs.
        List<String> ordered = navigationIds.stream().distinct().toList();
        Map<String, NavConfigOptions> out = new LinkedHashMap<>();
        for (String navId : ordered) {
            out.put(navId, buildOne(navId));
        }
        return ApiResponse.success(out);
    }

    /**
     * Resolve a single nav. Returns null for GROUP / pure-container MENU
     * (no primary model) — FE renders the row collapsed in that case.
     */
    private NavConfigOptions buildOne(String navId) {
        String model = navResolver.resolvePrimaryModel(navId);
        if (model == null || !ModelManager.existModel(model)) {
            return null;
        }
        MetaModel meta = ModelManager.getModel(model);
        String label = meta != null && meta.getLabelName() != null ? meta.getLabelName() : model;

        // Applicable scope types = model-column-shape-derived.
        List<String> scopes = scopeApplicability.applicableFor(model).stream()
                .map(ScopeType::name)
                .sorted()
                .toList();

        // SFS visible on this nav row = own (SFS.model == nav.model) ∪
        // attached (SFS.attachedTo contains nav.model). Dedup by setId so a
        // SFS that accidentally points its attachedTo at its own model
        // shows up only once. All data comes from the in-memory cache.
        Set<String> seen = new HashSet<>();
        List<SfsRef> sfs = new ArrayList<>();
        for (String setId : sfsCache.setIdsOwnedBy(model)) {
            if (!seen.add(setId)) continue;
            String name = sfsCache.nameOf(setId);
            sfs.add(new SfsRef(setId, name != null ? name : setId));
        }
        for (String setId : sfsCache.setIdsAttachedTo(model)) {
            if (!seen.add(setId)) continue;
            String name = sfsCache.nameOf(setId);
            sfs.add(new SfsRef(setId, name != null ? name : setId));
        }

        return new NavConfigOptions(model, label, scopes, sfs);
    }

    // ─────────────────── per-model options (role wizard data step) ───────────────────

    @GetMapping("/roleModelConfigOptions")
    @Operation(summary = "Role wizard data step — per-model scope + SFS options for the granted navs' primary models AND their related (lookup) models")
    public ApiResponse<List<RoleModelConfigOption>> getModelOptions(
            @RequestParam("navigation_ids") List<String> navigationIds) {
        if (navigationIds == null || navigationIds.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        // Primary models of the granted navs (dedup, keep caller order).
        Set<String> primary = new LinkedHashSet<>();
        for (String navId : navigationIds.stream().distinct().toList()) {
            String m = navResolver.resolvePrimaryModel(navId);
            if (m != null && ModelManager.existModel(m)) primary.add(m);
        }
        // Related/lookup models reachable via relational fields (L1 + L2),
        // mirroring EndpointIndex's lookup derivation — these get queried at
        // runtime (lookup / cascade), so the role needs a scope rule for them
        // too or they fail-closed to zero rows.
        Set<String> related = deriveRelatedModels(primary);

        List<RoleModelConfigOption> out = new ArrayList<>(primary.size() + related.size());
        for (String m : primary) out.add(buildModelOption(m, false));
        for (String m : related) out.add(buildModelOption(m, true));
        return ApiResponse.success(out);
    }

    /**
     * L1 (direct lookups) + L2 (lookups of those) related models, excluding the
     * primary set. Follows ONLY ManyToOne / ManyToMany relations (see
     * {@link #LOOKUP_RELATION_TYPES}) — those are queried independently via
     * pickers and therefore need their own scope. OneToOne / OneToMany children
     * are cascade-loaded under the parent's scope and are not listed.
     */
    private Set<String> deriveRelatedModels(Set<String> primary) {
        Set<String> visited = new HashSet<>(primary);
        Set<String> related = new LinkedHashSet<>();
        List<String> l1 = new ArrayList<>();
        for (String pm : primary) {
            for (String r : relationTargets(pm)) {
                if (visited.add(r)) {
                    related.add(r);
                    l1.add(r);
                }
            }
        }
        for (String parent : l1) {
            for (String r : relationTargets(parent)) {
                if (visited.add(r)) related.add(r);
            }
        }
        return related;
    }

    /** Distinct related-model names of a model's relational fields. Defensive:
     *  a corrupt / unknown model yields no targets rather than throwing. */
    private List<String> relationTargets(String model) {
        if (model == null || !ModelManager.existModel(model)) return List.of();
        List<MetaField> fields;
        try {
            fields = ModelManager.getModelFields(model);
        } catch (RuntimeException ex) {
            log.warn("roleModelConfigOptions — relation walk skipped for model {}: {}", model, ex.getMessage());
            return List.of();
        }
        if (fields == null) return List.of();
        List<String> out = new ArrayList<>();
        for (MetaField f : fields) {
            // ManyToOne / ManyToMany only — those get queried on their own via
            // pickers. OneToOne / OneToMany use the parent's scope (cascade).
            if (f.getFieldType() != null && LOOKUP_RELATION_TYPES.contains(f.getFieldType())) {
                String r = f.getRelatedModel();
                if (r != null && !r.isEmpty()) out.add(r);
            }
        }
        return out;
    }

    /** Per-model option row: label + applicable scopes + owned/attached SFS. */
    private RoleModelConfigOption buildModelOption(String model, boolean related) {
        MetaModel meta = ModelManager.getModel(model);
        String label = meta != null && meta.getLabelName() != null ? meta.getLabelName() : model;
        List<String> scopes = scopeApplicability.applicableFor(model).stream()
                .map(ScopeType::name)
                .sorted()
                .toList();
        Set<String> seen = new HashSet<>();
        List<SfsRef> sfs = new ArrayList<>();
        for (String setId : sfsCache.setIdsOwnedBy(model)) {
            if (seen.add(setId)) sfs.add(new SfsRef(setId, sfsName(setId)));
        }
        for (String setId : sfsCache.setIdsAttachedTo(model)) {
            if (seen.add(setId)) sfs.add(new SfsRef(setId, sfsName(setId)));
        }
        return new RoleModelConfigOption(model, label, scopes, sfs, related);
    }

    private String sfsName(String setId) {
        String name = sfsCache.nameOf(setId);
        return name != null ? name : setId;
    }
}
