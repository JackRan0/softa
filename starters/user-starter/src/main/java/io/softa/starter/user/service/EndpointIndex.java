package io.softa.starter.user.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.entity.Permission;

/**
 * System-wide reverse index of HTTP endpoint → set of permission ids.
 *
 * <p>Built once at startup by scanning the entire permission table; both
 * {@code exactIndex} and {@code patternEntries} are effectively immutable
 * after {@link #init()} and freely shared between request threads.
 *
 * <h3>Per-row endpoint resolution</h3>
 * For each permission row:
 * <ul>
 *   <li>{@code permission.endpoints != null} → use values verbatim</li>
 *   <li>{@code permission.endpoints == null} → derive {@code POST /<Model>/<suffix>}
 *       via {@link #STANDARD_ACTION_MAP} plus L1/L2 lookup propagation
 *       for relational fields (see {@link #deriveLookupEndpoints}).</li>
 * </ul>
 *
 * <p>All tenants share a single index in memory; the URI → permissionId
 * mapping is a system fact (tenant-independent), only "does this user
 * have any of the matched permissions" varies per user. Permission rows
 * are seed-only — redeploy to refresh; no runtime reload listener.
 *
 * <h3>Request hot path</h3>
 * {@code PermissionInterceptor} calls {@link #lookup(String, String)} →
 * gets a {@code Set} of candidate permission ids → checks if
 * {@code user.permissions} intersects → allow / 403.
 *
 * <h3>Why Set, not single permission id</h3>
 * The same endpoint can be reachable from multiple business contexts.
 * Example: {@code /Department/searchList} is needed by:
 * <ul>
 *   <li>the dedicated Department admin page → granted via
 *       {@code department.view}</li>
 *   <li>the Employee list page's department-tree side panel → should also
 *       be allowed via {@code employee.view} (so HR-only users don't need
 *       a separate department permission)</li>
 * </ul>
 * Operators model this in {@code permission.endpoints} JSON column —
 * {@code employee.view}'s endpoints array can list both {@code /Employee/search*}
 * AND {@code /Department/searchList}. The interceptor allows the call if
 * the caller holds <em>any</em> of the permissions tied to the URL.
 *
 * <p>URL convention: in-app path (context-path stripped) →
 * {@code POST /<Model>/<actionSuffix>}. Pattern entries (URIs containing
 * {@code {}}, e.g. {@code onChange/{fieldName}}) fall through after
 * exact-index misses; multiple patterns matching the same URI all
 * contribute to the result set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EndpointIndex {

    /** Standard CRUD action → framework endpoint(s). Each entry is "VERB suffix":
     *  the verb is required so detail/create/copy can register their GET-only
     *  helpers ({@code getDefaultValues}, {@code getCopyableFields}, unmask)
     *  alongside POST endpoints. One permission can map to multiple URLs when
     *  the semantic operation has variants. Custom (non-standard) actions fall
     *  through to {@code "POST <action>"} as the URL. */

    /** `view` covers the full read endpoint set — list-style + single-row
     *  + unmask helpers, all under one permission action. */
    private static final List<String> VIEW_ENDPOINTS = List.of(
            "POST searchPage", "POST searchList", "POST searchName",
            "POST searchSimpleAgg", "POST searchPivot", "POST count",
            "POST getOne", "POST getById", "POST getByIds", "POST searchOne",
            "GET getUnmaskedField", "GET getUnmaskedFields");

    /** Minimum endpoint set a Picker widget needs to render:
     *  {@code searchName} lists the candidates for the dropdown,
     *  {@code getById} / {@code getByIds} resolve the persisted id(s)
     *  back to displayName for the rendered chip(s) on form load /
     *  selection. Used for L2 lookup derivation — expanding to the full
     *  view set would bloat the reverse index without unlocking any
     *  picker UI capability. */
    private static final List<String> PICKER_ENDPOINTS = List.of(
            "POST searchName", "POST getById", "POST getByIds");

    private static final Map<String, List<String>> STANDARD_ACTION_MAP = Map.ofEntries(
            Map.entry("view",   VIEW_ENDPOINTS),
            // getDefaultValues backs the new-record form, so it rides on the create perm.
            Map.entry("create", List.of("POST createOne", "POST createOneAndFetch",
                                        "POST createList", "POST createListAndFetch",
                                        "GET getDefaultValues")),
            // onChange/{fieldName} is framework-generated per model; field-level change
            // handlers run while editing a record, so they fall under the update perm.
            Map.entry("update", List.of("POST updateOne", "POST updateOneAndFetch",
                                        "POST updateList", "POST updateListAndFetch",
                                        "POST updateByFilter",
                                        "POST updateByIdAndFetch", "POST updateByIdsAndFetch",
                                        "POST onChange/{fieldName}")),
            Map.entry("delete", List.of("POST deleteOne", "POST deleteList",
                                        "POST deleteById", "POST deleteByIds", "POST deleteBySliceId")),
            Map.entry("export", List.of("POST exportList")),
            Map.entry("import", List.of("POST importList")),
            // getCopyableFields backs the copy dialog, so it rides on the copy perm.
            Map.entry("copy",   List.of("POST copy",
                                        "POST copyById", "POST copyByIdAndFetch",
                                        "POST copyByIds", "POST copyByIdsAndFetch",
                                        "GET getCopyableFields")));

    private final ModelService<?> modelService;
    private final NavigationModelResolver navResolver;

    /** Endpoint → set of permission ids that grant this endpoint. Multiple
     *  permissions can list the same endpoint in {@code permission.endpoints}
     *  (e.g. both {@code employee.view} and {@code department.view} include
     *  {@code /Department/searchList} so an Employee-only role still loads
     *  the department-tree side panel). The interceptor allows the call if
     *  the caller's permission set intersects this set.
     *
     *  <p>Built once at startup in {@link #init()}; both fields are
     *  effectively immutable after that and used freely from concurrent
     *  request threads. No reload — seed-only data; redeploy to update. */
    private Map<String, Set<String>> exactIndex = Map.of();
    private List<PatternEntry> patternEntries = List.of();

    @PostConstruct
    void init() {
        Map<String, Set<String>> exact = new HashMap<>();
        List<PatternEntry> patterns = new ArrayList<>();

        for (Permission p : modelService.searchList("Permission", new FlexQuery(), Permission.class)) {
            List<String> endpoints = explicitOrDerive(p);
            for (String ep : endpoints) {
                if (ep.contains("{")) {
                    patterns.add(PatternEntry.compile(ep, p.getId()));
                } else {
                    // Accumulate, not overwrite — same endpoint can be
                    // listed by multiple permissions.
                    exact.computeIfAbsent(ep, k -> new HashSet<>()).add(p.getId());
                }
            }
        }
        // Freeze: wrap each inner Set as unmodifiable + the outer Map too.
        Map<String, Set<String>> frozenExact = new HashMap<>(exact.size());
        for (var entry : exact.entrySet()) {
            frozenExact.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        this.exactIndex = Collections.unmodifiableMap(frozenExact);
        this.patternEntries = List.copyOf(patterns);
        log.info("EndpointIndex built: {} exact + {} pattern entries", exact.size(), patterns.size());
    }

    /**
     * @param uri    in-app request path (servletPath, e.g. {@code "/Employee/searchPage"})
     * @param method HTTP method (e.g. "POST")
     * @return all permission ids tied to this endpoint, empty when the
     *         endpoint isn't registered. Caller checks {@code
     *         user.permissions.intersect(result).isNotEmpty()} to allow.
     */
    public Set<String> lookup(String uri, String method) {
        String key = method + " " + uri;
        Set<String> hit = exactIndex.get(key);
        if (hit != null) return hit;
        // Patterns are checked after exact misses. Multiple patterns can
        // match the same URI (e.g. /Foo/{id}/preview registered under two
        // permissions) — collect every match so the interceptor can grant
        // access via any one of them.
        Set<String> matched = null;
        for (PatternEntry p : patternEntries) {
            if (p.matches(key)) {
                if (matched == null) matched = new HashSet<>();
                matched.add(p.permissionId);
            }
        }
        return matched == null ? Set.of() : matched;
    }

    /**
     * Returns explicit endpoints from permission.endpoints when present;
     * otherwise derives POST /&lt;Model&gt;/&lt;suffix&gt; via nav.model + action.
     *
     * <p>NOTE: keys do NOT include the {@code /api} prefix. The interceptor
     * looks up using {@code HttpServletRequest.getServletPath()} which
     * returns the path INSIDE the app context — Spring strips
     * {@code server.servlet.context-path} (e.g. {@code /api/hcm}) before
     * we see the request. This makes the convention app-context-agnostic:
     * the same {@code permission.json} seed works whether the host app
     * runs at {@code /} or {@code /api/hcm} or any other context root.
     */
    private List<String> explicitOrDerive(Permission p) {
        JsonNode explicit = p.getEndpoints();
        if (explicit != null && explicit.isArray() && !explicit.isEmpty()) {
            List<String> out = new ArrayList<>(explicit.size());
            for (JsonNode node : explicit) out.add(node.asString());
            return out;
        }
        return deriveStandardEndpoints(p);
    }

    private List<String> deriveStandardEndpoints(Permission p) {
        Navigation nav = navResolver.findNavigation(p.getNavigationId());
        if (nav == null || nav.getModel() == null) return List.of();

        String action = lastSegment(p.getId());
        // Standard action → list of "VERB suffix" entries; unknown (custom)
        // action → POST with the action segment as the URL suffix. One
        // permission row can drive multiple endpoint entries in the lookup map.
        List<String> entries = STANDARD_ACTION_MAP.getOrDefault(action, List.of("POST " + action));
        List<String> out = new ArrayList<>(entries.size());
        for (String entry : entries) {
            int spaceIdx = entry.indexOf(' ');
            String verb = entry.substring(0, spaceIdx);
            String suffix = entry.substring(spaceIdx + 1);
            out.add(verb + " /" + nav.getModel() + "/" + suffix);
        }

        // Lookup auto-derivation — a business permission like Employee.create
        // implicitly grants the READ endpoints of related models referenced
        // through relational fields, so the admin doesn't have to grant
        // Department.view / LegalEntity.view separately just to populate
        // pickers in the form. Scope (Layer B) + FieldFilter (Layer C) still
        // protect the underlying data.
        //
        // Lookup propagation per triggering action:
        //   delete / export / copy                   → no derivation
        //   view / create / update / import         → see deriveLookupEndpoints:
        //     L1 (direct relations)        : full view endpoint sets
        //     L2 (nested-through relations): picker subset (searchName +
        //                                    getById + getByIds)
        //
        // Why no related-model WRITE derivation: writes against the primary
        // model only carry FK ids in their payload (e.g. Employee.create with
        // departmentId="dept-123"); the related row already exists. Inline-
        // create dialogs ("+ new department" inside the Employee form) are a
        // per-page UX choice and should gate that specific button with
        // useActionPermission("Department","create") rather than fold the
        // write endpoint into Employee.create's permission.
        //
        // Why L2 is the picker subset (not full view): nested sub-form
        // pickers (e.g. LeavePolicy.leavePolicyDetailIds →
        // LeavePolicyDetail.leaveTypeId → /LeaveType/{searchName,getById})
        // need searchName for the dropdown options + getById/getByIds to
        // render the selected chip's displayName. searchPage / count /
        // unmask / etc. are unreachable from a picker, so leaving them out
        // keeps the reverse-index lean.
        //
        // Option / MultiOption fields are NOT derived here —
        // /SysOptionSet/getOptionItems/* is yml-whitelisted as tenant-public
        // dictionary metadata.
        out.addAll(deriveLookupEndpoints(nav.getModel(), action));
        return out;
    }

    private static final Set<String> LOOKUP_TRIGGERS =
            Set.of("view", "create", "update", "import");

    /**
     * Derive lookup endpoints for relational fields of {@code model}.
     * Returns an empty list when {@code action} isn't a lookup trigger
     * (delete / export / copy / unknown) so callers can append unconditionally.
     *
     * <p>Two-layer derivation:
     * <ul>
     *   <li><b>L1 (direct relations):</b> full {@code view} endpoint
     *       sets — needed when the parent form / list view displays the
     *       related row (searchPage / searchList / getById / etc.).</li>
     *   <li><b>L2 (relations of relations):</b> picker subset —
     *       {@code searchName} (dropdown options) +
     *       {@code getById} / {@code getByIds} (render selected chip).
     *       Covers nested sub-form pickers (e.g. LeavePolicy creates carry
     *       LeavePolicyDetail children whose own LeaveType picker hits
     *       /LeaveType/searchName then /LeaveType/getById when a value is
     *       chosen). Expanding L2 to full view would bloat the
     *       reverse-index without changing what the page can actually
     *       reach.</li>
     * </ul>
     *
     * <p>Cycles guarded by a visited set (LeavePolicy → LeavePolicyDetail →
     * LeavePolicy would otherwise loop). Models reachable in BOTH layers
     * (e.g. parent has both a direct LeaveType FK and a nested one through
     * LeavePolicyDetail) get the L1 treatment — visited tracks the first
     * appearance.
     */
    private List<String> deriveLookupEndpoints(String model, String action) {
        if (!LOOKUP_TRIGGERS.contains(action)) return List.of();
        if (!ModelManager.existModel(model)) return List.of();

        List<MetaField> rootFields = readFieldsSafely(model);
        if (rootFields.isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(model);  // never re-derive self

        // L1: direct relations → full view
        List<String> l1Related = new ArrayList<>();
        for (MetaField field : rootFields) {
            if (!FieldType.RELATED_TYPES.contains(field.getFieldType())) continue;
            String related = field.getRelatedModel();
            if (related == null || related.isEmpty()) continue;
            if (!visited.add(related)) continue;
            appendDerivedAction(out, related, "view");
            l1Related.add(related);
        }

        // L2: relations of L1 relations → picker endpoint subset (searchName
        // for the dropdown + getById / getByIds to render the selected chip).
        for (String parent : l1Related) {
            List<MetaField> nestedFields = readFieldsSafely(parent);
            for (MetaField field : nestedFields) {
                if (!FieldType.RELATED_TYPES.contains(field.getFieldType())) continue;
                String nestedRelated = field.getRelatedModel();
                if (nestedRelated == null || nestedRelated.isEmpty()) continue;
                if (!visited.add(nestedRelated)) continue;
                for (String entry : PICKER_ENDPOINTS) {
                    int spaceIdx = entry.indexOf(' ');
                    String verb = entry.substring(0, spaceIdx);
                    String suffix = entry.substring(spaceIdx + 1);
                    out.add(verb + " /" + nestedRelated + "/" + suffix);
                }
            }
        }

        return out;
    }

    /** Defensive {@link ModelManager#getModelFields} wrapper — swallows
     *  metadata exceptions so a single corrupt model doesn't fail the
     *  whole index build, returns empty list on miss. */
    private List<MetaField> readFieldsSafely(String model) {
        try {
            List<MetaField> fields = ModelManager.getModelFields(model);
            return fields == null ? List.of() : fields;
        } catch (RuntimeException ex) {
            log.warn("EndpointIndex — lookup derivation skipped for model {}: {}", model, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Expand a (model, action) pair to its concrete endpoint strings using
     * the shared {@link #STANDARD_ACTION_MAP}. Mirrors the suffix loop in
     * {@link #deriveStandardEndpoints} so lookup propagation and primary
     * derivation produce identical URL formats.
     */
    private void appendDerivedAction(List<String> out, String model, String action) {
        List<String> entries = STANDARD_ACTION_MAP.get(action);
        if (entries == null) return;
        for (String entry : entries) {
            int spaceIdx = entry.indexOf(' ');
            String verb = entry.substring(0, spaceIdx);
            String suffix = entry.substring(spaceIdx + 1);
            out.add(verb + " /" + model + "/" + suffix);
        }
    }

    private static String lastSegment(String permissionId) {
        int idx = permissionId.lastIndexOf('.');
        return idx < 0 ? permissionId : permissionId.substring(idx + 1);
    }

    private record PatternEntry(Pattern compiled, String permissionId) {

        static PatternEntry compile(String template, String permissionId) {
            // Convert e.g. "GET /api/Foo/{id}/preview" → regex "GET /api/Foo/[^/]+/preview"
            String regex = "^" + template.replaceAll("\\{[^/]+}", "[^/]+") + "$";
            return new PatternEntry(Pattern.compile(regex), permissionId);
        }

        boolean matches(String key) {
            return compiled.matcher(key).matches();
        }
    }
}
