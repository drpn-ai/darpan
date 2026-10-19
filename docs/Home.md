Welcome to the darpan wiki!

## Project Guide

- [code-map](code-map.md)
- [runtime-baseline](runtime-baseline.md) — JDK 21 / Moqui 4 / Bitronix / Spark JVM flags
- [theme-library](theme-library.md)
- [repository-boundary](repository-boundary.md)
- [tenancy-model](tenancy-model.md) — tenant scoping, entity filters, `TenantScopedFinder`
- [crypt-key-rotation](crypt-key-rotation.md) — rotating `entity_ds_crypt_pass`
- [ADR 0001: modular monolith boundaries](adr/0001-modular-monolith-boundaries.md)

## API Contract

- [methods.txt](api-contract/methods.txt) — every remotely callable method (generated; do not edit)
- [openapi.json](api-contract/openapi.json) — OpenAPI 3.1 contract (generated; do not edit)
- [api-contract-testing](reconciliation/platform/api-contract-testing.md) — how the contract is generated and gated
- [api-access-policy](reconciliation/platform/api-access-policy.md) — the `/rpc/json` namespace fence, `admin.*`, CORS, public tier

## Domain Boundaries

- [reconciliation domain](domains/reconciliation/README.md)
- [jsonschema domain](domains/jsonschema/README.md)
- [rule-engine domain](domains/rule-engine/README.md)

## Validation

- `./gradlew :runtime:component:darpan:verifyOrganization --console=plain` (checks that backticked
  component paths beginning runtime/component/darpan/ in these docs exist; run it manually, it is
  not in CI)
- `./gradlew :runtime:component:darpan:checkApiContract`
- Test pools and commands: the component `README.md`


## Reconciliation

Pipeline
- [reconciliation-flow](reconciliation/reconciliation-flow.md) — ingestion → Spark diff → Drools rules → notification → automation
- [json-reconciliation](reconciliation/json-reconciliation.md)
- JSON schemas: [jsonschema domain](domains/jsonschema/README.md)

Platform
- [overview](reconciliation/platform/overview.md)
- [security](reconciliation/platform/security.md)
- [permissions-matrix](reconciliation/platform/permissions-matrix.md)
- [tenant-setup](reconciliation/platform/tenant-setup.md)
- [tenant-user-setup](reconciliation/platform/tenant-user-setup.md)
- [services](reconciliation/platform/services.md)
- [facade behaviour notes](reconciliation/platform/facade-behaviour-notes.md)
- [connector-registry](reconciliation/platform/connector-registry.md)
- [production-settings-surfaces](reconciliation/platform/production-settings-surfaces.md)
- [company-scoped-access-and-user-preferences](reconciliation/platform/company-scoped-access-and-user-preferences.md)
- [navigation-search](reconciliation/platform/navigation-search.md)

Automation
- [order-reconciliation-automation](reconciliation/automation/order-reconciliation-automation.md)
- [sftp-reconciliation](reconciliation/automation/sftp-reconciliation.md)

Data model
- [entity-model](reconciliation/data-model/entity-model.md)

Rules
- [rule-engine-services](reconciliation/rule-engine-services.md)

Technology
- [spark](reconciliation/technology/spark.md)
- [drools](reconciliation/technology/drools.md)

## Archive

Superseded plans and finished projects live in [archive/](archive/README.md). They are history, not
current reference:

- [ruleset-only cutover](archive/reconciliation/projects/ruleset-only-cutover/README.md) — the RuleSet
  compare-scope runtime path shipped; the Mapping migration (RSCUT-009) did not land, so
  mapping-backed runs remain supported.
- POC notes: [overview](archive/reconciliation/poc/overview.md), [pseudocode](archive/reconciliation/poc/pseudocode.md)
- Rule engine planning: [rule-engine-plan](archive/reconciliation/rule-engine-plan.md), [rule-engine-execution-roadmap](archive/reconciliation/rule-engine-execution-roadmap.md)
- [java17-compatibility](archive/build/java17-compatibility.md) — superseded by [runtime-baseline](runtime-baseline.md)
- [tenant-scope-finder-plan](archive/tenant-scope-finder-plan.md) — shipped as `TenantScopedFinder`; see [tenancy-model](tenancy-model.md)
- [connector-registry KT](archive/reconciliation/platform/connector-registry-kt.md) — superseded by [connector-registry](reconciliation/platform/connector-registry.md)
- [json-schema-management](archive/reconciliation/json-schema-management.md) — merged into the [jsonschema domain](domains/jsonschema/README.md)
- [ui-mockups](archive/reconciliation/ui-mockups.md), [pwa-ui-aesthetic-guidelines](archive/reconciliation/pwa-ui-aesthetic-guidelines.md) — early backend-screen UI work; UI now lives in `darpan-ui`
