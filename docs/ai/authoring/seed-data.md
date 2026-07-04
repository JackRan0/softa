# Shipping Seed Data

Part of the [Softa app authoring guide](../README.md). How to ship reference data
(countries, currencies, default categories, …) as JSON files alongside your app.

These files are loaded **on demand** by an operator calling
`POST /SysPreData/loadPreSystemData` — they are **not** auto-loaded at boot. The
*table structure* comes from your entity annotations ([entities.md](entities.md));
this is just the *rows*.

Place files in your module's resources:
- `src/main/resources/data-system/*.json` — shared/platform data (all tenants)
- `src/main/resources/data-tenant/*.json` — per-tenant defaults (loaded via `/SysPreData/loadPreTenantData`)

---

## File naming

`<EntityName>.<Variant>.json`, e.g. `CountryRegion.AllCountries.json`,
`Currency.AllCurrencies.json`, `Department.Default.json`.

- `<EntityName>` **must** match a `@Model` class's simple name — the loader
  resolves rows to that entity.
- `<Variant>` is a free label to distinguish files; the loader ignores it.

## JSON format

Top-level object keyed by the model name, value is an array of row objects:

```json
{
  "CountryRegion": [
    { "id": "AD", "name": "Andorra", "alpha3Code": "AND",
      "dialCode": "376", "currencyCode": "EUR", "continent": "EU" },
    { "id": "AE", "name": "United Arab Emirates", "alpha3Code": "ARE",
      "dialCode": "971", "currencyCode": "AED", "continent": "AS" }
  ]
}
```

Rules:
- **Field names are the Java `@Field` names (camelCase)** — `dialCode`, not
  `dial_code`. The loader maps JSON → entity, then the ORM maps to columns.
- **Enum values use the `@JsonValue` code**, not the constant name:
  `"continent": "EU"` ✓, `"continent": "EUROPE"` ✗.
- **`id` is the seed `preId`, always a string.** The loader upserts by
  `(model, preId)` in `SysPreData`, not by `@Model.businessKey`. For
  `EXTERNAL_ID` models (code-as-id masters such as `CountryRegion` / `Currency`)
  the same value is also the business row's primary key. For generated-id models
  it is tracking-only: the loader removes it before insert, lets the ID strategy
  create the row id, then records the `preId -> rowId` mapping.
- **References use seed ids.** For `MANY_TO_ONE` / `ONE_TO_ONE` /
  `MANY_TO_MANY` fields, put the referenced row's seed `id` (`preId`) in the
  JSON. The loader resolves it to the actual row id before writing.
- **Required fields**: if `@Field(required = true)` and the row omits it, the load
  fails with a NOT NULL error.
- **Omit audit fields** (`createdTime`, `createdBy`, `updatedTime`, …) — the
  loader fills them.

---

## Recipes

**Add a row** — insert it in the right file (keep stable `id` order), validate,
build:
```json
{ "id": "SS", "name": "South Sudan", "alpha3Code": "SSD",
  "dialCode": "211", "currencyCode": "SSP", "continent": "AF" }
```
If it references data that doesn't exist yet (currency `SSP`), add that **first**
in its own file — there's no DB FK, so a dangling reference loads silently.

**Update a row** — edit the fields, keep `id` unchanged. Changing `id` changes
the seed `preId`, so the loader treats it as a different seed row. Reload applies
the UPDATE to the row currently bound to that `preId`.

**Per-tenant defaults** — put them in `data-tenant/` (not `data-system/`); they
load per tenant via `/SysPreData/loadPreTenantData`, stamping the caller's tenant.

---

## Verify

```bash
# JSON validity
for f in src/main/resources/data-system/*.json; do jq . "$f" >/dev/null && echo "OK: $f" || echo "FAIL: $f"; done
# duplicate seed preIds in a file
jq '.CountryRegion[].id' CountryRegion.AllCountries.json | sort | uniq -d
```
Then, after deploy, an operator loads the file(s) and checks the row count.

---

## Common mistakes

1. **`snake_case` field names in JSON** — use camelCase (the Java field name).
2. **`Enum.name()` instead of `@JsonValue`** — `"EUROPE"` won't deserialize; use `"EU"`.
3. **Including audit fields** — omit them; the loader manages them.
4. **Duplicate seed ids in one file** — inconsistent upsert; check with `jq`.
5. **Changing a seed `id` during an update** — this creates a new `preId`
   binding instead of updating the existing one.
6. **Referencing not-yet-loaded data** — add the referenced rows first.
7. **Editing JSON without re-validating** — a trailing comma is silent until load time.

Not for: test fixtures (use `src/test/resources/`), or data that needs
transformation from an old schema (write a service-layer migration job instead).
