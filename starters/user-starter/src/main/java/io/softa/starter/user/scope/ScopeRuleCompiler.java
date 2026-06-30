package io.softa.starter.user.scope;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;

/**
 * Compile a list of {@link ScopeRule} (OR-combined) into a {@link Filters}
 * that {@code ScopeFilterAspect} AND-merges into the user's query.
 *
 * <h3>Dispatch model</h3>
 * Each {@link ScopeType} has exactly one {@link ScopeContributor} bean
 * registered against it. The compiler is a pure dispatcher:
 * <ol>
 *   <li>{@link ScopeType#ALL} — handled inline (returns null sentinel
 *       meaning "no scope restriction").</li>
 *   <li>Every other type — looked up in the {@code contributorsByType}
 *       map and the rule is forwarded to the contributor's
 *       {@link ScopeContributor#compile} method.</li>
 * </ol>
 *
 * <h3>Why this design</h3>
 * The framework's three generic scope types (ALL, CUSTOM,
 * CREATED_BY_SELF) ship with user-starter. Five HR-flavored types
 * (SELF, DIRECT_REPORTS, DEPT_SUBTREE, MANAGED_DEPARTMENTS, LEGAL_ENTITY)
 * live in the consuming module (zingkey-hcm) as their own
 * {@link ScopeContributor} beans. The compiler doesn't import HR
 * concepts; it just dispatches by ScopeType.
 *
 * <h3>Fail-closed semantics</h3>
 * When a rule depends on principal state that's missing (e.g. SELF
 * without an EmployeeContext extension, CUSTOM with an unresolvable
 * {@code $principal.xxx} ref), the contributor returns
 * {@link Filters#EMPTY} — yields no rows rather than leaking the whole
 * table.
 */
@Slf4j
@Component
public final class ScopeRuleCompiler {

    private final ScopeApplicabilityResolver applicability;
    private final Map<ScopeType, ScopeContributor> contributorsByType;

    /**
     * Spring constructor — injects every {@link ScopeContributor} bean
     * the application context knows about. At startup we validate that
     * each non-ALL {@link ScopeType} has exactly one contributor; missing
     * contributors are warned (their rules degrade fail-closed at runtime),
     * duplicates throw.
     */
    public ScopeRuleCompiler(
            ScopeApplicabilityResolver applicability,
            List<ScopeContributor> contributors) {
        this.applicability = applicability;
        Map<ScopeType, ScopeContributor> map = new EnumMap<>(ScopeType.class);
        for (ScopeContributor c : contributors) {
            ScopeContributor prior = map.putIfAbsent(c.scopeType(), c);
            if (prior != null && prior != c) {
                throw new IllegalStateException(
                        "Multiple ScopeContributor beans for " + c.scopeType()
                                + ": " + prior.getClass().getName() + " and " + c.getClass().getName());
            }
        }
        // ALL is handled inline — no contributor expected for it.
        for (ScopeType type : ScopeType.values()) {
            if (type == ScopeType.ALL) continue;
            if (!map.containsKey(type)) {
                log.warn("ScopeRuleCompiler — no ScopeContributor registered for {}; "
                        + "rules of this type will fail-closed", type);
            }
        }
        this.contributorsByType = Map.copyOf(map);
    }

    /**
     * Compile the rules into one OR-combined Filters. Returns:
     * <ul>
     *   <li>{@code null} — any rule is ALL → no scope filter (caller appends nothing).</li>
     *   <li>{@link Filters#EMPTY} — rules list is empty OR every rule degraded to EMPTY.</li>
     *   <li>otherwise — OR-merged Filters expression.</li>
     * </ul>
     */
    public Filters compile(List<ScopeRule> rules, PermissionInfo pi, String modelName) {
        if (rules == null || rules.isEmpty()) return emptyFilter();
        for (ScopeRule r : rules) {
            if (r.getScopeType() == ScopeType.ALL) return null;
        }
        Principal principal = pi == null ? null : pi.getPrincipal();

        Filters out = null;
        for (ScopeRule rule : rules) {
            Filters one = compileOne(rule, principal, modelName);
            if (one == null) continue;
            if (Filters.isEmpty(one)) continue;
            out = (out == null) ? one : out.or(one);
        }
        return out == null ? emptyFilter() : out;
    }

    private Filters compileOne(ScopeRule rule, Principal principal, String modelName) {
        if (rule == null || rule.getScopeType() == null) return emptyFilter();
        ScopeType type = rule.getScopeType();
        if (type == ScopeType.ALL) return null;
        // Fail-fast on rules whose scope doesn't apply to this model (the
        // anchor column was removed or the nav points at the wrong model).
        if (modelName != null && !applicability.applicableFor(modelName).contains(type)) {
            return emptyFilter();
        }
        ScopeContributor contributor = contributorsByType.get(type);
        if (contributor == null) {
            // No bean registered for this type — fail-closed.
            log.debug("No ScopeContributor for {}, degrading to empty", type);
            return emptyFilter();
        }
        try {
            return contributor.compile(rule, principal, modelName);
        } catch (IllegalStateException ise) {
            // Contributors throw IllegalStateException for config errors
            // (e.g. DeptCascadePathResolver returning empty for a model
            // that ApplicabilityResolver accepted). Propagate so it
            // surfaces at the first request.
            throw ise;
        } catch (Throwable t) {
            log.warn("ScopeContributor[{}] threw; degrading to empty", type, t);
            return emptyFilter();
        }
    }

    /** Empty Filters — matches no rows. */
    private static Filters emptyFilter() {
        return new Filters();
    }
}
