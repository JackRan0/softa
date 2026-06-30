package io.softa.starter.user.service;

import io.softa.starter.user.dto.Principal;

/**
 * SPI — domain modules use this to attach their own context to a
 * {@link Principal} during permission-info enrichment. Each contributor
 * supplies a {@link #key()} and a {@link #loadFor(Long, Long)} that
 * returns the domain-specific context object; {@code PermissionInfoEnricher}
 * stores the result under {@code principal.extensions[key]}.
 *
 * <h3>Example — HR module</h3>
 * <pre>
 * &#64;Component
 * public class EmployeeContextEnricher implements PrincipalEnrichmentContributor {
 *     &#64;Override public String key() { return "employee"; }
 *     &#64;Override public Object loadFor(Long userId, Long tenantId) {
 *         return employeeRelationsService.loadFor(userId);
 *     }
 * }
 *
 * // Scope contributor consumes it:
 * EmployeeContext ec = (EmployeeContext) principal.getExtensions().get("employee");
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * {@link #loadFor} runs once per cache miss (PermissionInfo cache is keyed
 * per user + tenant, default 1h TTL). The returned value MUST be
 * serializable — PermissionInfo travels through Redis and the
 * {@code /me/uiContext} HTTP response.
 *
 * <h3>Failure semantics</h3>
 * Return {@code null} when no context applies to this user — the
 * extension slot stays empty and scope contributors that depend on it
 * degrade fail-closed at compile time. Throwing aborts the entire
 * enrichment; only do so for genuinely fatal conditions.
 */
public interface PrincipalEnrichmentContributor {

    /**
     * Stable identifier for this contributor's extension slot. Used as
     * the key in {@link Principal#getExtensions()}. Should be a short,
     * URL-safe string — examples: {@code "employee"}, {@code "salesTerritory"},
     * {@code "tenantProfile"}.
     */
    String key();

    /**
     * Load the domain context for this user, or {@code null} if none
     * applies. Called once per cache miss; the result is memoized in
     * PermissionInfo for the cache TTL.
     */
    Object loadFor(Long userId, Long tenantId);
}
