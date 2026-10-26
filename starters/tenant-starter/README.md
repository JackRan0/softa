# Tenant Starter

Multi-tenancy infrastructure for SaaS applications built on Softa: the
`TenantInfo` registry, tenant lifecycle/status, and the runtime plumbing that
isolates data across `@Model(multiTenant = true)` entities. It also ships
plan/entitlement versioning (版本计费 — which modules a tenant's plan unlocks)
and a separate commerce sub-domain (service catalog, orders, payments).

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>tenant-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Depends on `softa-web`, `reference-data-starter` (for `Currency` / `CountryRegion`
lookups on `TenantInfo`), `stripe-java` (payments), and `cron-starter` (**optional** — powers the built-in
`TenantMaintenanceCronConsumer` for subscription-expiry; a deployment
on a different scheduler omits it, the consumer stays dormant via `@ConditionalOnClass`, and the app
drives the jobs itself). Auto-configured by
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
| `TenantInfo` | Tenant registry — `code`, `name`, `status` (ACTIVE/SUSPENDED/CLOSED), `defaultLanguage`/`defaultTimezone`/`defaultCurrency`(→`Currency.id`)/`defaultCountry`(→`CountryRegion.id`), `dataRegion`, and a nullable `subscriptionId` (1:1 owner FK → `TenantSubscription`); soft-delete, distributed id. **No plan/lifecycle columns** — the version lives on `TenantSubscription`. |
| `TenantSubscription` | The tenant's owned 1:1 subscription — one row per tenant, owned via `TenantInfo.subscriptionId`, carrying no `tenantId`. **Every business column is a projection** of the tenant's period rows as of its own local today: `subscriptionStatus`, `planId` (FK → `Plan`), `periodType`, `currentPeriodId`, `currentStartDate`/`currentEndDate`, `nextStartDate`, plus `projectedForDate`/`projectedTime`. Ops never edits it. Also declares a virtual `periods` (`ONE_TO_MANY`) so a create form can render period inputs from metadata — **not** a write path (see Provisioning below) |
| `TenantSubscriptionPeriod` | The record layer: one row per period sold — `subscriptionId` (FK, `CASCADE`), `effectiveStartDate`, `effectiveEndDate` (null = open-ended), `planId`, `periodType` (`TRIAL`/`PAID`), `lastReminderDate`. Unique on `(subscriptionId, effectiveStartDate)`. Gaps between periods are legitimate: the tenant runs on the floor plan in between |
| `Plan` / `PlanEntitlement` | System-level plan catalog — code-as-id, `tier` (ordering; lowest = the fallback floor), `active` — plus the module ids each plan entitles. Deployment-authored seed data (no plan id is hardcoded in the starter) |
| `ServiceProduct` | Commerce catalog (`category`, `price`, `duration`, `active`) — a separate sub-domain from plan/entitlement |
| `ServiceOrder` | Orders (`orderNumber`, `orderStatus`, `amount`) |
| `ServiceRecord` | Service execution records |
| `PaymentRecord` | Payments (`paymentMethod`, `paymentStatus`, amounts) |

## Entitlement (versioning / 版本计费)

A tenant's entitled module set is resolved from its 1:1 `TenantSubscription`, never the nav tree — so
tenant-starter needs no user-starter dependency:

- **`EntitlementResolver`** (behind the framework `EntitlementService` SPI) reads
  `TenantSubscription.planId` → `plan_entitlement` → module set, cached in Redis (`entl:{tenantId}`,
  TTL capped at the tenant's local midnight so the cache cannot outlive the day it was computed for).
  It reads the projected main-table row rather than scanning periods per request — but never blindly:
  it first compares `projectedForDate` against that tenant's local today and **recomputes on the spot**
  when they differ, so a stale projection repairs itself on first touch. Compared with `!=`, never `<`:
  moving a tenant's timezone westward moves its local today *backwards*, and a "projected before today"
  test would then never fire again. `null` (never projected) also takes the recompute branch, which is why
  the migration needs no backfill.
- **Fallback / floor** = the catalog's **lowest-`tier` plan** — no plan id is hardcoded, so any
  deployment's own plan naming works. No plan seeded → empty entitlement (unpaid = no access). The same
  rule supplies the default plan at provisioning. Two ways to seed the floor, both supported without code
  changes:
  - **Free floor** (a product with a free tier): seed the lowest-tier plan with a base module set — an
    expired tenant lands on it and keeps the base modules.
  - **Paid floor** (a product whose cheapest tier costs money): seed a lowest-tier plan with **zero**
    `plan_entitlement` rows and `active = false`, so an expired tenant is entitled to nothing. Without
    it the floor would be the cheapest *paid* plan and expired tenants would keep it for free. The floor
    lookup deliberately does **not** filter on `active`, so an unsellable placeholder plan works as the
    floor; the "`planId` must not be the floor" guard then also stops ops from selling it by hand.
- **`SubscriptionProjectionService`** is the **only** writer of the main table. `refresh(tenant)` is
  staleness-gated (no-op when `projectedForDate` already equals the tenant's local today); `refreshNow`
  is the unconditional variant the write path uses; `refreshAll` batches the scheduled warm-up in two
  queries. It resolves the period covering that date, derives `subscriptionStatus`
  (`PAID`/`TRIAL`/`PENDING`/`EXPIRED`), and fires an entitlement-changed event
  **only when `planId` actually changed** (evict `entl:` + MQ role-grant cleanup). Overlaps are rejected
  on write, so more than one covering period is corrupt data: it logs ERROR and picks the latest start
  (then highest id) — deterministic, because silently varying which plan a tenant gets is worse than a
  wrong-but-stable answer.
- **`SubscriptionProjectionJob`** is the warm-up, not the guarantee — correctness comes from the
  read-time self-heal above. It is **not** `@Scheduled`; **tenant-starter's own
  `TenantMaintenanceCronConsumer`** drives it off an hourly `sys_cron` row `SubscriptionExpiry`
  (`CrossTenant`; tenant-starter depends on cron-starter), so tenants spanning 24 UTC hours each roll over
  at their own local midnight. This cron's domain is billing, so its trigger lives here in tenant-starter,
  not in the HR business module. A second, **non-projecting** pass (`remindUpcoming`) fires **expiry
  reminders**: a configured number of days before the current period's end (default 7 and 1), at the
  tenant-local reminder hour (default 10:00), it publishes a `SubscriptionExpiryReminderEvent` →
  `SubscriptionExpiryReminderMessage` (softa-base MQ) so a user module can email the tenant's admins. It
  fires **once per tenant-local day**, at or after the reminder hour, deduped via the period's
  `lastReminderDate` (so a misfire catch-up or manual re-run the same day does not double-send, and a
  missed reminder hour still sends later that day). Whether — and what — to remind is decided by
  **what will apply the day after** the period ends, through the same tier rule the projection writes with:
  a same-or-higher successor is silent (renewed, or a higher plan already covers); a **lower** one is a
  *downgrade* and carries `successorPlanId` so the mail can say "you drop to this" instead of the untrue
  "you lose access"; a gap-separated successor carries `nextStartDate`; nothing at all gets the plain expiry
  ask. Asking "is there a period starting after this one" instead — the pre-overlap rule — got both of the
  first two wrong, because the floor period starts at tenant creation and so is never *after* anything. The
  message also carries a `trial` flag for trial-vs-renewal wording, which the downgrade case outranks. Cadence overridable via `tenant.subscription.reminder.{hour,days-before}`;
  the pure `dueReminderDays` seam keeps the day/hour/dedup decision clock-free for tests. Reminders run
  their own query — only the batch owner-load is shared with the refresh (no per-row `TenantInfo` N+1).
- **`TenantSubscriptionPeriodService`** is the single guarded entry point for period writes. Four guards:
  the end must not precede the start; **at most one** period on the floor plan (that row is the tenant's
  base access, written by provisioning — a second would put one entitlement in two places); the
  floor period's **start date is immutable** (it is the tenant's creation day, which is history, not a
  setting — its *end* date is settable and that is the whole mechanism for time-boxing base access); and the
  floor period **cannot be deleted** (the resolver reads a missing floor row as "predates the migration" and
  falls back to granting the floor plan's modules, so deleting it would silently restore access an operator
  had just revoked by ending it). Update guards check the *stored* row, so a half-specified patch cannot slip
  past. Every write refreshes the projection afterwards.

  **Overlap is deliberately not guarded.** It used to be, along with "the plan must not be the floor" and "a
  trial must sit above the floor" — all three were removed. Provisioning gives every tenant an open-ended
  floor period, so every sale overlaps at least that one and rejecting overlap would make selling impossible.
  Which period applies is decided by **plan tier** instead (`PeriodSelection.winnerOn` — highest tier
  covering the date, ties by latest start then id), which is what makes "sell Pro on top of free" mean the
  tenant gets Pro. That one rule is shared by the projection and the expiry reminder rather than copied:
  a reminder disagreeing with the projection is two beliefs about one subscription, and the customer is told
  the wrong one. Because `ModelServiceImpl` does **not** route through per-model `EntityService`, shadow
  controllers cover all 16 generic write endpoints on both models — the period table's forward to this
  service, the main table's are rejected outright — and a reflective test fails if upstream adds a 17th.
- **Provisioning** (`TenantProvisioningService`, behind the shadowed `POST /TenantInfo/createOne`)
  creates the registry row plus an empty projection row, writes the open-ended floor-plan period every
  tenant owns (`ensureFreePeriod` — idempotent, and provisioning **refuses outright** when the catalog has no
  tiered plan, because a tenant with no period looks created while resolving to nothing), then records any
  periods the
  request carried under `subscriptionId.periods.Create[]` — so a customer buying Pro on day one is one
  submit rather than "create on the floor plan, then upgrade". That relation is parsed here and pushed
  through the period service **one row at a time**, deliberately *not* handed to the framework's
  nested-relation pipeline: that persists via the generic `ModelService`, which runs none of the guards
  and does not refresh the projection. Sequential calls also let the floor-cardinality guard see each earlier row of
  the same payload. For the same reason `updateOne` / `updateOneAndFetch` **reject** a nested
  `subscriptionId.periods` patch — on update there is no typed path to route it through, and editing
  periods belongs to `/TenantSubscriptionPeriod/**`.

## Provisioning status (seed orchestration)

A newly provisioned tenant's business data is seeded across modules asynchronously over MQ, and how far
that has got is carried by **`TenantStatus` itself** — `DRAFT` at creation, `INITIALIZING` while seeders
run, `ACTIVE` once they are all in.

There used to be a second, orthogonal `provisioningStatus` axis (`INITIALIZING` / `READY` / `FAILED`)
alongside it. It was merged away: two columns described one tenant, so "is this tenant usable" needed both
and the pair could disagree. `READY` became `ACTIVE`, and `FAILED` became `DRAFT` — a tenant that was never
built and one whose build failed are the same thing to look at. There is no rebuild: a seeder is expected to
be idempotent, so a failed setup is finished by running the seeders again, not by discarding what they wrote.
**`POST /TenantInfo/retrySetup?id=`** does that for a `DRAFT` tenant: it reopens the status and announces the
tenant again exactly as provisioning did, and each seeder continues where it stopped — seed-starter runs only
the seed files the tenant has not run successfully, and the business seeders skip what they find in place.
An `INITIALIZING` tenant is refused, because its seeders are still running. Unlike the old
axis this one **does** gate login: a tenant mid-setup is refused with "still being set
up" rather than a generic failure, checked before the active check so that specific message survives.

- **`TenantProvisioningStatusService`** — the per-tenant completion latch. Owns `TenantSeedProgress`
  (`{tenantId, seederKey}`) and folds each seeder's completion (`SeederCompletedMessage` → the
  `SeederCompletedCoordinator`) into the status: `ACTIVE` once `doneKeys ⊇ expected-seeders`
  (`softa.tenant.provisioning.expected-seeders`). Business-agnostic — it only ever sees opaque
  `seederKey` strings, never imports a business module.
- **Dependency gate** — `dependenciesSatisfied(tenantId, dependsOn, justCompletedKey)` = `doneKeys ⊇
  dependsOn` (set-containment, order-independent), so a downstream seeder can wait on its upstreams.
- **Failure** — each seed consumer calls `markSeederFailed(tenantId, seederKey, redeliveryCount)` from its
  catch block, passing the failed message's Pulsar redelivery count, then rethrows. Every failure names the
  seeder on its progress row at once. While MQ still has deliveries left (the `tenantSeedRetryPolicy` cap) the
  tenant stays `INITIALIZING`; the last delivery's failure moves it to `DRAFT`, where Retry Setup takes it — so
  a retry never runs beside a redelivery of the same setup. Only an `INITIALIZING` tenant is moved, by one
  conditional update, and a failure reported for a tenant past setup (`ACTIVE`, `SUSPENDED`, `CLOSED`) — which
  can only come from a duplicate message — changes nothing. `markSeederReady` brings a tenant on to `ACTIVE`
  once the seed completes. There is **no timeout sweep**:
  the `ProvisioningTimeout` cron that once moved a tenant stuck in `INITIALIZING` to `DRAFT` was removed, so a
  seed that never reaches a catch block (message lost, consumer down) leaves the tenant `INITIALIZING`. Find
  such a tenant by querying `INITIALIZING` rows, and set it to `DRAFT` by hand before retrying its setup.
- **`TenantMaintenanceCronConsumer`** carries `SubscriptionExpiry` (shipped in
  `data-system/SysCron.TenantMaintenance.json`, which the app lists under `platform` in its `seed-manifest.yml`;
  an unlisted file never loads, and the cron never runs). **cron-starter is optional** (`@ConditionalOnClass` on the
  consumer + `<optional>true</optional>` in the pom): a deployment on a different scheduler (Quartz,
  `@Scheduled`, XXL-Job, …) omits cron-starter — the consumer stays dormant and the app drives
  `SubscriptionProjectionJob` from its own trigger.

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
