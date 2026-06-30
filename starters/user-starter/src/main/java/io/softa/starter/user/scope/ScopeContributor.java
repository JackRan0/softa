package io.softa.starter.user.scope;

import java.util.List;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;

/**
 * SPI — plug-in implementation of a single {@link ScopeType}'s row-filter
 * compilation logic. {@link ScopeRuleCompiler} dispatches each rule to the
 * contributor whose {@link #scopeType()} matches the rule's type.
 *
 * <h3>Why a registry, not a switch</h3>
 * The framework's three generic scope types (ALL, CUSTOM, CREATED_BY_SELF)
 * are domain-agnostic — they live in user-starter. The HR-flavored types
 * (SELF, DIRECT_REPORTS, DEPT_SUBTREE, MANAGED_DEPARTMENTS, LEGAL_ENTITY)
 * carry semantics that depend on the consuming app's domain shape — they
 * live in the consuming module (zingkey-hcm). Dispatching through this
 * registry lets the same compiler handle both, without user-starter
 * importing HR concepts.
 *
 * <h3>How to register</h3>
 * Mark your implementation as {@code @Component}. Spring collects all
 * beans of this type and injects them into {@link ScopeRuleCompiler}. At
 * startup every {@link ScopeType} value should have exactly one
 * contributor — duplicates are rejected, missing contributors log a
 * warning (and any rule referencing the orphaned type degrades to
 * fail-closed at compile time).
 */
public interface ScopeContributor {

    /**
     * The single {@link ScopeType} this contributor implements. Used as
     * the dispatch key by {@link ScopeRuleCompiler}.
     */
    ScopeType scopeType();

    /**
     * Anchor field names this scope type uses on the queried model.
     * {@link ScopeApplicabilityResolver} ORs them with model metadata to
     * decide whether the scope is applicable — admins should not be
     * offered DEPT_SUBTREE on a model that has no {@code departmentId}
     * field, for instance.
     *
     * <p>Return {@link List#of()} for universally-applicable scopes
     * (ALL / CUSTOM are special-cased; CREATED_BY_SELF's
     * {@code createdId} is on every AuditableModel descendant so listing
     * it still works).
     *
     * <p>Multiple fields = OR semantics (any present field enables).
     */
    List<String> applicableFields();

    /**
     * Compile this rule into a {@link Filters} for the queried model.
     * Return {@link Filters#EMPTY} (i.e. {@code new Filters()}) to
     * fail-closed for this rule — the caller still OR-merges with other
     * rules, but this one contributes "no rows" rather than "every row".
     *
     * <p>The {@code principal} carries userId, tenantId, roles, and the
     * domain-specific extension map ({@link Principal#getExtensions()}).
     * Domain-specific contributors should read their own extension by
     * key (e.g. {@code (EmployeeContext) principal.getExtensions().get("employee")})
     * — the generic compiler doesn't pre-resolve domain context for you.
     *
     * <p>The {@code modelName} is the queried model in PascalCase
     * (e.g. {@code "Employee"} / {@code "LeaveRequest"}). Contributors
     * use this to pick the right anchor column when a model deviates
     * from the default convention.
     */
    Filters compile(ScopeRule rule, Principal principal, String modelName);
}
