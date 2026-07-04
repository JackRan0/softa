# ES Starter

Elasticsearch-backed **change-log** persistence and search. The framework emits
a change event for every business-data mutation (when `system.enable-change-log`
is on); this starter consumes those events from Pulsar, indexes them into
Elasticsearch, and exposes a query API for the resulting audit trail. It also
provides a small reusable `ESService<T>` query abstraction.

> Scope note: this is a change-log / audit-search module, **not** a general
> CRUD layer for arbitrary entities — it has no automatic indexing of business
> models. `ESService<T>` is a query contract for ES-backed data; the concrete
> implementation shipped here is for change logs.

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>es-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Depends on `softa-web` and `spring-boot-starter-data-elasticsearch`.

## How it works

```
business mutation ──▶ change-log event ──▶ Pulsar topic ──▶ ChangeLogPersistConsumer ──▶ Elasticsearch index
   (softa-orm,                             (mq.topics.        (bulk-index as              (spring.elasticsearch
    enable-change-log)                      change-log)        ChangeLogDocument)          .index.changelog)
```

- `ChangeLogPersistConsumer` — Pulsar listener; registered **only** when
  `mq.topics.change-log.topic` is configured (`@ConditionalOnProperty`). It
  converts each event to a `ChangeLogDocument` and bulk-indexes it.
- `ChangeLogDocument` — the stored shape (map payloads flattened to JSON strings
  for deterministic serialization).

## Query API

`ChangeLogController`:

| Endpoint | Purpose |
|---|---|
| `GET /ChangeLog/getChangeLog` | Change history for one row (`modelName` + `id`, paged) |
| `GET /ChangeLog/getSliceChangeLog` | History for a timeline-model slice |
| `POST /ChangeLog/searchPageByModel` | Filtered search within a model (`QueryParams` body) |
| `POST /ChangeLog/searchPage` | Cross-model search (`QueryParams` body) |

Admin-scoped endpoints require the system admin role; results are permission-
checked per user and field references are resolved for display.

`ChangeLogService extends ESService<ChangeLog>` adds `searchByCorrelationIds(...)`
(aggregate versions by correlation id). `ESService<T>` /`ESServiceImpl<T>` provide
`searchPage(Filters, Orders, Page)` with criteria mapping (EQUAL, NOT_EQUAL,
GREATER_THAN, CONTAINS, IN, BETWEEN, PARENT_OF, CHILD_OF, …).

## Configuration

```yaml
system:
  enable-change-log: true              # framework emits change events (required to produce data)

spring:
  elasticsearch:
    uris: http://localhost:9200
    username: <user>
    password: <pass>
    index:
      changelog: demo_change_log        # target ES index (required by the consumer)

mq:
  topics:
    change-log:
      topic: demo_change_log            # Pulsar topic the events are published to
      persist-sub: demo_change_log_persist_sub   # subscription for the consumer
```

All are mandatory when using the module (no defaults).

## Infrastructure

Needs a reachable **Elasticsearch** instance, and a **Pulsar** broker for the
change-log pipeline. The demo stack (`deploy/demo-app/docker-compose.yml`)
provides both (plus Kibana). Business data still lives in MySQL/PostgreSQL; only
the change-log audit trail is indexed into ES.
