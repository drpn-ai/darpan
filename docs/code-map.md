# Code Map

This wiki lives in `runtime/component/darpan/docs/Home.md`. The Darpan component code is in `runtime/component/darpan`.

## Production Entrypoints

- Component descriptor: `runtime/component/darpan/component.xml`
- Main app screen: `runtime/component/darpan/screen/darpan.xml`
- Reconciliation route: `runtime/component/darpan/screen/Reconciliation.xml`
- Reconciliation service contracts: `runtime/component/darpan/service/reconciliation/ReconciliationCoreServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationRuleEngineServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationInventoryServices.xml`
- Reconciliation automation contracts: `runtime/component/darpan/service/reconciliation/ReconciliationAutomationServices.xml`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/automation/AutomationExecutionSupport.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/automation/SftpAutomationSupport.groovy`, and `runtime/component/darpan/docs/reconciliation/automation/order-reconciliation-automation.md`
- JSON schema service contracts: `runtime/component/darpan/service/jsonschema/JsonSchemaServices.xml`
- Auth facade contracts: `runtime/component/darpan/service/facade/AuthFacadeServices.xml`, with auth/session helper logic in `runtime/component/darpan/src/main/groovy/darpan/facade/auth/AuthFacadeSupport.groovy`
- Reconciliation scripts: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/reconcileFilesByMapping.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineServices.groovy`
- RuleSet compare-scope cutover plan: `runtime/component/darpan/docs/reconciliation/projects/ruleset-only-cutover/README.md`
- JSON schema scripts/helpers: `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/createJsonSchemaFromJson.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/saveRefinedSchema.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/common/JsonSchemaUtil.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/validation/validateJsonFileAgainstSchema.groovy`
- Production tenant settings runbook: `runtime/component/darpan/docs/reconciliation/platform/production-settings-surfaces.md`
- Tenant-scoped access model: `runtime/component/darpan/docs/reconciliation/platform/company-scoped-access-and-user-preferences.md`
- Platform security and service notes: `runtime/component/darpan/docs/reconciliation/platform/security.md`, `runtime/component/darpan/docs/reconciliation/platform/services.md`
- Automation setup and validation notes: `runtime/component/darpan/docs/reconciliation/automation/order-reconciliation-automation.md`, `runtime/component/darpan/docs/reconciliation/automation/sftp-reconciliation.md`

## Shared Resources

- Entities (reconciliation + rule config): `runtime/component/darpan/entity/ReconciliationEntities.xml`, `runtime/component/darpan/entity/RuleEntities.xml`, `runtime/component/darpan/entity/MappingEntities.xml`
- Setup seed data: generic source files in `runtime/component/darpan/data/` (for example type, security, system-message, job, mapping, and reconciliation seed files) use `darpan-seed-initial` and `darpan-seed`; NetSuite setup data uses `netsuite-seed-initial` and `netsuite-seed`; ordered setup is loaded through `./gradlew loadDarpanData`
- Current upgrade data for customer/self-hosted upgrades: `runtime/component/darpan/data/upgrade-data.xml`, loaded in Docker startup through the component-owned `loadDarpanUpgradeData` Gradle task using `-b runtime/component/darpan/build.gradle`, the `darpan-seed` reader type, and the `component://darpan/data/upgrade-data.xml` location
- Release upgrade records must also be reflected in the appropriate generic source data file; release preflight generates upgrade candidates from generic source data diffs against the previous tag
- Archived release upgrade data: versioned files such as `runtime/component/darpan/data/releases/2.0.0/upgrade-data.xml`; these are generated release artifacts and should not be treated as source seed files when diffing upgrade data
- Theme library: `runtime/component/darpan/theme-library/css/tokens.css`, `runtime/component/darpan/theme-library/css/components.css`, `runtime/component/darpan/theme-library/js/theme-runtime.js`, `runtime/component/darpan/theme-library/html/blocks.html`
- Sample data inputs: `runtime/component/darpan/data/sample/omsData.json`, `runtime/component/darpan/data/sample/shopifyData.json`
- Build config + organization guardrails: `runtime/component/darpan/build.gradle`

## Framework References

- DataDocument entity (used by RunDataDocument) lives in `framework/entity/EntityEntities.xml`.
- SystemMessageRemote entity (used by RunSystemInstance) lives in `framework/entity/ServiceEntities.xml`.
