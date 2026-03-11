# JSON Schema Domain

## Purpose

JSON schema authoring, refinement, and validation for reconciliation payloads.

## Entrypoints

- Route: `runtime/component/darpan/screen/Reconciliation/JsonSchema/JsonSchemaManager.xml`
- Services: `runtime/component/darpan/service/jsonschema/JsonSchemaServices.xml`
- Scripts: `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/saveRefinedSchema.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/saveJsonSchema.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/validation/validateJsonFileAgainstSchema.groovy`

## Operational Notes

- Keep schema-editor save and validation flows within `jsonschema.*` service contracts.
- Re-run compile and organization verification after contract or path edits.
