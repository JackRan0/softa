package io.softa.framework.base.message;

/**
 * MQ payload a seeder publishes after it finishes seeding one tenant. Two kinds of subscriber consume it:
 * the tenant-starter coordinator (folds it into the tenant's provisioning progress; the tenant becomes
 * Active once the expected set is complete), and any downstream business seeder that depends on this one
 * (fires only after its upstream reports done — the ordering mechanism in mode-2). The {@code seederKey} is
 * opaque to the framework — business modules assign and interpret it. A framework DTO in
 * {@code base.message} so the producer (any business module) and consumers (tenant-starter + other modules)
 * share it without a cycle.
 *
 * <p>It is published on success only. A seeder that fails marks itself failed from its catch block
 * ({@code TenantProvisioningStatusService.markSeederFailed}), which puts the tenant back to Draft, and
 * rethrows so MQ redelivers; Retry Setup starts a Draft tenant's setup again.
 *
 * @param tenantId  the tenant being seeded
 * @param seederKey the reporting seeder's opaque key (e.g. "pre-data", "corehr")
 */
public record SeederCompletedMessage(Long tenantId, String seederKey) {
}
