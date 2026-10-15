package io.softa.starter.user.dto;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Typed {@code GET /me/uiContext} response — exactly the permission-snapshot fields the frontend
 * reads, plus {@code entitledModules} appended by {@code MeController}. Normalizes the TWO sources
 * to one shape: a cache hit (the engine's serialized {@code PermissionInfo} JSON, read via
 * {@code CacheService}) and the cold-cache fallback ({@code UiContextBuilder}).
 *
 * <p>Deliberately a SUBSET of the engine's {@code PermissionInfo}:
 * <ul>
 *   <li>no {@code superAdmin} — the FE derives it from {@code roleCodes.contains("SUPER_ADMIN")};</li>
 *   <li>no {@code permissionCodes} — server-side only, the FE never reads it;</li>
 *   <li>no {@code modelScopeMap} — server-side row-filtering only, and its {@code ScopeRule} type
 *       lives in permission-starter, which user-starter must not depend on (⊥).</li>
 * </ul>
 * {@link JsonIgnoreProperties}{@code (ignoreUnknown = true)} lets a cache hit deserialize the richer
 * {@code PermissionInfo} JSON into this subset without failing on those extra fields.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UiContext {

    /** Role codes the user holds — the FE derives super-admin from this (roleCodes.has("SUPER_ADMIN")). */
    private Set<String> roleCodes;

    /** Navigation ids the user can see (ancestors expanded server-side). */
    private Set<String> navigations;

    /** Permission ids the user has been granted. */
    private Set<String> permissions;

    /** Model name → granted sensitive-field-set ids. */
    private Map<String, Set<String>> modelSensitiveFieldSetsMap;

    /**
     * Model name → the sensitive fields on it that none of the caller's roles grants, so a screen can
     * leave them out. Appended per request; empty for a caller with full data access, and models with
     * nothing hidden are omitted. Fields hidden only on some records are not listed here — a record's
     * own access says which.
     */
    private Map<String, Set<String>> modelHiddenFieldsMap;

    /**
     * The caller's own employee id, or null when the account is not linked to an employee record.
     *
     * <p>Appended per request rather than baked into the cached snapshot: the snapshot holds for an
     * hour, and whether an account is an employee changes the moment an administrator links it.
     *
     * <p>The frontend gates flows that require the caller to act <i>as</i> an employee with it — a
     * document template signed by whoever initiates it, say — at the point the flow is chosen rather
     * than at the step that submits it.
     *
     * <p>Always serialized: a literal {@code null} is the signal ("no employee record"). An absent key
     * reads as "backend predates the field", which the frontend deliberately does not gate on.
     */
    private Long empId;

    /** Module ids the tenant's plan entitles it to; OMITTED (null) = entitlement not installed, so the
     *  FE applies no version gating (treats every module as entitled). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Set<String> entitledModules;
}
