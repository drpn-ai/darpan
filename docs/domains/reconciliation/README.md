# Reconciliation Domain

## Purpose

Production reconciliation flows (file ingest, current Mapping-backed source extraction, RuleSet-backed decisioning, Diff output, persistence, and run tracking).

## Production Entrypoints

- Route: `runtime/component/darpan/screen/Reconciliation.xml`
- Services: `runtime/component/darpan/service/reconciliation/ReconciliationCoreServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationInventoryServices.xml`
- Core scripts: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/reconcileFilesByMapping.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineServices.groovy`

## Operational Notes

- Keep production flows free of sample/debug service calls.
- Current Generic and SFTP flows still use `reconciliationMappingId` as the source/extraction key. The active cutover plan moves object identity and file-side primary ID extraction into RuleSet compare scopes (`ruleSetId` plus `compareScopeId`) and deprecates Mapping only after migration/parity evidence.
- DRL rules should assume the compared object exists in both files; missing-object Diffs are produced by the base compare stage before DRL.
- For pilot/generic reconciliation text payload parameters such as `file1Text` and `file2Text`, use `allow-html="any"` in service XML. Moqui does not treat `allow-html="true"` as enabled input allowance, so literal `<` and `>` content will still be rejected during parameter validation.
- Use `./gradlew :runtime:component:darpan:verifyOrganization --console=plain` to validate boundary rules.
