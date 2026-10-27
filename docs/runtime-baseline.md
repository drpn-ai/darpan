# Runtime Baseline: JDK 21, Moqui 4, Bitronix, Spark JVM Flags

This is the current build/runtime baseline for the Darpan component. It supersedes the old
JDK 17 + Atomikos notes (archived at `archive/build/java17-compatibility.md`).

## Baseline

| Concern | Current state | Where it is enforced |
| --- | --- | --- |
| JDK | **JDK 21** via Gradle toolchain (`JavaLanguageVersion.of(21)`) | `build.gradle` (component); `dev-stack.sh` at the wrapper root preflights the active `java` and fails fast if it is not 21 |
| Framework | **Moqui 4** from the `drpn-ai/moqui-framework` fork, branch `master` (upstream v4.0.0 + embedded Bitronix + the login self-deadlock fix). `moqui/moqui-runtime` is pinned at `v3.9.9`. | framework checkout in `darpan-backend`; component `.github/workflows/ci.yml`; `MOQUI_FRAMEWORK_REF` / `MOQUI_RUNTIME_REF` in both `darpan-docker-config` Dockerfiles (move all three together) |
| Build | Gradle 9 | framework/root Gradle wrapper |
| JTA transaction manager | **Embedded Bitronix** (`TransactionInternalBitronix`), the Moqui 4 default. `moqui-atomikos` is retired and removed from the build graph and Dockerfiles. | `build.gradle` comment near the dependency block |
| Spark | `org.apache.spark:spark-sql_2.12:3.5.1`, plus `javax.servlet:javax.servlet-api:4.0.1` because Spark 3.5.x still uses the legacy `javax.servlet` API while Moqui 4 ships only `jakarta.servlet` | `build.gradle` |
| Drools / MVEL | **Drools 8.44.0.Final**: `drools-engine`, `drools-mvel` (still required for `dialect "mvel"` constraints), `drools-xml-support` (kmodule.xml) and `kie-api`. MVEL is managed transitively by 8.x, so the old manual MVEL2 2.5.x pin is gone. `drools-mvel`'s legacy evaluators need a classloader workaround around `buildAll()`; see the comment in `build.gradle`. | `build.gradle` |

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

- `build.gradle` — `ext.sparkJava21Opens` applied to every Test task (`test`, `unitTest`, `smokeTest`, `mysqlTest`) through `jvmArgs`
- `entrypoint.sh` and `prod/entrypoint.sh` in the private `drpn-ai/darpan-docker-config` repo — exported through `JAVA_TOOL_OPTIONS` for deployed containers
- wrapper-root `dev-stack.sh` — exports the same set through `JAVA_TOOL_OPTIONS` for local runs

## Local commands

Run from the `darpan-backend` checkout root:

- Focused test: `./gradlew :runtime:component:darpan:test --tests <FQCN>`, or
  `runtime/component/darpan/scripts/focused-test.sh` to run only what a diff can break
- Full suite, split into pools: `./gradlew :runtime:component:darpan:testAll` (= `unitTest`, the
  classes that never boot Moqui, + `smokeTest`, the classes that do, one JVM each)
- Full suite, one JVM per class (what CI runs): `./gradlew :runtime:component:darpan:test`
- MySQL-only locking tests: `./gradlew :runtime:component:darpan:mysqlTest` (needs a local `darpan_test` MySQL)
- API contract: `./gradlew :runtime:component:darpan:generateApiContract` / `checkApiContract`
- Component build: `./gradlew :runtime:component:darpan:build`
- Organization guardrails (manual; not part of `check` or CI): `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
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
| `JWT_KEY` | **Yes** | Signing secret for Moqui JWTs; resolves the `${JWT_KEY}` placeholder in `MoquiProductionConf.xml`. Unset → startup aborts. Set a strong, deployment-unique value. |
| `entity_ds_crypt_pass` | **Yes** | At-rest encryption key for encrypted entity fields (per-tenant credentials/tokens). Unset → startup aborts (no fallback to Moqui's published default key). Set to a deployment-unique secret and treat key rotation carefully. |
| `entity_ds_crypt_pass_old` | No | Previous at-rest key, set only during a key rotation window; defaults to `entity_ds_crypt_pass`. See [crypt-key-rotation](crypt-key-rotation.md). |
| `Moqui_WEBAPP_ALLOW_ORIGINS` | No (image default) | CORS allow-list written into `webapp_allow_origins`. The prod image sets an exact first-party list; the UAT image prepends `*` (temporary, DAR-OPS-001). |
| `DARPAN_APP_BASE_URL` | No | Base URL for run deep links in notifications; falls back to the `darpan.app.baseUrl` property, then the first allowed web origin. |
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
| `darpan.reconciliation.automation.maxConcurrentExecutions` | 8 | Max automation executions **in flight at once** on this instance (DAR-BE-143: was 4). Every execution — scheduled, retried, played, retried from its extracts, a finding's re-check — goes through `RunAdmission`, runs on its own `darpan-run-N` thread (not Moqui's worker pool) and waits, queued, when no slot is free. `scan#DueAutomations` submits only what starts at once: its budget each tick is this cap minus the executions already `AUT_STAT_RUNNING`, and a due automation whose tenant has no free share is deferred to the next 5-minute tick with its schedule untouched (reported as `deferredCount`). Raise it only where the heap and Spark driver can take the extra concurrent jobs. |
| `darpan.reconciliation.automation.maxConcurrentExecutionsPerTenant` | 5 | Max of those executions one tenant runs at once, so one tenant's audit leaves room for another's; waiting tenants take turns. A tenant's audit is bound by its systems' rate limits (HotWax refills per endpoint), not by how many of its checks run together. Capped at `maxConcurrentExecutions`. |
| `darpan.reconciliation.automation.maxWaitingPerTenant` | 50 | Max runs one tenant may keep waiting for a slot (play, retry, re-check); past it a submission is refused in words. |
| `darpan.reconciliation.automation.holdAtHeapPercent` | 85 | While old-generation heap after the last GC is at or above this percent, no new execution starts (waiting ones stay queued; the scan defers). One always may when none is running. |
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
  `secret-scan` gitleaks job. These run on `drpn-ai/darpan`. `ci.yml` clones darpan-hotwax,
  shopify-darpan, netsuite-darpan and database-darpan at `main`, not at release tags, so a green
  run does not prove a tagged set; the prod Dockerfile pins in `darpan-docker-config` are what ship.
- **`darpan-backend/.travis.yml` and any workflows in the moqui-framework checkout are
  UPSTREAM Moqui's** (`origin=moqui/moqui-framework`, push disabled) — they are not
  Darpan CI, do not run for us, and must not be "fixed" in our checkouts.
- darpan-ui has its own CI in its repo (check + coverage thresholds + contract drift +
  npm audit + secret scan).
