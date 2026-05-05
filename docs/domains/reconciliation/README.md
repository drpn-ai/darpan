# Reconciliation Domain

## Purpose

Production reconciliation flows (file ingest, current Mapping-backed source extraction, RuleSet-backed decisioning, Diff output, persistence, and run tracking).

## Production Entrypoints

- Route: `runtime/component/darpan/screen/Reconciliation.xml`
- Services: `runtime/component/darpan/service/reconciliation/ReconciliationCoreServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationRuleEngineServices.xml`
- Core scripts: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/reconcileFilesByMapping.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineServices.groovy`

## Operational Notes

- Keep production flows free of sample/debug service calls.
- Current Generic and SFTP flows still use `reconciliationMappingId` as the source/extraction key. The active cutover plan moves object identity and file-side primary ID extraction into RuleSet compare scopes (`ruleSetId` plus `compareScopeId`) and deprecates Mapping only after migration/parity evidence.
- User-facing RuleSet compare-scope errors and generated-output labels should prefer the compare-scope description over the raw `compareScopeId`. New generic RuleSet compare scopes use neutral `_COMPARE_SCOPE` ids, while CSV-only saved runs keep `_CSV_SCOPE`; generated ids must still stay within the stored Moqui `id` length, trimming the RuleSet portion before persistence when needed.
- DRL rules should assume the compared object exists in both files; missing-object Diffs are produced by the base compare stage before DRL.
- RuleSet CSV compare-scope extraction now validates the configured primary-ID column before Spark projection so file-type or header mismatches fail with a contract error that includes the available columns instead of a raw `UNRESOLVED_COLUMN` stack fragment.
- RuleSet runs with no active rules may collapse duplicate per-side primary IDs into one representative object for missing-object base diffing and emit a processing warning; RuleSet runs with active rules still require each primary ID to map to exactly one object per file side.
- Saved-run diff uploads now also reject obvious payload-shape mismatches before Spark staging. CSV runs block JSON and JSON Schema documents, and JSON runs block non-JSON payloads plus schema-definition uploads when the flow expects record data.
- Saved-run file executions must create a durable `ReconciliationRunResult` manifest with an active status before long-running compare work starts, then update the same row with the generated artifact path and final status. Run-history surfaces depend on this create-before-poll contract.
- For pilot/generic reconciliation text payload parameters such as `file1Text` and `file2Text`, use `allow-html="any"` in service XML. Moqui does not treat `allow-html="true"` as enabled input allowance, so literal `<` and `>` content will still be rejected during parameter validation.
- Use `./gradlew :runtime:component:darpan:verifyOrganization --console=plain` to validate boundary rules.
- NetSuite inventory retrieval is owned by `runtime/component/netsuite-darpan/service/reconciliation/NetSuiteInventoryServices.xml`; the Darpan component no longer exposes an inventory retrieval service surface.
