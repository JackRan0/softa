# Studio Starter

## Overview

Studio Starter is the design-time IDE for Softa metadata. It lets an operator
design per-environment metadata, preview generated Java/DDL output, publish the
desired metadata state to a target runtime, import or reverse-engineer runtime
state back into Studio, merge designs between environments, and restore a prior
activity snapshot.

Current implementation shape:

- Each `DesignAppEnv` owns a full env-scoped design set. The env's live
  `design_*` rows are the desired state; there is no WorkItem, Version, or
  Deployment model in the current code.
- `DesignActivity` is the audit record for operations such as publish, import,
  reverse, and merge. Successful publish/merge activities can carry a
  `DesignSnapshot` for later restore.
- Publishing is desired-state based: Studio diffs env design rows against the
  target connector, renders DDL for structural changes, projects row changes to
  `MetadataChangeSet`, and lets the connector apply the result.
- Connector targets are `SOFTA` (signed runtime upgrade API) and `JDBC` (raw
  database DDL execution and physical schema reverse).

## Template Engine

Studio uses Pebble (`{{ var }}` / `{% if %}`) for generated code files and for
SQL DDL templates resolved through the DDL dialect layer.

### Code Templates

Code generation is handled by `CodeGenerator`.

- Database mode: `DesignCodeTemplate` entries are loaded by `codeLang`, ordered
  by `sequence`, and rendered into a `ModelCodeDTO.files` list.
- `DesignFieldCodeMapping` maps Softa field types to language-specific property
  types.
- Fallback mode is used when no templates are configured for a language. The
  classpath fallback files live under:

```text
starters/studio-starter/src/main/resources/templates/code/
```

Fallback template paths determine output paths. For example,
`templates/code/service/{{modelName}}Service.java.peb` renders to
`service/SysModelService.java`.

### DDL Rendering

DDL rendering is shared with metadata-starter's annotation DDL infrastructure.

- `MetadataChangeDdlRenderer` converts row-level metadata changes to
  `DdlTemplateContext`.
- `ConnectorFactory` selects the `DdlDialect` for the target env through
  `DdlDialectFactory`.
- `SOFTA` connectors use the builtin resolver, matching the boot-time annotation
  scanner.
- `JDBC` connectors adapt `DesignGenerationMetadataResolver` through
  `DesignDdlMetadataResolver`, so `DesignFieldDbMapping` and `DesignSqlTemplate`
  can customize external database DDL without becoming global DDL beans.
- Classpath fallback SQL templates are in metadata-starter, not in
  studio-starter.

Rendered DDL is split into per-statement payloads by `DdlSqlSplitter`, which
delegates statement-boundary parsing to metadata-starter's `SqlStatements` lexer
so semicolons inside comments and quoted SQL literals are safe.

For newly created models, index creation should have a single owner in the
dialect's create-model rendering path. Different databases can implement that
owner differently: MySQL can inline indexes in `CREATE TABLE`, while PostgreSQL
emits separate `CREATE INDEX` statements from the create template.

## Current Runtime Sync Coverage

The desired-state publish, drift, import, and env-to-env merge path currently
sweeps these env-scoped design models:

| Design model | Runtime model |
| --- | --- |
| `DesignModel` | `SysModel` |
| `DesignField` | `SysField` |
| `DesignModelIndex` | `SysModelIndex` |
| `DesignOptionSet` | `SysOptionSet` |
| `DesignOptionItem` | `SysOptionItem` |

The following design models exist, but are not part of the current
desired-state sweep: `DesignModelTrans`, `DesignFieldTrans`,
`DesignOptionSetTrans`, `DesignOptionItemTrans`, `DesignView`, and
`DesignNavigation`. Treat that as an explicit implementation gap until those
models are added to `DesignRows`, `MetaTable`, connector read/apply paths,
checksums, merge, import, and tests.

## Dependencies

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>studio-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Runtime module dependencies:

- `metadata-starter`: runtime metadata entities, DDL dialects, checksums, and
  upgrade DTOs.
- `es-starter`: still declared by the module POM, but the current desired-state
  release flow no longer uses the old ES-backed WorkItem/Version pipeline.

## Environment Configuration

`DesignAppEnv` selects how Studio talks to a target:

- `connectorType = SOFTA` targets a Softa runtime through signed HTTP upgrade
  APIs. `upgradeEndpoint`, `databaseType`, and an issued keypair are required.
- `connectorType = JDBC` targets a raw JDBC database. `jdbcUrl`, credentials,
  and `databaseType` are required. Apply is DDL-only because a raw database has
  no `sys_*` metadata rows.
- `autoExecuteDDL` is honored by the SOFTA connector. When false, Studio still
  publishes metadata row changes but ships no DDL; a DBA runs the recorded DDL
  out of band.

Key setup for a SOFTA env:

1. Call `POST /DesignAppEnv/issueKey?id=<envId>`.
2. Put the returned public key into the target runtime's
   `system.metadata.public-key`.
3. Keep Studio's generated private key only in `DesignAppEnv.privateKey`.

## Core Data Model

### Design Metadata

| Entity | Purpose |
| --- | --- |
| `DesignModel` | Model/table definition, app/env scope, business key, storage flags |
| `DesignField` | Field/column definition and relation metadata |
| `DesignModelIndex` | Model index definition |
| `DesignOptionSet` | Option-set root |
| `DesignOptionItem` | Option-set item |
| `DesignView` | Design-time view definition, not swept by publish yet |
| `DesignNavigation` | Design-time navigation definition, not swept by publish yet |
| `Design*Trans` | Design-time translation rows, not swept by publish yet |

### Generation Metadata

| Entity | Purpose |
| --- | --- |
| `DesignFieldDbMapping` | Field type to database type mapping for design-backed DDL dialects |
| `DesignSqlTemplate` | Database-managed SQL template override per database type |
| `DesignFieldCodeMapping` | Field type to code property type mapping |
| `DesignCodeTemplate` | Database-managed code template |
| `DesignFieldDomain` | Reusable one-time field template applied through `DesignField.applyDomain` |

### Release and Audit

| Entity | Purpose |
| --- | --- |
| `DesignApp` | Application identity, code, package name, and lifecycle status |
| `DesignAppEnv` | Target environment and connector configuration; owns env-scoped design |
| `DesignActivity` | Audit record for publish/import/reverse/merge/cancel/restore related work |
| `DesignSnapshot` | Full env design snapshot captured after successful activities |

## Main Workflows

### Design and Preview

1. Create a `DesignApp`.
2. Create one or more `DesignAppEnv` rows.
3. Seed a new env from an existing env, import from a Softa runtime, or reverse
   a JDBC schema if needed.
4. Edit env-scoped `DesignModel`, `DesignField`, `DesignModelIndex`,
   `DesignOptionSet`, and `DesignOptionItem` rows.
5. Use `DesignModel` preview APIs for generated DDL and code.

### Publish

1. `POST /DesignAppEnv/publish?id=<envId>`.
2. The env mutex transitions from `STABLE` to `DEPLOYING`.
3. Studio computes desired-state changes through checksum-gated diff.
4. Studio renders DDL and row changes.
5. The connector applies the change set:
   - `SOFTA`: signed metadata upgrade API, optionally with DDL.
   - `JDBC`: execute DDL against the external database; row changes are ignored.
6. Studio writes a `DesignActivity` and, on success, a `DesignSnapshot`.
7. The env returns to `STABLE`.

Publish is roll-forward only. Canceling a stuck activity releases the mutex but
does not undo runtime DDL or metadata already applied.

### Drift, Import, and Reverse

- `compareDesignWithRuntime` computes a live operator-facing design-vs-runtime
  drift envelope.
- `previewRuntimeDrift` compares the runtime against the latest successful
  publish snapshot.
- `applyDrift` accepts the current runtime state as the design truth.
- `importFromRuntime` refreshes runtime state first, then applies it to design.
- `seedFromSource` clones a full env design into an empty target env.
- `merge` converges one env's design to another env's design for selected
  aggregate roots or for the whole swept catalog.

For JDBC targets, physical reverse currently reads tables and columns. Index
reverse is still deferred, so incremental JDBC publish to a database that
already has matching indexes may re-emit index DDL.

### Restore

`POST /DesignActivity/restore?id=<activityId>` restores the env design from a
successful activity's snapshot, then publishes that restored design to converge
the runtime. Only activities with a snapshot can be restored.

## Key APIs

### Model Design

| Endpoint | Description |
| --- | --- |
| `GET /DesignModel/previewDDL?id=` | Preview DDL for the current model and its indexes |
| `GET /DesignModel/previewCode?id=&codeLang=` | Preview generated code for one language |
| `GET /DesignModel/previewAllCode?id=` | Preview all configured generated code packages |
| `GET /DesignModel/downloadCode?id=&codeLang=&relativePath=` | Download one generated file |
| `GET /DesignModel/downloadZip?id=&codeLang=` | Download one language package as ZIP |
| `GET /DesignModel/downloadAllZip?id=` | Download all generated language packages |
| `POST /DesignField/applyDomain` | Copy a `DesignFieldDomain` into a field as a one-time template |

### Environment

| Endpoint | Description |
| --- | --- |
| `GET /DesignAppEnv/compareDesignWithRuntime?id=` | Live design-vs-runtime drift envelope |
| `GET /DesignAppEnv/previewRuntimeDrift?id=` | Runtime drift from the last publish snapshot |
| `POST /DesignAppEnv/issueKey?id=` | Issue or rotate the SOFTA connector signing keypair |
| `POST /DesignAppEnv/applyDrift?id=` | Accept runtime state as design truth |
| `POST /DesignAppEnv/importFromRuntime?id=` | Refresh runtime state and import it to design |
| `POST /DesignAppEnv/seedFromSource?id=&sourceId=` | Clone an empty env from another env |
| `POST /DesignAppEnv/publish?id=` | Publish env design to its runtime |
| `POST /DesignAppEnv/merge?id=&sourceId=` | Merge source env design into target env design |

### Activity

| Endpoint | Description |
| --- | --- |
| `POST /DesignActivity/retry?id=` | Retry a failed publish by publishing the same env again |
| `POST /DesignActivity/cancel?id=` | Cancel a stuck running activity and release the env mutex |
| `POST /DesignActivity/restore?id=` | Restore design from the activity snapshot, then publish |
| `GET /DesignActivity/changeReport?id=` | Read the activity's aggregate change report |

### App Status

| Endpoint | Description |
| --- | --- |
| `POST /DesignApp/activate?id=` | Activate an app |
| `POST /DesignApp/enterMaintenance?id=` | Put an app into maintenance mode |
| `POST /DesignApp/deprecate?id=` | Deprecate an app |

## Status Enums

### `DesignAppStatus`

`Active` / `Maintenance` / `Deprecated`

### `DesignAppEnvStatus`

`Stable` / `Deploying` / `Importing` / `Merging`

The env status is the per-env mutex. Publish, import, reverse, and merge acquire
it through conditional update from `Stable` to a busy state and release it back
to `Stable` when finished or canceled.

### `DesignActivityStatus`

`Running` / `Success` / `Failure` / `Canceled`

Studio operations are synchronous in the current implementation. There is no
`Pending` state and no automatic rollback state.

### `DesignActivityKind`

`Publish` / `Import` / `Reverse` / `Merge`

### `ConnectorType`

`Softa` / `JDBC`

### `DesignAppEnvType`

`DEV` / `TEST` / `UAT` / `PROD`

## Current Gaps

- Desired-state sync currently covers only the five swept meta-models listed
  above. Translation rows, views, and navigation are design-time only until the
  sweep model is expanded.
- JDBC reverse does not yet read physical indexes, option sets, comments, or
  non-standard constraints.
- `DesignAppEnv.protectedEnv`, `active`, and some connector policy fields are
  present on the entity but not fully enforced by every operation.
- Runtime restore is implemented as roll-forward publish from a prior design
  snapshot; it is not a database rollback.

## AI agent guidance

Operators integrating Studio via Open API should use
[`docs/ai/studio-no-code.md`](../../docs/ai/studio-no-code.md). Framework
contributors editing Java metadata use
[`docs/ai/framework/annotation-lane.md`](../../docs/ai/framework/annotation-lane.md).
See [`docs/ai/README.md`](../../docs/ai/README.md) for the full prompt layout.
