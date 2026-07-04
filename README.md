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

## Repository boundary

- `darpan-backend` owns Moqui backend contracts, services, entities, and processing logic.
- `darpan-ui` owns custom UI and PWA implementation.
- Do not add or extend custom UI/PWA surfaces in `darpan-backend`; implement them in `darpan-ui`.

## Licensing

See `LICENSE.md`.
