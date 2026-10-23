package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * The tenant's single lifecycle axis — how far along it is, and whether it may be used.
 *
 * <p>Setup and operation used to be two independent fields ({@code status} plus a
 * {@code provisioningStatus} of INITIALIZING / READY / FAILED). One axis replaces both: a tenant is in
 * exactly one of these states, so "is it usable" has one answer instead of a pair to reconcile. The
 * former READY collapses into {@link #ACTIVE}, and a failed setup returns to {@link #DRAFT} rather
 * than getting a state of its own — nobody acts differently on "never set up" and "setup failed";
 * both mean "not built yet, press the button".
 *
 * <p>{@link #SUSPENDED} and {@link #CLOSED} currently mean exactly one thing: <b>login is refused</b>
 * (see {@code isTenantActive}). No grace period, no automatic transition between them, no
 * export-only mode — those were discussed and deliberately deferred, so do not read intent into the
 * two being separate beyond "an operator chose one".
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum TenantStatus {
    /** Created, not built — or built and it failed. Retrying the setup starts from here. */
    DRAFT("Draft"),
    /** Seeders are running. Not usable yet, and a retry is refused so the setup cannot double-run. */
    INITIALIZING("Initializing"),
    /** Fully set up and usable. */
    ACTIVE("Active"),
    SUSPENDED("Suspended"),
    CLOSED("Closed"),
    ;

    @JsonValue
    private final String status;

}
