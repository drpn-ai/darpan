# Darpan

Darpan is a Moqui component for multi-tenant data reconciliation between retail systems
(Shopify, NetSuite, HotWax OMS). It compares records from file uploads, API extractions, or SFTP
drops using Spark-based diffing, evaluates tenant-authored rules with a Drools rule engine so
sync issues surface as flagged differences, and notifies tenants on run completion.

## Runtime baseline

- **JDK 21** (Gradle toolchain enforced), **Moqui 4** (upstream master), Gradle 9.
- JTA transaction manager is the Moqui 4 embedded **Bitronix**; `moqui-atomikos` is retired.
- Apache Spark 3.5.1 for reconciliation diffs; Drools 7.73 with MVEL2 forced to 2.5.x for JDK 21.
- Spark needs the canonical `--add-opens` JVM flag set, wired in `build.gradle` (tests),
  `entrypoint.sh` / `prod/entrypoint.sh` in the private `drpn-ai/darpan-docker-config` repo
  (deploys), and the wrapper-root `dev-stack.sh` (local). Do not strip them.

Details: `runtime/component/darpan/docs/runtime-baseline.md`

## What's in this component

- Component code: `runtime/component/darpan/`
- Docs/wiki index: `runtime/component/darpan/docs/Home.md`
- Service contracts: `runtime/component/darpan/service/` — reconciliation core/generic/JSON/mixed
  compare, rule engine, automation (SFTP polling + scheduled runs), run-completion notifications,
  JSON schema tooling, and the `facade.*` JSON-RPC surface used by `darpan-ui` (auth/session,
  settings, reconciliation, JSON schema, navigation search)
- Entities: `runtime/component/darpan/entity/` — reconciliation runs/results/automations,
  rule sets + compare scopes, mappings, JSON schemas, tenant auth/settings
- Processing logic: `runtime/component/darpan/src/main/groovy/darpan/`
- Seed/upgrade data: `runtime/component/darpan/data/`

## Start here

- End-to-end pipeline (ingestion → Spark diff → Drools rules → notification → automation):
  `runtime/component/darpan/docs/reconciliation/reconciliation-flow.md`
- Code map and entry points: `runtime/component/darpan/docs/code-map.md`
- Reconciliation platform docs: `runtime/component/darpan/docs/reconciliation/platform/overview.md`
- Rule engine service contracts: `runtime/component/darpan/docs/reconciliation/rule-engine-services.md`
- JSON schemas: `runtime/component/darpan/docs/reconciliation/json-schema-management.md`
- Build/runtime baseline (JDK 21 / Moqui 4 / Bitronix / Spark flags):
  `runtime/component/darpan/docs/runtime-baseline.md`

## Running tests

All commands are run from `darpan-backend/`.

| Command | Runs | Use it for |
| --- | --- | --- |
| `runtime/component/darpan/scripts/focused-test.sh [base-ref]` | only what your diff can break | the normal inner loop |
| `./gradlew :runtime:component:darpan:unitTest` | the 67 classes that never boot Moqui | quick signal mid-edit |
| `./gradlew :runtime:component:darpan:smokeTest` | the 33 classes that boot Moqui | when you touch services, entities or seed data |
| `./gradlew :runtime:component:darpan:testAll` | the full suite, as the two pools above | before pushing |
| `./gradlew :runtime:component:darpan:test` | the full suite, one JVM per class | unchanged — the fallback if you suspect the split |
| `./gradlew :runtime:component:darpan:mysqlTest` | the `@Tag("mysql")` classes | InnoDB locking behaviour H2 cannot reproduce |

`test` is deliberately untouched: it still runs everything on its own, so no existing command or
CI invocation changes meaning. `testAll` covers the same classes by a faster route.

### What actually costs time

Almost none of it is the tests. The 100 classes sum to roughly **140s of in-JVM execution**; the
rest is **JVM starts**. Under `forkEvery = 1` a worker boots Moqui, runs one class, and dies, so
the suite pays ~100 cold starts of Groovy + Spark + Drools + Moqui classloading.

Measured on a 10-core machine, same 100 classes / 945 tests / 0 skipped in every case:

| Run | Wall clock |
| --- | --- |
| `test`, serial (`-PtestForks=1`, the old default) | 14m 22s |
| `test`, 5 forks | 8m 57s |
| **`testAll`** (pools split, 5 forks) | **3m 13s** / 3m 39s |
| `unitTest` alone | 8s |

That is why the split matters more than the fork width:

- **Which pool.** Only 33 of the 100 classes boot Moqui. The other 67 need no isolation at all
  and were each paying for a JVM they never used. `unitTest` runs them pooled
  (`forkEvery = 0`), and `testAll` runs the full suite as the two pools — same classes as `test`,
  but ~67 fewer JVM starts. The split is derived from source at configuration time (a class
  mentioning `initMoqui(`, `Moqui.dynamicInit` or `ExecutionContextFactoryImpl` is a smoke test),
  so a new test lands in the right pool with no list to maintain, and configuration fails loudly
  if the marker set ever goes stale.
- **Fork width** (`maxParallelForks`, default half the cores, override `-PtestForks=N`). This
  helps less than it looks. Concurrent workers mostly overlap each other's *startup*, not their
  test execution, and that startup CPU slows whichever class is actually running: measured across
  the same 945 tests, in-JVM time rose from 141s serial to 487s at width 5 while average
  concurrency was only 1.1. Widen it for wall clock, but do not expect linear scaling, and use
  `-PtestForks=1` to reproduce the old fully-serial behaviour.

Because contention stretches wall-clock timings, **avoid asserting an upper bound on elapsed
time** in a test. `AuthFacadeSupportTests` guards that a successful login is not padded to the
25ms refusal floor; it samples several sign-ins and asserts on the *fastest*, so a padded path is
still caught while a descheduled sample is not mistaken for one.

`scripts/select_tests.py` maps changed files onto the tests that could break, following
Groovy references, service XML (`location=".../Foo.groovy"` and inline `from="pkg.Foo.method()"`),
entity names, and fixture file names. It is deliberately over-inclusive and escalates to the
full suite for anything it cannot map. Inspect a selection without running it:

```bash
python3 scripts/select_tests.py --base origin/main --explain
python3 scripts/select_tests.py --self-check   # main classes no test reaches
```

Reverse-reference expansion is capped at `--depth 2`. The graph saturates past that — hub
classes like `ReconciliationServices` reach nearly everything, taking a one-class edit from 19
tests at one hop to 72 at five — so the cap is where selection stops being selective. The full
suite before pushing is what covers the tail it gives up.

## Repository boundary

- `darpan-backend` owns Moqui backend contracts, services, entities, and processing logic.
- `darpan-ui` owns custom UI and PWA implementation.
- Do not add or extend custom UI/PWA surfaces in `darpan-backend`; implement them in `darpan-ui`.

## Licensing

See `LICENSE.md`.
