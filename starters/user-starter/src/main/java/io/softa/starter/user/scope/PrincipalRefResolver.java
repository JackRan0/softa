package io.softa.starter.user.scope;

import java.util.Set;

import io.softa.starter.user.dto.Principal;

/**
 * SPI — resolves a {@code $principal.<field>} dynamic reference used inside
 * a CUSTOM scope rule's {@code scopeExpr}. Multiple resolvers can be
 * registered; {@code CustomScopeContributor} aggregates them by
 * {@link #refKeys()}.
 *
 * <h3>Why pluggable</h3>
 * {@code $principal.userId} is universal (it's on {@link Principal}
 * itself). But domain-specific refs (e.g. an app-defined
 * {@code $principal.<domainId>}) resolve against a slot in
 * {@code principal.extensions} that only the owning module knows how to
 * read. Plugging the resolution behind this SPI keeps user-starter
 * generic while allowing any consuming module to advertise its own refs.
 *
 * <h3>Failure semantics</h3>
 * Return {@code null} when the ref is known but the underlying value is
 * missing (e.g. an app-defined ref for a user that has no matching
 * domain context). {@code CustomScopeContributor} treats any null
 * resolution as fail-closed for the whole rule — partial substitution
 * would silently let a literal {@code "$principal.<ref>"} string
 * compare leak rows.
 */
public interface PrincipalRefResolver {

    /**
     * Ref keys this resolver claims, without the {@code $principal.} prefix.
     * E.g. {@code Set.of("employeeId", "departmentId", "legalEntityId")}.
     * The compiler picks the resolver whose {@code refKeys()} contains
     * the requested key; duplicate claims across resolvers throw at
     * startup.
     */
    Set<String> refKeys();

    /**
     * Resolve a ref to its concrete value for the current principal.
     * Return {@code null} when the value is unavailable (caller fails
     * the whole CUSTOM rule closed).
     *
     * <p>The value is typically a {@link Long} (id) or {@link String}.
     * {@code CustomScopeContributor} binds it directly into the
     * scope-rule JSON before deserialization.
     */
    Object resolve(String refKey, Principal principal);
}
