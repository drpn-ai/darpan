# JSON Schema Domain

## Purpose

JSON schema authoring, refinement, and validation for reconciliation payloads.

## Entrypoints

- Route: `runtime/component/darpan/screen/Reconciliation/JsonSchema/JsonSchemaManager.xml`
- Services: `runtime/component/darpan/service/jsonschema/JsonSchemaServices.xml`
- XML-backed schema CRUD/orchestration: `save#JsonSchema`, `update#JsonSchemaText`
- Facade XML wrapper for darpan-ui text saves: `runtime/component/darpan/service/facade/JsonSchemaFacadeServices.xml`
- Script-backed schema logic that remains procedural: `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/saveRefinedSchema.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/validation/validateJsonFileAgainstSchema.groovy`
- Shared helper: `runtime/component/darpan/src/main/groovy/darpan/jsonschema/common/JsonSchemaUtil.groovy`

## Operational Notes

- Keep schema-editor save and validation flows within `jsonschema.*` service contracts.
- `update#JsonSchemaText` resolves the target schema by `jsonSchemaId`, `schemaName`, or legacy `filename` input so older callers remain compatible during migration.
- Persist both `createdDate` and `lastUpdatedStamp` on `darpan.reconciliation.JsonSchema`; creation flows initialize both values and text/refined saves must refresh `lastUpdatedStamp` so darpan-ui can show the `Updated` card consistently.
- Re-run compile and organization verification after contract or path edits.
