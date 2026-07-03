Welcome to the darpan wiki!

## Project Guide

- [code-map](code-map.md)
- [runtime-baseline](runtime-baseline.md) — JDK 21 / Moqui 4 / Bitronix / Spark JVM flags
- [theme-library](theme-library.md)
- [repository-boundary](repository-boundary.md)

## Domain Boundaries

- [reconciliation domain](domains/reconciliation/README.md)
- [jsonschema domain](domains/jsonschema/README.md)
- [rule-engine domain](domains/rule-engine/README.md)

## Validation

- `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`


## Reconciliation

Pipeline
- [reconciliation-flow](reconciliation/reconciliation-flow.md) — ingestion → Spark diff → Drools rules → notification → automation
- [json-reconciliation](reconciliation/json-reconciliation.md)
- [json-schema-management](reconciliation/json-schema-management.md)

Platform
- [overview](reconciliation/platform/overview.md)
- [security](reconciliation/platform/security.md)
- [permissions-matrix](reconciliation/platform/permissions-matrix.md)
- [tenant-setup](reconciliation/platform/tenant-setup.md)
- [tenant-user-setup](reconciliation/platform/tenant-user-setup.md)
- [services](reconciliation/platform/services.md)
- [facade services](reconciliation/platform/facade-wave1-services.md)
- [production-settings-surfaces](reconciliation/platform/production-settings-surfaces.md)
- [company-scoped-access-and-user-preferences](reconciliation/platform/company-scoped-access-and-user-preferences.md)
- [navigation-search](reconciliation/platform/navigation-search.md)
- [ui-mockups](reconciliation/ui-mockups.md)
- [pwa-ui-aesthetic-guidelines](reconciliation/pwa-ui-aesthetic-guidelines.md)

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

## Project Archive

- [ruleset-only cutover](reconciliation/projects/ruleset-only-cutover/README.md) — RuleSet
  compare-scope runtime path is shipped (`create#RuleSetRun`, `reconcile#RuleSetCompareScope`);
  the final Mapping migration/deprecation ticket (RSCUT-009) has not landed, so mapping-backed
  runs remain supported. See the status note in the project README.
- POC notes: [overview](reconciliation/poc/overview.md), [sample-data](reconciliation/poc/sample-data.md), [pseudocode](reconciliation/poc/pseudocode.md)
- Rule engine planning: [rule-engine-plan](reconciliation/rule-engine-plan.md), [rule-engine-execution-roadmap](reconciliation/rule-engine-execution-roadmap.md)
- [java17-compatibility](build/java17-compatibility.md) — superseded by [runtime-baseline](runtime-baseline.md)
