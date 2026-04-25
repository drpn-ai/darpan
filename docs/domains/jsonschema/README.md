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
- Persist both `createdDate` and `lastUpdatedStamp` on `darpan.reconciliation.JsonSchema`; creation flows initialize `createdDate`, and Moqui's built-in entity update stamp owns the single `lastUpdatedStamp` column that text/refined saves refresh so darpan-ui can show the `Updated` card consistently.
- Example: `save#JsonSchemaText` creating `schemaName=OrderPayload` writes `createdDate` from the service input map while Moqui supplies one `lastUpdatedStamp` field on the resulting `JSON_SCHEMA` insert.
- Schema create paths should default `description` to the requested visible label so duplicate names do not surface as internal ` (1)` dedupe suffixes in product-facing schema cards.
- For raw JSON payload parameters and schema text outputs, use `allow-html="any"` in service XML. Moqui does not treat `allow-html="true"` as enabled input allowance, so uploaded sample values containing literal `<` or `>` will still be rejected during parameter validation.
- Re-run compile and organization verification after contract or path edits.
