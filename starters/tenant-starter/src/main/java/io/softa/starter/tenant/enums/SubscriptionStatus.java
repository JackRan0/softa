package io.softa.starter.tenant.enums;

import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonValue;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import io.softa.framework.base.enums.OptionItemTone;

/**
 * A tenant's subscription standing, projected onto its {@code TenantSubscription} row from the period
 * detail rows as of that tenant's local today. Never written by hand — the projection refresh is the
 * only writer.
 *
 * <h3>Names are deliberately neutral</h3>
 * The displayed wording lives in the option-item labels (seed data), so a deployment can relabel without
 * touching code. Two naming choices matter:
 *
 * <ul>
 *   <li><b>{@code PAID}, not {@code ACTIVE}</b> — {@code TenantStatus.ACTIVE} already means "may log in"
 *       (the operational axis). The two axes are orthogonal and can contradict: a tenant can be suspended
 *       by ops while still inside a paid period.</li>
 *   <li><b>No blanket {@code FREE} / {@code AT_FLOOR}</b> — the floor plan is a period like any other now,
 *       so its standing follows from its dates: {@link #TRIAL} while the row is open, {@link #EXPIRED} once
 *       an operator gives it an end date that passes. Avoiding the word "free" also keeps the enum honest on
 *       a deployment whose lowest tier is paid.</li>
 * </ul>
 *
 * <h3>Four states, not five</h3>
 * {@code NEVER_SUBSCRIBED} meant "no period rows at all", which every tenant passed through at birth.
 * Provisioning now writes a base period at creation, so that state became unreachable — a tenant always has
 * at least one row and its standing is always derivable from dates. Keeping an unreachable value would leave
 * the UI with a branch nothing produces and the next reader hunting for what triggers it.
 */
@Getter
/*
 * Labels deliberately differ from item codes on three of these. The code is persisted and read by the
 * frontend, so it is fixed for good; the label is display text and should read well. Making them match would
 * let a decision taken for storage stability dictate the wording ops sees — "Paid" lands as a payment state
 * rather than a subscription one, in a set whose other members are subscription states. Where the derived
 * name is already right (PENDING, EXPIRED) no label is declared at all, so a declared one always signals a
 * deliberate override.
 */
@OptionSet(description = "Projected subscription standing of a tenant as of its local today", renamedFrom = "SubscriptionStatus")
public enum SubscriptionStatus {

    /** A paid period covers the projection date. */
    @OptionItem(label = "Subscribed", itemTone = OptionItemTone.INFO, sequence = 1)
    PAID("Paid"),

    /** A trial period covers the projection date. */
    @OptionItem(label = "On trial", itemTone = OptionItemTone.WARNING, sequence = 2)
    TRIAL("Trial"),

    /**
     * No period covers the projection date, but a later one exists — bought, not yet started. Recording a
     * future period is not early activation, and the UI must say so.
     *
     * <p>Named for the word the requirement uses (待生效): "Pending" reads as "waiting to begin", where
     * "Scheduled" reads as an arrangement already in force. Code and constant both say Pending, so the value
     * the frontend switches on and the value ops reads are the same word — the earlier split (code
     * {@code Scheduled}, label "Pending") meant a reader of either side had to know about the other.
     *
     * <p>This is a replacement, not a tracked rename. The scanner therefore adds "Pending" and only warns
     * about "Scheduled" — it never DROPs — so V37 does both halves the scanner will not: it rewrites the rows
     * that still hold the old value (business data, which the scanner never touches) and deletes the old
     * option item, which would otherwise stay in every picker offering a value nothing can produce.
     */
    @OptionItem(itemTone = OptionItemTone.NEUTRAL, sequence = 3)
    PENDING("Pending"),

    /**
     * Periods exist but none covers the projection date and none is upcoming — the tenant lapsed. Reachable
     * even for a tenant that bought nothing: its base period lapses too if an operator gave it an end date.
     */
    @OptionItem(itemTone = OptionItemTone.NEUTRAL, sequence = 4)
    EXPIRED("Expired");

    @JsonValue
    private final String code;

    SubscriptionStatus(String code) {
        this.code = code;
    }
}
