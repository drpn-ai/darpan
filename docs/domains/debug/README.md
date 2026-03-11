# Debug Domain

## Purpose

Quarantine non-production sample/test flows behind a dedicated debug namespace and hidden route.

## Debug Entrypoints

- Hidden route: `runtime/component/darpan/screen/Debug.xml`
- Debug test screen: `runtime/component/darpan/screen/Debug/RuleEngineTest.xml`
- Debug services: `runtime/component/darpan/service/debug/ReconciliationDebugServices.xml`
- Debug scripts: `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/compareSampleOrderIds.groovy`, `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/testSampleOrderIdsDb.groovy`, `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/testRuleEngine.groovy`, `runtime/component/darpan/src/main/groovy/darpan/debug/reconciliation/testRuleParser.groovy`

## Operational Notes

- Debug route is intentionally hidden from main navigation via `menu-include="false"` in `runtime/component/darpan/screen/darpan.xml`.
- Production screens/services must not call `debug.*` service namespaces.
