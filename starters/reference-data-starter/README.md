# Reference Data Starter

Platform-level reference data for international SaaS applications:

- **CountryRegion** — ISO 3166-1 alpha-2 territories (249 entries)
- **Currency** — ISO 4217 active alpha-3 currencies (155 entries)
- **CountrySubdivision** — ISO 3166-2 first-level subdivisions (entity + service; no seed data this release)
- **Continent** — 7-continent enum used by `CountryRegion`

All rows are **platform-scoped, read-only, and shared across tenants**. There
is no tenant-level override; reference data is the same physical row for
every tenant in the deployment.

## Metadata catalog

The three entities (`CountryRegion` / `Currency` / `CountrySubdivision`) and
the `Continent` enum are annotated with `@Model` / `@Field` / `@OptionSet` /
`@OptionItem` (see
[`framework/softa-orm`](../../framework/softa-orm/README.md#metadata-annotations)).
When this starter's package is in `scanner-scope`, `MetadataAnnotationScanner`
reconciles them into `sys_*` rows stamped with the runtime's `app_code`
(`system.app-code`). Per-tenant runtime metadata customization is out of
scope — tenants cannot add custom fields onto these models via Studio.

Schema drift between the annotations and `sys_*` triggers a startup WARN
(when this package is out of `scanner-scope`) or an automatic ALTER (when it
is in scope).

The framework `Language` enum lives in `softa-base` and carries
`@OptionSet` / `@OptionItem` like any other enum (the annotations live in
`io.softa.framework.base.annotation` precisely so base enums avoid a
base → orm cycle); the scanner manages its `sys_option_set` rows the same way.
Locale formatting facts (date/time patterns, decimal and grouping separators)
are **not stored** — they derive from the JDK's built-in CLDR data via the
`Language` enum accessors (`toLocale()`, `decimalSeparator()`, `datePattern()`,
…); browsers derive the same values from the tag via `Intl.*`. (The former
`LanguageProfile` entity that stored these per tenant was retired in 2026-06.)

## Code-as-id masters

`Currency` and `CountryRegion` use **code-as-id** (`IdStrategy.EXTERNAL_ID`):
the primary key **`id` is the ISO code** (alpha-3 for currency, alpha-2 for
country). References store that code in an id-FK column — e.g.
`CountryRegion.currencyCode` → `Currency.id`, `TenantInfo.defaultCountry` →
`CountryRegion.id`.

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>reference-data-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Tables

Apply `src/main/resources/sql/reference-data-starter.sql` for a standalone DDL
baseline. When the annotation scanner is active (`scanner-scope` includes this
package), table shape is driven by the `@Model` entities — prefer verifying
against the compiled catalog:

```sql
SELECT model_name, app_code FROM sys_model
 WHERE model_name IN ('CountryRegion', 'Currency', 'CountrySubdivision');
```

| Entity | Rows (seeded) | Primary key (`id`) | Notes |
|---|---|---|---|
| `CountryRegion` | 249 | ISO 3166-1 alpha-2 (`CN`, `US`, …) | Indexed on `continent`, `currencyCode`, `eea` |
| `Currency` | 155 | ISO 4217 alpha-3 (`USD`, `CNY`, …) | `decimalPlaces` is critical for monetary arithmetic |
| `CountrySubdivision` | 0 | ISO 3166-2 code | Schema reserved; populated when address/tax features land |

`CountryRegion.currencyCode` references `Currency.id` (same string value as the
ISO alpha-3 code). `CountrySubdivision.countryCode` references
`CountryRegion.id`. Validation happens at the service layer; the database does
not enforce physical FKs because reference data is loaded as denormalized seed.

## Services

| Service | Primary method | Cached |
|---|---|---|
| `CountryRegionService` | `findByCode(String code)` | yes (Redis, TTL 1h) |
| `CountryRegionService` | `findByContinent(Continent)` | no |
| `CountryRegionService` | `findEeaMembers()` | no |
| `CurrencyService` | `findByCode(String code)` | yes (Redis, TTL 1h) |
| `CountrySubdivisionService` | `findByCode(String)`, `findByCountryCode(String)`, `findByParentCode(String)` | no |

`findByCode` is the hot-path API name; internally it loads by **`id`**
(the ISO code). Cache is mandatory in production. Cache is invalidated
automatically on `updateOne` / `deleteById` paths through the service.

## Seed Data — platform seed files

Seed data lives in `src/main/resources/data-system/`:

- `Currency.AllCurrencies.json` — 155 active ISO 4217 currencies
- `CountryRegion.AllCountries.json` — 249 ISO 3166-1 alpha-2 territories
- *(no `CountrySubdivision` JSON yet — entity + service are ready, JSON ships
  with address/tax feature)*

**Seed data is NOT auto-loaded at application startup.** This is by design:

1. Operators retain control over which environments load seed data and when.
2. Reference data may be edited in admin UI (`frozen=true` on the row's
   `SysPreData` binding protects an operator override from the next load).
3. One person loads, once per deploy — no load-time race between instances.

The app lists both files under `platform` in its `seed-manifest.yml`
(`data-system/Currency.AllCurrencies.json`, `data-system/CountryRegion.AllCountries.json`),
**Currency first**: a platform load runs in list order, and
`CountryRegion.currencyCode` references `Currency.id`. The platform
admin then loads them with the other pending platform files from the Seed Data
page (`POST /SeedData/platform/load`) — see the
[seed-starter README](../seed-starter/README.md).

A platform file is the data: it loads again, whole, whenever it changes in a
later build. Frozen rows are preserved untouched.

**Without `seed-starter` on the classpath**, the JSON files still ship in the
jar but nothing loads them. Ops must seed the tables through some other path
(direct SQL, custom script).

## Continent enum

`Continent` lives in this starter (not `softa-base`) because its only
consumer is `CountryRegion.continent`. Seven values: `AS`, `EU`, `AF`, `NA`,
`SA`, `OC`, `AN` — the 7-continent model (the most widely-used scheme across
education, UN regional groupings, IAB taxonomies). Codes are project
conventions; they are **not** ISO 3166 / UN M49 codes.

## Consumers in this codebase

| Module | Field / usage |
|---|---|
| `message-starter` | `SmsProviderRegion.regionCode` → `CountryRegion.id`, `dialCode` denormalized from country master |
| `framework/softa-orm` | `TenantInfo.defaultCountry` → `CountryRegion.id`, `TenantInfo.defaultCurrency` → `Currency.id` |

## ISO 4217 fraction digits — quick reference

This is the most common field operators get wrong. Validate against ISO 4217
when loading seed updates:

| `decimalPlaces` | Currencies (sample) |
|---|---|
| 0 | JPY, KRW, VND, UGX, RWF, PYG, MGA, KMF, XAF, XOF, XPF, BIF, CLP, DJF, GNF, ISK |
| 2 | USD, EUR, GBP, CNY, AUD, CAD, … (most currencies) |
| 3 | BHD, IQD, JOD, KWD, LYD, OMR, TND |

Mismatching `decimalPlaces` silently breaks monetary arithmetic, so this is
covered by unit tests against well-known special-case currencies.
