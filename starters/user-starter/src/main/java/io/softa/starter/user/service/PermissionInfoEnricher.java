package io.softa.starter.user.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.filter.SensitiveFieldSetCache;
import io.softa.starter.user.util.JsonArrayUtils;

/**
 * Builds {@link PermissionInfo} for a user at login (or on cache miss).
 *
 * <h3>Two-layer cache in front of the DB load</h3>
 * <ol>
 *   <li><b>Request-scoped</b> — stashes the resolved snapshot on the
 *       current {@link RequestAttributes} so repeated lookups inside one
 *       HTTP request hit only memory. A single list request typically
 *       calls {@link #enrich} 4+ times (Layer A interceptor + Layer B/C/D
 *       AOP invocations); without this layer every one round-trips Redis.</li>
 *   <li><b>Redis-backed</b> — TTL 1 hour. Survives the populating request
 *       so subsequent requests from the same user skip the DB. Targeted
 *       evictions by {@code PermissionCacheInvalidator} keep it tight
 *       when role / grant / employee context changes.</li>
 * </ol>
 * On miss in both, loads from DB via {@link #loadFromDb} and back-fills
 * both caches.
 *
 * <h3>Cache key</h3>
 * {@code perm:{tenantId}:user:{userId}} — exposed via {@link #cacheKey}
 * so {@code PermissionCacheInvalidator} computes the same key for
 * targeted evictions. If the {@code PermissionInfo} schema ever changes
 * incompatibly, bump the key prefix (e.g. {@code perm-v2:}) so the old
 * and new serialized values live in disjoint namespaces.
 *
 * <h3>DB load chain</h3>
 * <ol>
 *   <li>Principal — userId + domain-specific extensions populated by every
 *       registered {@link PrincipalEnrichmentContributor} bean (e.g. the HCM
 *       module's {@code EmployeeContextEnricher} writes {@code extensions["employee"]});
 *       no contributors → Principal carries only generic identity, and any
 *       ScopeContributor that depends on a missing extension degrades
 *       fail-closed at compile time.</li>
 *   <li>Roles — user_role_rel → active role rows (inactive roles
 *       intentionally skipped per design §3.5: flipping active=false is
 *       the supported way to revoke every grant tied to that role
 *       without deleting rows).</li>
 *   <li>SUPER_ADMIN short-circuit — any active role with
 *       {@code code=SUPER_ADMIN} returns the empty-grant snapshot;
 *       PermissionInterceptor / ScopeFilterAspect / FieldFilter detect
 *       the role code downstream and bypass enforcement.</li>
 *   <li>Grants — load all role_navigation rows for the user's roles in
 *       one batch, parse the 3 JSON columns, aggregate:
 *         <ul>
 *           <li>navigations:                union of navigationId per row</li>
 *           <li>permissions:                union of permissionIds across rows</li>
 *           <li>modelScopeMap[model]:       per-row scope rules grouped by
 *               the navigation's primary model; multi-nav rows for the
 *               same model OR-combine at enforcement time.</li>
 *           <li>modelSensitiveFieldSetsMap: union per model (keyed by the
 *               SFS's canonical model, not the granting nav's model — see
 *               inline comment for the {@code attachedTo} reasoning).</li>
 *         </ul>
 *   </li>
 *   <li>Ancestor expansion — every granted nav id implies its parent
 *       (GROUP / container-MENU) is visible in the sidebar.</li>
 * </ol>
 *
 * <h3>{@code @SkipPermissionCheck}</h3>
 * Marked on {@link #enrich} — this is the very service the four
 * permission layers depend on for their bypass decisions, so any
 * {@code ModelService.search*} call inside enrich (roles, user_role_rel,
 * role_navigation, navigation) must NOT be re-routed back into the
 * aspect chain. Without the annotation, enrich → ScopeFilterAspect →
 * enrich recurses unbounded. The annotation is honored by CGLIB-proxied
 * invocations (the project default {@code spring.aop.proxy-target-class=true}).
 */
@Slf4j
@Service
public class PermissionInfoEnricher {

    /** TTL for the Redis layer. Long enough to absorb burst load,
     *  short enough that any missed eviction settles within an hour. */
    private static final int CACHE_TTL_SECONDS = RedisConstant.ONE_HOUR;

    // DB-loader collaborators.
    private final RoleService roleService;
    private final UserRoleRelService userRoleRelService;
    private final RoleNavigationService roleNavigationService;
    private final NavigationModelResolver navigationModelResolver;
    private final SensitiveFieldSetCache sensitiveFieldSetCache;
    /** Pluggable per-domain enricher beans (e.g. HR module's
     *  EmployeeContextEnricher contributes the {@code "employee"} slot).
     *  Each contributor writes its result into
     *  {@code principal.extensions[contributor.key()]}. List is empty
     *  when no domain module is wired — Principal carries only
     *  generic identity. */
    private final List<PrincipalEnrichmentContributor> enrichmentContributors;

    // Cache layer.
    private final CacheService cacheService;

    public PermissionInfoEnricher(
            RoleService roleService,
            UserRoleRelService userRoleRelService,
            RoleNavigationService roleNavigationService,
            NavigationModelResolver navigationModelResolver,
            SensitiveFieldSetCache sensitiveFieldSetCache,
            List<PrincipalEnrichmentContributor> enrichmentContributors,
            CacheService cacheService) {
        this.roleService = roleService;
        this.userRoleRelService = userRoleRelService;
        this.roleNavigationService = roleNavigationService;
        this.navigationModelResolver = navigationModelResolver;
        this.sensitiveFieldSetCache = sensitiveFieldSetCache;
        this.enrichmentContributors = enrichmentContributors;
        this.cacheService = cacheService;
    }

    /** leafNavId → root→leaf ancestor chain (inclusive of the leaf).
     *  Built once in {@link #initAncestorIndex()} from the same Navigation
     *  snapshot {@link NavigationModelResolver} exposes; nav rows are
     *  seed-only so a per-request walk via parentId was pure repeat work. */
    private volatile Map<String, List<String>> ancestorChains = Map.of();

    /**
     * Cached entry point — checks request-scoped attributes, then Redis,
     * then falls through to DB load and back-fills both layers.
     */
    @SkipPermissionCheck
    public PermissionInfo enrich(Long tenantId, Long userId) {
        String key = cacheKey(tenantId, userId);

        // Layer 1: request-scoped fast path. Same request, same user
        // (the common case — Layer A/B/C/D all call enrich for
        // ctx.getUserId()) → return the already-resolved snapshot. The
        // RequestAttributes container is automatically cleared at request
        // end by Spring's RequestContextFilter — no manual cleanup needed.
        // Note: ra == null in non-request contexts (scheduled jobs,
        // bootstrap hooks, async without request copy). Those skip the
        // request-scoped layer and fall through to Redis + DB.
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra != null) {
            Object stashed = ra.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (stashed instanceof PermissionInfo pi) {
                return pi;
            }
        }

        // Layer 2: Redis. Across requests, same user → memory hit.
        try {
            PermissionInfo cached = cacheService.get(key, PermissionInfo.class);
            if (cached != null) {
                log.trace("PermissionInfo cache hit — key={}", key);
                stashInRequest(ra, key, cached);
                return cached;
            }
        } catch (Throwable t) {
            // Cache read failure (deserialization / Redis offline) → fall
            // through to DB load. Don't fail the whole request on a stale /
            // poisoned cache entry.
            log.warn("PermissionInfo cache read failed for key={}; falling through to DB", key, t);
        }

        // Layer 3: DB.
        PermissionInfo fresh = loadFromDb(tenantId, userId);
        if (fresh != null) {
            try {
                cacheService.save(key, fresh, CACHE_TTL_SECONDS);
            } catch (Throwable t) {
                // Cache write failure — log + return the fresh value. Next
                // request will retry the cache write.
                log.warn("PermissionInfo cache write failed for key={}; continuing", key, t);
            }
            stashInRequest(ra, key, fresh);
        }
        return fresh;
    }

    /**
     * Canonical cache-key shape. Exposed (static) so
     * {@code PermissionCacheInvalidator} computes the same key for
     * targeted evictions.
     */
    public static String cacheKey(Long tenantId, Long userId) {
        return "perm:" + tenantId + ":user:" + userId;
    }

    /** Park a resolved snapshot in request-scoped attributes so subsequent
     *  layers (B/C/D) within the same request don't round-trip Redis. */
    private static void stashInRequest(RequestAttributes ra, String key, PermissionInfo pi) {
        if (ra == null) return;
        ra.setAttribute(key, pi, RequestAttributes.SCOPE_REQUEST);
    }

    // ─────────────────────── DB load ───────────────────────

    private PermissionInfo loadFromDb(Long tenantId, Long userId) {
        // 1. Principal — userId + domain extensions (loaded via the
        //    PrincipalEnrichmentContributor SPI). Each contributor stores
        //    its result under its own key in principal.extensions — e.g.
        //    HR module's EmployeeContextEnricher writes "employee" →
        //    EmployeeContext. List may be empty when no domain module is
        //    wired; Principal then carries only generic identity.
        Principal principal = new Principal();
        principal.setUserId(userId);
        for (PrincipalEnrichmentContributor contributor : enrichmentContributors) {
            try {
                Object value = contributor.loadFor(userId, tenantId);
                if (value != null) principal.putExtension(contributor.key(), value);
            } catch (Throwable t) {
                log.warn("PrincipalEnrichmentContributor[{}] failed for user {}; skipping slot",
                        contributor.key(), userId, t);
            }
        }

        // 2. Roles
        List<Role> activeRoles = loadActiveRolesFor(userId);
        Set<String> roleCodes = activeRoles.stream()
                .map(Role::getCode)
                .filter(c -> c != null && !c.isEmpty())
                .collect(Collectors.toSet());

        // 3. SUPER_ADMIN short-circuit — uses the same empty-grants
        //    snapshot as "no active roles" below; SUPER_ADMIN is identified
        //    purely by roleCodes downstream, no other field marks it.
        if (roleCodes.contains(RoleConstant.CODE_SUPER_ADMIN)) {
            log.debug("PermissionInfo bypass — user {} (tenant {}) is SUPER_ADMIN", userId, tenantId);
            return emptyGrantsSnapshot(principal, roleCodes);
        }

        // 4. Grants
        if (activeRoles.isEmpty()) {
            return emptyGrantsSnapshot(principal, roleCodes);
        }
        List<Long> roleIds = activeRoles.stream().map(Role::getId).toList();
        // Only the four columns below are read in the loop that follows.
        // Limiting via setFields cuts the cache-miss path's serialization
        // cost roughly in half — the role_navigation table carries audit
        // fields, tenantId, source, etc. that the enricher never touches.
        List<RoleNavigation> grants = roleNavigationService.searchList(new FlexQuery(
                List.of("navigationId", "permissionIds", "dataScopes", "sensitiveFieldSetIds"),
                new Filters().in(RoleNavigation::getRoleId, roleIds)));

        Set<String> navigations = new HashSet<>();
        Set<String> permissions = new HashSet<>();
        Map<String, List<ScopeRule>> modelScopeMap = new HashMap<>();
        Map<String, Set<String>> modelSensitiveFieldSetsMap = new HashMap<>();

        for (RoleNavigation rn : grants) {
            String navId = rn.getNavigationId();
            if (navId == null) continue;
            navigations.add(navId);

            // permissions — union across all rows. Backend doesn't sub-bucket
            // permissions per nav for enforcement; the interceptor just needs
            // "does the user have permission X".
            // permissionIds JSON can hold either strings or numeric ids in
            // historical seeds — coerceNumeric=true preserves the
            // backward-compatible behaviour the old in-class parser had.
            permissions.addAll(JsonArrayUtils.toStringList(rn.getPermissionIds(), true));

            // Model-keyed aggregates. GROUP / container MENU rows have no
            // primary model — Validator ⑩ rejects them but be defensive.
            String model = navigationModelResolver.resolvePrimaryModel(navId);
            if (model == null) continue;

            List<ScopeRule> scopes = parseScopeRules(rn.getDataScopes());
            if (!scopes.isEmpty()) {
                modelScopeMap.computeIfAbsent(model, k -> new ArrayList<>()).addAll(scopes);
            }
            // Route each granted setId into modelSensitiveFieldSetsMap
            // keyed by the SFS's CANONICAL model — not by the granting
            // nav's primary model. Two scenarios this gets right:
            //
            //   1. SFS bound to a model X granted on a nav whose primary
            //      model is X → conceptually identical to the old
            //      `model` keying, but goes through the cache lookup
            //      uniformly.
            //   2. SFS bound to model A but declared `attachedTo: [B]`
            //      (UI hint) — admin checks it on a B-rooted nav row.
            //      The grant must land in map[A] so the FieldFilter
            //      recursion (which switches to A's mask context when
            //      it hits an A-typed sub-object under B's response)
            //      finds the grant. Old code keyed it under B → invisible
            //      at A's mask context → unintended masking.
            //
            // Unknown setIds (cache miss = SFS deleted but grant lingers
            // → dangling ref) skip silently. PermissionRegistryValidator
            // surfaces those as warnings on startup.
            for (String sid : JsonArrayUtils.toStringList(rn.getSensitiveFieldSetIds())) {
                String sfsModel = sensitiveFieldSetCache.modelOf(sid);
                if (sfsModel == null) continue;
                modelSensitiveFieldSetsMap
                        .computeIfAbsent(sfsModel, k -> new HashSet<>())
                        .add(sid);
            }
        }

        // 5. Ancestor expansion — a granted child implies its container is
        //    visible in the sidebar.
        Set<String> expandedNavigations = expandAncestors(navigations);

        PermissionInfo info = new PermissionInfo();
        info.setPrincipal(principal);
        info.setRoleCodes(roleCodes);
        info.setNavigations(expandedNavigations);
        info.setPermissions(permissions);
        info.setModelScopeMap(modelScopeMap);
        info.setModelSensitiveFieldSetsMap(modelSensitiveFieldSetsMap);
        return info;
    }

    // ─────────────────────── loaders ───────────────────────

    /** Roles for a user, filtered to active=true. Inactive roles are skipped
     *  per §3.5 — the admin can flip active=false to temporarily revoke
     *  every grant tied to that role without deleting rows. */
    private List<Role> loadActiveRolesFor(Long userId) {
        // Only roleId is used downstream — drop the rest of UserRoleRel.
        List<UserRoleRel> rels = userRoleRelService.searchList(new FlexQuery(
                List.of("roleId"),
                new Filters().eq(UserRoleRel::getUserId, userId)));
        if (rels.isEmpty()) return List.of();
        Set<Long> roleIds = rels.stream()
                .map(UserRoleRel::getRoleId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (roleIds.isEmpty()) return List.of();
        // Only id + code + active are read (active is the filter, code is
        // used for SUPER_ADMIN detection, id is what we collect).
        return roleService.searchList(new FlexQuery(
                List.of("id", "code", "active"),
                new Filters().in(Role::getId, roleIds).eq(Role::getActive, true)));
    }

    private Set<String> expandAncestors(Set<String> leafNavIds) {
        if (leafNavIds.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>(leafNavIds);
        Map<String, List<String>> chains = ancestorChains;
        for (String leaf : leafNavIds) {
            List<String> chain = chains.get(leaf);
            if (chain == null || chain.isEmpty()) continue;
            // chain is stored root → leaf (inclusive); skip the leaf itself
            // (already in `out`) and union every ancestor.
            for (String id : chain) {
                if (!id.equals(leaf)) out.add(id);
            }
        }
        return out;
    }

    /**
     * Build the leaf → ancestor-chain index once. The Navigation tree is
     * immutable at runtime (seed-only data, redeploy to refresh) so the
     * cost is paid once and the per-request enrich loop becomes a hashmap
     * lookup instead of N parent traversals.
     *
     * <p>Chain ordering is root → leaf inclusive; cycle defence and depth
     * cap (32) mirror the old per-request walk. Built from the same
     * Navigation set {@code NavigationModelResolver} exposes.
     */
    @PostConstruct
    void initAncestorIndex() {
        Collection<Navigation> all = navigationModelResolver.allNavigations();
        if (all == null || all.isEmpty()) {
            ancestorChains = Map.of();
            return;
        }
        Map<String, Navigation> byId = new HashMap<>(all.size());
        for (Navigation n : all) {
            if (n != null && n.getId() != null) byId.put(n.getId(), n);
        }
        Map<String, List<String>> built = new HashMap<>(byId.size());
        for (Navigation n : byId.values()) {
            String leafId = n.getId();
            List<String> chain = new ArrayList<>();
            chain.add(leafId);
            String cursor = n.getParentId();
            int guard = 0;
            Set<String> visited = new HashSet<>();
            visited.add(leafId);
            while (cursor != null && guard++ < 32 && visited.add(cursor)) {
                chain.add(cursor);
                Navigation parent = byId.get(cursor);
                cursor = parent == null ? null : parent.getParentId();
            }
            // Reverse to root → leaf order for natural iteration.
            Collections.reverse(chain);
            built.put(leafId, List.copyOf(chain));
        }
        ancestorChains = Map.copyOf(built);
        log.debug("PermissionInfoEnricher — ancestor chain index built for {} navigation(s)", built.size());
    }

    // ─────────────────────── parsers ───────────────────────

    /** Parse dataScopes JSON → ScopeRule list. Shape: [{scopeType, scopeExpr?}].
     *  Tolerant: skip rows missing scopeType or with unknown enum value. */
    private static List<ScopeRule> parseScopeRules(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return List.of();
        List<ScopeRule> out = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            if (!el.isObject()) continue;
            JsonNode typeNode = el.get("scopeType");
            if (typeNode == null || !typeNode.isString()) continue;
            ScopeType type;
            try {
                type = ScopeType.valueOf(typeNode.asString());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            JsonNode exprNode = el.get("scopeExpr");
            ScopeRule rule = new ScopeRule();
            rule.setScopeType(type);
            rule.setScopeExpr(exprNode);
            out.add(rule);
        }
        return out;
    }

    // ─────────────────────── snapshots ───────────────────────

    /**
     * Empty-grant snapshot — used for both SUPER_ADMIN (bypass: layers
     * short-circuit on {@code pi.isSuperAdmin()} before reading the empty
     * grant sets) and for users with no active roles (literally no
     * grants). Whether the user is super-admin is encoded entirely in
     * {@code roleCodes}; downstream layers do not infer it from any
     * other field.
     */
    private static PermissionInfo emptyGrantsSnapshot(Principal principal, Set<String> roleCodes) {
        PermissionInfo info = new PermissionInfo();
        info.setPrincipal(principal);
        info.setRoleCodes(roleCodes);
        info.setNavigations(Collections.emptySet());
        info.setPermissions(Collections.emptySet());
        info.setModelScopeMap(Collections.emptyMap());
        info.setModelSensitiveFieldSetsMap(Collections.emptyMap());
        return info;
    }

}
