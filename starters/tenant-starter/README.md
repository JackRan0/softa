# Tenant Starter

Multi-tenancy infrastructure for SaaS applications built on Softa: the
`TenantInfo` registry, tenant lifecycle/status, and the runtime plumbing that
isolates data across `@Model(multiTenant = true)` entities. It also ships a
subscription/billing sub-domain (service catalog, orders, payments).

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>tenant-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Depends on `softa-web`, `reference-data-starter` (for `Currency` / `CountryRegion`
lookups on `TenantInfo`), and `stripe-java` (payments). Auto-configured by
`io.softa.starter.tenant.TenantAutoConfiguration` (component-scan). Requires
Redis for the active-tenant cache.

## Enabling

```yaml
system:
  enable-multi-tenancy: true      # the only tenant-specific key; master switch for isolation
```

With this off, `multiTenant` isolation is not applied. Keep it consistent with
your `@Model(multiTenant = true)` usage (see the app authoring
[config guide](../../docs/ai/authoring/config.md)).

## Entities

Under `io.softa.starter.tenant.entity`:

| Entity | Purpose |
|---|---|
| `TenantInfo` | Tenant registry — `code`, `name`, `status` (ACTIVE/SUSPENDED/CLOSED), `lifecycle`, `defaultLanguage`/`defaultTimezone`/`defaultCurrency`(→`Currency.id`)/`defaultCountry`(→`CountryRegion.id`), `dataRegion`, `planId`, `subscriptionId`; soft-delete, distributed id |
| `ServiceProduct` | Service/subscription catalog (`category`, `price`, `duration`, `active`) |
| `ServiceOrder` | Orders (`orderNumber`, `orderStatus`, `amount`) |
| `ServiceRecord` | Service execution records |
| `PaymentRecord` | Payments (`paymentMethod`, `paymentStatus`, amounts) |

## How isolation works

Tenant identity travels on the request `Context` (`io.softa.framework.base.context`),
managed thread-locally by `ContextHolder`; the auth layer populates
`Context.tenantId` from the session/login. Then the ORM does the rest:

- **Reads** — for a `@Model(multiTenant = true)` entity the ORM automatically adds
  `WHERE tenant_id = :tenantId` (reserved column `tenant_id`).
- **Writes** — `tenant_id` is auto-filled from `Context.tenantId`.
- **Bypass** — `@CrossTenant` (or `Context.crossTenant = true`) skips tenant
  filtering for system operations.
- **Fan-out** — `@PerTenant` runs a `void` method once per **active** tenant, in
  parallel on virtual threads (capped at 100 concurrent to protect the DB pool).

You generally don't call any of this directly — declaring `@Model(multiTenant = true)`
and enabling `system.enable-multi-tenancy` is enough.

## Public API

`TenantInfoService` (the framework SPI, implemented here):

- `List<Long> getActiveTenantIds()` — active tenant ids (Redis-cached).
- `boolean isTenantActive(Long tenantId)` — existence + `ACTIVE` status (cached).
- `void deactivate(Long tenantId)` — move to `SUSPENDED`, evict caches, force
  affected users to re-login.

`ServiceProduct` / `ServiceOrder` / `ServiceRecord` / `PaymentRecord` each have a
standard `EntityService` + `EntityController` (CRUD/query under `/ServiceProduct`,
`/ServiceOrder`, …) — see the app authoring
[controllers-services guide](../../docs/ai/authoring/controllers-services.md).
