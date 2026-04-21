# Code Map

This wiki lives in `runtime/component/darpan/docs/Home.md`. The Darpan component code is in `runtime/component/darpan`.

## Production Entrypoints

- Component descriptor: `runtime/component/darpan/component.xml`
- Main app screen: `runtime/component/darpan/screen/darpan.xml`
- Reconciliation route: `runtime/component/darpan/screen/Reconciliation.xml`
- Reconciliation service contracts: `runtime/component/darpan/service/reconciliation/ReconciliationCoreServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationRuleEngineServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationInventoryServices.xml`
- JSON schema service contracts: `runtime/component/darpan/service/jsonschema/JsonSchemaServices.xml`
- Reconciliation scripts: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/reconcileFilesByMapping.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineServices.groovy`
- RuleSet compare-scope cutover plan: `runtime/component/darpan/docs/reconciliation/projects/ruleset-only-cutover/README.md`
- JSON schema scripts/helpers: `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/createJsonSchemaFromJson.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/saveRefinedSchema.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/common/JsonSchemaUtil.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/validation/validateJsonFileAgainstSchema.groovy`

## Debug Entrypoints

- Hidden debug route: `runtime/component/darpan/screen/Debug.xml` (wired under `runtime/component/darpan/screen/darpan.xml` with `menu-include="false"`)
- Debug test screen: `runtime/component/darpan/screen/Debug/RuleEngineTest.xml`
- Debug-only service contracts: `runtime/component/darpan/service/debug/ReconciliationDebugServices.xml`
- Debug sample scripts: `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/compareSampleOrderIds.groovy`, `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/testSampleOrderIdsDb.groovy`, `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/testRuleEngine.groovy`, `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/testRuleParser.groovy`

## Shared Resources

- Entities (reconciliation + rule config): `runtime/component/darpan/entity/ReconciliationEntities.xml`, `runtime/component/darpan/entity/RuleEntities.xml`, `runtime/component/darpan/entity/MappingEntities.xml`
- Setup seed data: `runtime/component/darpan/data/` (for example `runtime/component/darpan/data/DarpanSystemSourceSeedData.xml` and `runtime/component/darpan/data/MappingSeedData.xml`) uses `darpan-seed-initial` and `darpan-seed`; component-level setup data uses separate `darpan-<component>-seed(-initial)` reader types and is loaded through `./gradlew loadDarpanSetup`
- Release upgrade packs for customer/self-hosted upgrades: `runtime/component/darpan/data/releases/`; these are generated release artifacts and should not be treated as source seed files when diffing upgrade data
- Theme library: `runtime/component/darpan/theme-library/css/tokens.css`, `runtime/component/darpan/theme-library/css/components.css`, `runtime/component/darpan/theme-library/js/theme-runtime.js`, `runtime/component/darpan/theme-library/html/blocks.html`
- Sample data inputs: `runtime/component/darpan/data/sample/omsData.json`, `runtime/component/darpan/data/sample/shopifyData.json`
- Build config + organization guardrails: `runtime/component/darpan/build.gradle`

## Framework References

- DataDocument entity (used by RunDataDocument) lives in `framework/entity/EntityEntities.xml`.
- SystemMessageRemote entity (used by RunSystemInstance) lives in `framework/entity/ServiceEntities.xml`.
