# Reconciliation Domain

## Purpose

Production reconciliation flows (file ingest, compare, persistence, and run tracking).

## Production Entrypoints

- Route: `runtime/component/darpan/screen/Reconciliation.xml`
- Services: `runtime/component/darpan/service/reconciliation/ReconciliationCoreServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationInventoryServices.xml`
- Core scripts: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/core/reconcileFilesByMapping.groovy`

## Operational Notes

- Keep production flows free of sample/debug service calls.
- For pilot/generic reconciliation text payload parameters such as `file1Text` and `file2Text`, use `allow-html="any"` in service XML. Moqui does not treat `allow-html="true"` as enabled input allowance, so literal `<` and `>` content will still be rejected during parameter validation.
- Use `./gradlew :runtime:component:darpan:verifyOrganization --console=plain` to validate boundary rules.
