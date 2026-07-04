package io.softa.starter.user.dto;

import java.util.HashMap;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.utils.JsonUtils;

/**
 * Caller principal — built at login by {@code PermissionInfoEnricher}.
 *
 * <h3>Generic identity (always present)</h3>
 * {@code userId} + {@code displayName} — pure identity facts that every
 * caller has.
 *
 * <h3>Domain extensions (opt-in per module)</h3>
 * The {@link #extensions} map is the framework's hook for domain modules
 * to attach their own context. Each
 * {@code PrincipalEnrichmentContributor} bean writes its own slot; the
 * slot's value is a domain-defined DTO keyed by the contributor's
 * {@code key()}. Consumers (typically
 * {@link io.softa.starter.user.scope.ScopeContributor} implementations)
 * read their slot by key and degrade fail-closed when the slot is
 * missing.
 *
 * <p>The framework itself never reads any specific extension — only
 * domain code that knows the key + type does. Keep this map a
 * {@link HashMap} (mutable) so contributors can add slots; the
 * serialization path treats it as a normal JSON object.
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Caller principal")
public class Principal {

    @Schema(description = "User account ID (user_account.id)")
    private Long userId;

    @Schema(description = "Display name shown in UI / audit log")
    private String displayName;

    /**
     * Domain-specific context slots, keyed by {@code PrincipalEnrichmentContributor.key()}.
     * Values are domain-defined DTOs supplied by the contributor bean.
     * Empty map when
     * no contributor matched the user.
     */
    @Schema(description = "Domain-specific context slots (e.g. employee, salesTerritory) keyed by contributor name")
    @Builder.Default
    private Map<String, Object> extensions = new HashMap<>();

    /**
     * Typed accessor for an extension slot. Returns {@code null} when the
     * slot is absent — callers should treat null as fail-closed (rule
     * degrades to empty filter).
     *
     * <h3>Why this isn't a plain cast</h3>
     * {@code extensions} is declared {@code Map<String, Object>} so Jackson
     * doesn't statically know what concrete type each slot carries. When
     * {@code PermissionInfo} is deserialized from Redis (cache hit), Jackson
     * defaults nested objects to {@link java.util.LinkedHashMap} — so the
     * slot value <b>loses its original type</b>. A naive
     * {@code instanceof} / {@code cast} would return null for every cache
     * hit, silently degrading downstream HR scope contributors to fail-
     * closed.
     *
     * <p>Defense in two layers:
     * <ol>
     *   <li>If the value is already an instance of {@code type} (in-process
     *       hot path right after enrich) — return it directly.</li>
     *   <li>Otherwise, convert via the framework {@link JsonUtils} mapper —
     *       a no-op for already-typed objects, but rehydrates a
     *       {@code LinkedHashMap} into the target class via Jackson's
     *       {@code convertValue} (uses the same reflection / Lombok-aware
     *       setters that would have run on direct deserialization).</li>
     * </ol>
     *
     * <p>Conversion failures (e.g. malformed cached entry) log and return
     * null — same fail-closed semantics as a missing slot.
     */
    public <T> T getExtension(String key, Class<T> type) {
        if (extensions == null || type == null) return null;
        Object value = extensions.get(key);
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        try {
            T typed = JsonUtils.getMapper().convertValue(value, type);
            // Jackson convertValue is reflection-heavy —
            // several contributors read the same slot on each request (e.g.
            // SelfScopeContributor, DirectReportsScopeContributor,
            // LegalEntityScopeContributor all read extensions["employee"]).
            // Cache the typed instance back into the slot so subsequent reads
            // hit the isInstance short-circuit above. Safe because
            // extensions is a per-Principal instance owned by the request /
            // enricher — no cross-request or cross-user sharing.
            if (typed != null) extensions.put(key, typed);
            return typed;
        } catch (RuntimeException ex) {
            log.warn("Principal.getExtension('{}') — cached value cannot be converted to {}: {}",
                    key, type.getName(), ex.toString());
            return null;
        }
    }

    /** Set an extension slot; lazily allocates the map if needed. */
    public void putExtension(String key, Object value) {
        if (extensions == null) extensions = new HashMap<>();
        extensions.put(key, value);
    }
}
