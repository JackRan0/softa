# Application Configuration

Part of the [Softa app authoring guide](../README.md). What to put in your app's
`application.yml` / `application-<profile>.yml` and `pom.xml` to wire up Softa.

**Rule of thumb:** put environment-varying settings in the **profile** file
(`application-dev.yml`, `application-prod.yml`), not the base `application.yml`.
Never inline secrets — use `${ENV_VAR:default}`.

---

## Core system keys

```yaml
system:
  app-code: my-app                 # REQUIRED (metadata-starter fails fast without it); stable app identity
  default-language: en-US          # BCP-47 default language
  enable-multi-tenancy: false      # true for tenant-scoped apps; must match your @Model(multiTenant=true) usage
  enable-insert-id: true           # auto-set id on INSERT
  enable-change-log: true          # audit log (usually off in prod)
  public-access-url: ${PUBLIC_ACCESS_URL:http://localhost}
  metadata:
    scanner-scope:                 # dev/test ONLY — leave empty/unset in prod (see entities.md)
      - "io\\.acme\\.myapp.*"
```

| Key | Default | Purpose |
|---|---|---|
| `system.app-code` | — (required) | Stable app identity; stamped onto managed metadata |
| `system.metadata.scanner-scope` | empty/unset | Regex list of packages the scanner reconciles at boot. Empty = manage nothing (prod-safe). See [entities.md](entities.md) |
| `system.enable-multi-tenancy` | `false` | Enables tenant scope on `multiTenant` tables — keep consistent with your `@Model`s |
| `system.default-language` | `en-US` | Default language for new tenants |
| `system.public-access-url` | env-specific | Base URL for callback links / Open API |

---

## Data sources

Single source:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: ${DB_USER:admin}
    password: ${DB_PASSWORD:admin}          # env var in prod
    driver-class-name: com.mysql.cj.jdbc.Driver
```

Multiple sources — enable dynamic mode, then point specific models at a source
with `@Model(dataSource = "reporting")`:
```yaml
spring:
  datasource:
    dynamic:
      enable: true
      mode: multi-datasource     # or: read-write-separation, switch-by-model, multi-tenancy-isolated
      datasource:
        primary:   { driver-class-name: com.mysql.cj.jdbc.Driver, url: ..., username: ..., password: ... }
        reporting: { driver-class-name: com.mysql.cj.jdbc.Driver, url: ..., username: ..., password: ... }
```
Managed metadata always lives in the **primary** source; only the business table
for a `dataSource`-tagged model goes elsewhere.

## Redis

```yaml
spring:
  data:
    redis:
      root-key: myapp_dev        # namespace prefix — unique per environment
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD:}
```

## Logging

```yaml
logging:
  level:
    root: INFO
    io.acme.myapp: DEBUG         # your packages during development
```

---

## Adding a Softa starter

Add the dependency (keep all Softa starters on the **same version**):
```xml
<dependency>
    <groupId>io.softa</groupId>
    <artifactId>reference-data-starter</artifactId>
    <version>${softa.version}</version>
</dependency>
```
Spring Boot auto-configuration activates the starter — you usually don't need to
add its transitive deps. If a starter ships its own tables, apply its DDL (or let
the scanner create them in dev). Most starters expose an `enabled` toggle
(`softa.<feature>.enabled: false`) to switch off in a profile (e.g. no MinIO in
CI).

---

## Profiles

```bash
# choose the active profile at run time — don't hard-code it in application.yml
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# or
SPRING_PROFILES_ACTIVE=dev java -jar my-app.jar
```
Base `application.yml` loads first, then `application-<profile>.yml` merges on
top. Put a setting in the profile file if it differs per environment.

---

## Common mistakes

1. **Non-empty `scanner-scope` in base `application.yml` or prod** — that makes
   the scanner write schema at boot in every environment. Scope it to dev/test
   profiles only.
2. **Inline secrets** — passwords/keys/tokens get committed. Use
   `${ENV_VAR:default}`; local-only defaults against `localhost` are fine.
3. **`enable-multi-tenancy` out of sync with `@Model(multiTenant=true)`** — if the
   app disables tenancy but a model expects it, queries can cross-leak. Keep them
   aligned.
4. **Version skew across starters** — pinning one starter to a different version
   than the rest fails in subtle ways. Keep them in lockstep.
5. **Adding a starter that needs Redis without configuring Redis** — boot failure.
   Configure the dependency, or disable the starter in that profile.

For CI/CD, Docker, and Kubernetes manifests — that's deployment tooling, outside
this guide.
