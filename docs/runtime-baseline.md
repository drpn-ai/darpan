# Runtime Baseline: JDK 21, Moqui 4, Bitronix, Spark JVM Flags

This is the current build/runtime baseline for the Darpan component. It supersedes the old
JDK 17 + Atomikos notes (`build/java17-compatibility.md`).

## Baseline

| Concern | Current state | Where it is enforced |
| --- | --- | --- |
| JDK | **JDK 21** via Gradle toolchain (`JavaLanguageVersion.of(21)`) | `build.gradle` (component); `dev-stack.sh` at the wrapper root preflights the active `java` and fails fast if it is not 21 |
| Framework | **Moqui 4** (upstream `moqui/moqui-framework` master) | framework checkout in `darpan-backend` |
| Build | Gradle 9 | framework/root Gradle wrapper |
| JTA transaction manager | **Embedded Bitronix** (`TransactionInternalBitronix`), the Moqui 4 default. `moqui-atomikos` is retired and removed from the build graph and Dockerfiles. | `build.gradle` comment near the dependency block |
| Spark | `org.apache.spark:spark-sql_2.12:3.5.1`, plus `javax.servlet:javax.servlet-api:4.0.1` because Spark 3.5.x still uses the legacy `javax.servlet` API while Moqui 4 ships only `jakarta.servlet` | `build.gradle` |
| Drools / MVEL | Drools 7.73.0.Final (`drools-mvel`, `drools-compiler`, `kie-api`) with **MVEL2 forced to 2.5.2.Final**. Drools 7.73 pulls MVEL2 2.4.x, which references `java.lang.Compiler` (removed in JDK 21) and fails to initialize `MVELDialectConfiguration`. | `build.gradle` |

## Required JVM flags

Spark 3.5.x on JDK 17+/21 needs the canonical `--add-opens` module openings (Spark's own
`JavaModuleOptions` list); without them Spark SQL throws `IllegalAccessError` on
`sun.nio.ch.DirectBuffer` at `SparkContext` init:

```text
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
--add-opens=java.base/java.io=ALL-UNNAMED
--add-opens=java.base/java.net=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--add-opens=java.base/sun.nio.cs=ALL-UNNAMED
--add-opens=java.base/sun.security.action=ALL-UNNAMED
--add-opens=java.base/sun.util.calendar=ALL-UNNAMED
--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED
```

Two more flags quiet known JDK 21 warnings:

- `-XX:+EnableDynamicAgentLoading` — suppresses the ByteBuddy/Mockito dynamic-agent warning
- `--enable-native-access=ALL-UNNAMED` — quiets Netty/OpenSearch native-access warnings

## Where the flags are wired (do not strip them)

- `build.gradle` — `ext.sparkJava21Opens` applied to the `test` task (`jvmArgs`)
- `entrypoint.sh` and `prod/entrypoint.sh` in the private `drpn-ai/darpan-docker-config` repo — exported through `JAVA_TOOL_OPTIONS` for deployed containers
- wrapper-root `dev-stack.sh` — exports the same set through `JAVA_TOOL_OPTIONS` for local runs

## Local commands

Run from the `darpan-backend` checkout root:

- Focused test: `./gradlew :runtime:component:darpan:test --tests <FQCN>`
- Full component test suite: `./gradlew :runtime:component:darpan:test`
- Component build: `./gradlew :runtime:component:darpan:build`
- Organization guardrails: `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
- Seed/demo data load: `./gradlew load` (standard Moqui task at the framework root)
- Upgrade data (Docker startup and self-hosted upgrades): `loadDarpanUpgradeData`, defined in the
  component `build.gradle` and invoked as `./gradlew :runtime:component:darpan:loadDarpanUpgradeData`
  (Gradle 9 removed the former `-b <build-file>` invocation)

There is no `loadDarpanData` Gradle task; ordered setup data loads through the standard Moqui
`load` task using the component's data-file reader types (`darpan-seed-initial`, `darpan-seed`,
`netsuite-seed-initial`, `netsuite-seed`).

## Production environment variables (Docker)

`entrypoint.sh` and `prod/entrypoint.sh` (in `drpn-ai/darpan-docker-config`) fail fast if any
**required** variable is unset, rather than silently keeping an insecure default.

| Variable | Required | Purpose |
| --- | --- | --- |
| `Moqui_DB_HOST`, `Moqui_DB_USER`, `Moqui_DB_PASSWORD`, `Moqui_DB_NAME` | **Yes** | DB connection. Unset → startup aborts (no `moqui/moqui` fallback). |
| `entity_ds_crypt_pass` | **Yes** | At-rest encryption key for encrypted entity fields (per-tenant credentials/tokens). Unset → startup aborts (no fallback to Moqui's published default key). Set to a deployment-unique secret and treat key rotation carefully. |
| `entity_ds_use_ssl` | No (default `true`) | MySQL TLS. Defaults to on so credentials and tenant payloads are not sent in cleartext; set `false` only for an environment that genuinely cannot terminate TLS. |
| `entity_ds_allow_pubkey` | No (default `false`) | `allowPublicKeyRetrieval`. Off by default; only needed for `caching_sha2_password` over a non-TLS link. |
| `REQUIRE_PINNED_BASE` (Docker **build arg**) | No | Set `=1` in CI/prod builds to fail the build unless `BASE_IMAGE` is digest-pinned (contains `@sha256:`, e.g. `…/maarg-base-os@sha256:<digest>`). A digest needs the `@` separator — a `:sha256:…` tag form is an invalid reference — so `BASE_IMAGE` is a full image reference. |

## Tunable JVM system properties

All optional; defaults are sized for typical single-node embedded use. Pass via `-D` in
`JAVA_TOOL_OPTIONS`/`JAVA_OPTS`.

| Property | Default | Effect |
| --- | --- | --- |
| `darpan.reconciliation.rule.cacheMax` | 200 | Max compiled rulesets (Drools `KieContainer`s) held in the bounded LRU before eviction+dispose. |
| `darpan.reconciliation.rule.maxRuleDiffRows` | 1000000 | Cap on rule-generated diff rows accumulated on the driver per run; beyond it rows are dropped (counted, with a loud truncation warning in the completion alert) so a catastrophic sync cannot OOM the JVM. |
| `darpan.reconciliation.automation.maxConcurrentExecutions` | 4 | Max automation executions **in flight at once** (bounds shared service-pool and Spark-driver load). `scan#DueAutomations` submits fire-and-forget, so its budget each tick is this cap minus the executions already `AUT_STAT_RUNNING`; due automations past the budget are deferred to the next 5-minute tick with their schedule untouched (reported as `deferredCount`). Raise it only where the Spark driver can take the extra concurrent jobs — Moqui's own worker pool is bounded far higher (core 16 / max 32, or `cores × 3`), so it is not the effective limit. |
| `darpan.reconciliation.spark.shufflePartitions` | 16 | `spark.sql.shuffle.partitions` for the embedded `local[*]` batches (the 200 default is wasteful for the small datasets here). |
| `darpan.reconciliation.spark.useSavedReadSchema` | `false` | **Opt-in.** When `true`, JSON ingest supplies the saved JSON Schema as the Spark read schema, skipping the per-run inference scan. Enable **only** for sources whose saved schema is authoritative and current: a provided schema reads only declared columns, so a field that appears in newer data but is missing from the schema is silently omitted from the per-record `data` payload, and a type that disagrees with the data is coerced. If the saved schema does not resolve the id/rule field paths, ingest transparently falls back to inference (logged) — so a stale schema is "slower but correct", never a failed run. Default (unset) keeps full per-run inference. |

## Compare-ID normalizers

A reconciliation source's primary-ID expression may carry an optional normalizer with the
`<idExpr>|<NORMALIZER>` syntax. Supported normalizers:

- `SHOPIFY_GID_TAIL` — extract the trailing numeric id from a Shopify GID (`gid://shopify/Order/123` → `123`).
- `CASE_FOLD` (aliases `CASE_INSENSITIVE`, `LOWER`) — lower-case the key before matching, so cross-system IDs that differ only by letter case do not surface as false `missing` differences. Opt-in per side; existing configs are unaffected.

## CI ownership (which pipelines are ours)

- **This component's CI** is `.github/workflows/ci.yml` (test suite + coverage floors +
  contract drift/compat gates + docs-catalog check) and `security.yml`, plus the
  `secret-scan` gitleaks job. These run on `drpn-ai/darpan`.
- **`darpan-backend/.travis.yml` and any workflows in the moqui-framework checkout are
  UPSTREAM Moqui's** (`origin=moqui/moqui-framework`, push disabled) — they are not
  Darpan CI, do not run for us, and must not be "fixed" in our checkouts.
- darpan-ui has its own CI in its repo (check + coverage thresholds + contract drift +
  npm audit + secret scan).
