package io.softa.starter.tenant.provisioning;

/**
 * Published (synchronously, inside the provisioning transaction) right after a tenant's registry row +
 * owned subscription are created by {@link TenantProvisioningService}. Lets the app react atomically —
 * e.g. seed per-tenant pre-data and provision the first admin — WITHOUT tenant-starter depending on
 * seed-starter / user-starter (both ⊥ to it). Because it fires synchronously within the same
 * transaction, a listener that throws rolls the whole provisioning back.
 *
 * <p>{@code retrySetup} publishes it again for a Draft tenant whose setup failed, so a listener may meet a
 * tenant it has seen before: it continues what is missing and leaves what is done.
 *
 * @param tenantId the newly created tenant's id
 * @param code     the tenant code (as supplied, or slug-generated)
 * @param name     the tenant display name (carried so downstream MQ consumers can name seeded masters)
 */
public record TenantProvisionedEvent(Long tenantId, String code, String name) {
}
