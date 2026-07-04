# AI & Authoring Documentation

Guidance for AI agents (Claude / Cursor / Copilot) **and** humans writing code
against Softa — both in this repo and in downstream applications that consume
Softa as a Maven SDK.

The docs are split by **audience**:

## For downstream applications → [`authoring/`](authoring/)

If you added `io.softa:*-starter` dependencies to your `pom.xml` and are building
an app, everything you need to author features is in [`authoring/`](authoring/).
It is **self-contained** — no framework internals, no assumptions about this
monorepo's layout.

| Guide | Covers |
|---|---|
| [entities.md](authoring/entities.md) | Define tables with `@Model` / `@Field` / `@Index` / `@OptionSet` / `@OptionItem` |
| [controllers-services.md](authoring/controllers-services.md) | Expose REST CRUD with `EntityController`; business logic with `EntityService` |
| [queries.md](authoring/queries.md) | Read/search with `QueryParams` / `Filters` / `FlexQuery`; submit & return formats |
| [placeholders.md](authoring/placeholders.md) | The `{{ }}` syntax: computed fields, filters, templates |
| [config.md](authoring/config.md) | `application.yml` keys, datasources, profiles |
| [seed-data.md](authoring/seed-data.md) | Ship reference data as JSON seed files |

For feature-specific starters (flow, messaging, files, reference data, AI), see
[feature-starters.md](authoring/feature-starters.md) — it points at each
starter's own reference README.

### How to use this in your app

1. **Copy** the files under [`authoring/`](authoring/) into your repo
   (e.g. `docs/softa/`).
2. **Point your AI at it.** In `CLAUDE.md`, import the ones you need:
   ```
   @docs/softa/entities.md
   @docs/softa/controllers-services.md
   ```
   or in `AGENTS.md` add a line: *"For Softa entities and REST layer, follow
   `docs/softa/entities.md` and `docs/softa/controllers-services.md`."*
3. **Humans** get the same content via IDE hover — the annotation Javadoc ships
   in the SDK's sources jar. This guide is the "how to put it together" companion
   to that per-attribute reference.
4. **On SDK upgrade**, re-copy — these guides are versioned with the SDK they
   came from.

## For framework contributors (working in this repo)

- [`framework/`](framework/) — the framework-contributor overlay, with the
  monorepo-internal detail the `authoring/` guides leave out:
  [annotation-lane.md](framework/annotation-lane.md) (scanner / `sys_*`
  reconciliation, DDL auto-execute policy, the in-repo verification recipe with
  test-class names, and manual migrations). It adds internals **on top of**
  `authoring/` — read the matching `authoring/` guide first, not instead.
- [studio-no-code.md](studio-no-code.md) — the Studio no-code lane (defining
  metadata via Studio's Open API instead of Java). Operator/integration
  audience, not downstream code generation.
