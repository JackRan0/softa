package io.softa.starter.tenant.entitlement;

import java.time.LocalDate;

/**
 * Published (in-process) by {@link SubscriptionProjectionJob} when one of a tenant's subscription periods is a
 * configured number of days from its {@code effectiveTo} AND it is currently the tenant-local reminder
 * hour — one event per due tenant per reminder point. Bridged to MQ by
 * {@code SubscriptionExpiryReminderPublisher} so a user/business module can email the tenant's admins,
 * without tenant-starter depending on user-starter (⊥). Carries {@link LocalDate} in-process; the publisher
 * renders it to an ISO string on the wire.
 *
 * @param tenantId    the tenant whose subscription is expiring
 * @param tenantName  the tenant display name
 * @param planId      the current plan id/code
 * @param effectiveTo the subscription end date (tenant-local)
 * @param daysLeft    whole days remaining until {@code effectiveTo} in the tenant's timezone (0 = last day)
 * @param trial       {@code true} if the expiring subscription's lifecycle is {@code TRIAL} (vs a purchased
 *                    plan) — carried so the notifier can pick trial-vs-renewal wording
 * @param nextStartDate when a later period exists but does <b>not</b> start the day after this one ends, its
 *                    start date; {@code null} when nothing follows at all. A seamless successor never reaches
 *                    here — that case is not reminded about. So a non-null value means "there <i>is</i> a gap
 *                    and it ends on this date", which is a different message: the customer has renewed, but
 *                    not for the months in between, and telling them to "renew before it lapses" would be
 *                    both wrong and dismissible.
 * @param successorPlanId the plan that applies the day after this period ends — the tier rule's answer, not
 *                    just "the next row". {@code null} means nothing covers that day and access really does
 *                    lapse. A non-null value means the tenant keeps working on a <b>lower</b> plan (a
 *                    downgrade; a same-or-higher successor is not reminded about at all), so the mail must say
 *                    "you drop to this" rather than "you lose access" — the second is simply untrue now that
 *                    every tenant owns an open-ended base period underneath whatever it bought.
 */
public record SubscriptionExpiryReminderEvent(Long tenantId, String tenantName, String planId,
                                              LocalDate effectiveTo, int daysLeft, boolean trial,
                                              LocalDate nextStartDate, String successorPlanId) {
}
