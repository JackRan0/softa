package io.softa.starter.user.scope.contributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.scope.PrincipalRefResolver;
import io.softa.starter.user.scope.ScopeContributor;

/**
 * {@link ScopeType#CUSTOM} — admin-authored {@link Filters} JSON. Same
 * tuple-array shape as a {@code FlexQuery.filters}, produced by the FE
 * wizard's {@code FilterDialog}. Letting the framework's
 * {@link Filters#of(String)} deserialize it guarantees operator semantics
 * are identical to runtime user-typed filters.
 *
 * <h3>Dynamic refs ({@code $principal.xxx})</h3>
 * String leaves that match {@code "$principal.<field>"} get substituted
 * with the current principal's value before deserialization.
 *
 * <p>The set of supported {@code <field>} names is the union of:
 * <ul>
 *   <li>{@code userId} — always (read directly from {@link Principal})</li>
 *   <li>Whatever {@link PrincipalRefResolver} beans contribute via
 *       {@link PrincipalRefResolver#refKeys()} — e.g. the HR module
 *       contributes {@code employeeId}, {@code departmentId},
 *       {@code legalEntityId}</li>
 * </ul>
 *
 * <h3>Failure semantics</h3>
 * Any unresolved ref (unknown key, or known key but value missing) →
 * whole rule degrades to empty filter. Partial substitution is unsafe:
 * a leftover {@code "$principal.xxx"} literal would silently match
 * arbitrary string rows.
 */
@Slf4j
@Component
public class CustomScopeContributor implements ScopeContributor {

    private static final String PRINCIPAL_REF_PREFIX = "$principal.";
    private static final String USER_ID_REF = "userId";

    /** Pre-built ref → resolver index. Each ref key MUST map to exactly
     *  one resolver — duplicates throw at construction. */
    private final Map<String, PrincipalRefResolver> resolversByKey;

    public CustomScopeContributor(List<PrincipalRefResolver> resolvers) {
        Map<String, PrincipalRefResolver> index = new HashMap<>();
        for (PrincipalRefResolver r : resolvers) {
            Set<String> keys = r.refKeys();
            if (keys == null) continue;
            for (String key : keys) {
                PrincipalRefResolver prior = index.putIfAbsent(key, r);
                if (prior != null && prior != r) {
                    throw new IllegalStateException(
                            "Multiple PrincipalRefResolver beans claim the same ref key '" + key
                                    + "': " + prior.getClass().getName() + " and "
                                    + r.getClass().getName());
                }
            }
        }
        this.resolversByKey = Map.copyOf(index);
    }

    @Override
    public ScopeType scopeType() {
        return ScopeType.CUSTOM;
    }

    @Override
    public List<String> applicableFields() {
        // CUSTOM is universally applicable — admin can author any filter.
        // ApplicabilityResolver special-cases CUSTOM (always enabled).
        return List.of();
    }

    @Override
    public Filters compile(ScopeRule rule, Principal principal, String modelName) {
        JsonNode expr = rule.getScopeExpr();
        if (expr == null || !expr.isArray() || expr.isEmpty()) return new Filters();
        JsonNode substituted = substitutePrincipalRefs(expr, principal);
        if (substituted == null) return new Filters();
        try {
            Filters parsed = Filters.of(substituted.toString());
            return parsed == null ? new Filters() : parsed;
        } catch (Throwable t) {
            log.warn("CustomScope — failed to parse substituted scopeExpr; degrading to empty", t);
            return new Filters();
        }
    }

    /** Recursively walk the scopeExpr JSON tree and replace every
     *  {@code "$principal.<field>"} string leaf with the resolved value.
     *  Returns the substituted tree, or {@code null} if any ref can't be
     *  resolved (unknown ref / required context missing). */
    private JsonNode substitutePrincipalRefs(JsonNode node, Principal principal) {
        if (node == null) return null;
        if (node.isString()) {
            String s = node.asString();
            if (!s.startsWith(PRINCIPAL_REF_PREFIX)) return node;
            String field = s.substring(PRINCIPAL_REF_PREFIX.length());
            Object resolved = resolve(field, principal);
            if (resolved == null) return null;
            if (resolved instanceof Number n) {
                return JsonNodeFactory.instance.numberNode(n.longValue());
            }
            return JsonNodeFactory.instance.textNode(resolved.toString());
        }
        if (node.isArray()) {
            ArrayNode arr = JsonNodeFactory.instance.arrayNode(node.size());
            for (JsonNode child : node) {
                JsonNode sub = substitutePrincipalRefs(child, principal);
                if (sub == null) return null;
                arr.add(sub);
            }
            return arr;
        }
        if (node.isObject()) {
            ObjectNode obj = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                JsonNode sub = substitutePrincipalRefs(e.getValue(), principal);
                if (sub == null) return null;
                obj.set(e.getKey(), sub);
            }
            return obj;
        }
        return node;   // Number / Boolean / Null literals pass through
    }

    /** Dispatch to the framework-built-in userId path or to a registered
     *  resolver. Returns null on unknown ref or unavailable value. */
    private Object resolve(String field, Principal principal) {
        if (principal == null) return null;
        if (USER_ID_REF.equals(field)) return principal.getUserId();
        PrincipalRefResolver r = resolversByKey.get(field);
        return r == null ? null : r.resolve(field, principal);
    }
}
