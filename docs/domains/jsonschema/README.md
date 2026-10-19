# JSON Schema Domain

## Purpose

JSON schema authoring, refinement, and validation for reconciliation payloads.

## Model

- The database is the source of truth: one tenant-owned `darpan.reconciliation.JsonSchema` row per
  schema, keyed for users by a human-readable `schemaName`. Saves also mirror the text to
  `runtime://datamanager/schemas/json/{schemaName}.json`; the legacy `runtime://schemas/json/**`
  location is read only as a last fallback.
- `darpan-ui` uses the facade (`facade.JsonSchemaFacadeServices`: list/get, text save, inference
  from JSON or CSV sample text, refined save from a field list, flatten, validate text against a
  schema, delete). Every call is scoped to the active tenant. The method list is
  `docs/api-contract/methods.txt`.
- The `jsonschema.JsonSchemaServices` layer underneath holds file-based create/infer/validate,
  `resolve#JsonPathForKey` (finds a JSONPath such as `$.data.orders[*].id` from a bare key), and
  delete/flatten. Inference samples large inputs rather than reading them whole.
- Saved schemas drive compare-source setup: a JSON compare source names a saved schema plus a
  schema-backed ID field path.

## Entrypoints

- Legacy backend screen (super-admin only, not extended): `runtime/component/darpan/screen/Reconciliation/JsonSchema/JsonSchemaManager.xml`
- Services: `runtime/component/darpan/service/jsonschema/JsonSchemaServices.xml`
- XML-backed schema CRUD/orchestration: `save#JsonSchema`
- Facade XML wrapper for darpan-ui text saves: `runtime/component/darpan/service/facade/JsonSchemaFacadeServices.xml`
- Script-backed schema logic that remains procedural: `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/crud/saveRefinedSchema.groovy`, `runtime/component/darpan/src/main/groovy/darpan/jsonschema/service/validation/validateJsonFileAgainstSchema.groovy`
- Shared helper: `runtime/component/darpan/src/main/groovy/darpan/jsonschema/common/JsonSchemaUtil.groovy`

## Operational Notes

- Keep schema-editor save and validation flows within `jsonschema.*` service contracts.
- Persist both `createdDate` and `lastUpdatedStamp` on `darpan.reconciliation.JsonSchema`; creation flows initialize `createdDate`, and Moqui's built-in entity update stamp owns the single `lastUpdatedStamp` column that text/refined saves refresh so darpan-ui can show the `Updated` card consistently.
- Example: `save#JsonSchemaText` creating `schemaName=OrderPayload` writes `createdDate` from the service input map while Moqui supplies one `lastUpdatedStamp` field on the resulting `JSON_SCHEMA` insert.
- Schema text remains the database source of truth and is also mirrored to `runtime://datamanager/schemas/json/{schemaName}.json` whenever schema text is saved, uploaded, inferred from JSON, or refined from field rows. Schema loading checks this data-manager mirror before the legacy `runtime://schemas/json/**` fallback.
- `facade.JsonSchemaFacadeServices.list#JsonSchemas` must only return active schemas owned by the current `activeTenantUserGroupId`, matching the accessibility rule enforced by `get#JsonSchema`.
- For raw JSON payload parameters and schema text outputs, use `allow-html="any"` in service XML. Moqui does not treat `allow-html="true"` as enabled input allowance, so uploaded sample values containing literal `<` or `>` will still be rejected during parameter validation.
- Re-run compile and organization verification after contract or path edits.

## Groovy Helper Retention

`JsonSchemaUtil.groovy` is limited to helpers that are shared by XML services and procedural schema scripts, or that need Java/Groovy APIs that do not translate cleanly to declarative Moqui XML actions.

| Helper | XML conversion decision |
| --- | --- |
| `cleanFileName` | Retain in Groovy; it is a path traversal guard used inside schema resource loading before resolving fallback locations. |
| `loadSchemaText` | Retain in Groovy; it combines DB lookup, data-manager fallback, legacy data-manager fallback, and legacy runtime resource fallback. |
| `persistSchemaText` | Retain in Groovy; it delegates data-manager path resolution and text writes through `DataManagerSupport`. |
| `loadSchemaTextFromLocation` | Retain in Groovy; it encapsulates `ResourceReference` existence and text-read behavior for file-like Moqui locations. |
| `resolveFilePath` | Retain in Groovy; validation scripts need URL/URI-to-file path normalization before streaming local JSON files. |
| `ensureJsonSchemaTable` | Retain in Groovy; both XML services and scripts need the same datasource table guard before querying `JsonSchema`. |
| `findSystemEnum` | Retain in Groovy; the enum lookup is shared by XML facade validation and procedural refined-schema save logic. |
| `resolveSystemLabel` | Retain in Groovy; it combines enum lookup with `FacadeSupport.enumLabel` normalization used by facade/search/mapping responses. |
| `deleteSchemaRecord` | Retain in Groovy; XML `<entity-delete>` can delete the value, but this helper preserves message-based failure handling instead of surfacing a raw delete exception. |
| `validateJsonText` | Retain in Groovy; it uses Jackson parsing and returns stable field-specific validation text for XML service actions. |
| `readUploadedText` | Retain in Groovy; it handles `FileItem` size checks and UTF-8 reads before XML service orchestration saves the schema. |
| `resolveSchemaRecord` | Retain in Groovy; it is a shared compatibility lookup by `jsonSchemaId`, `schemaName`, or legacy `filename`. |
| `generateUniqueSchemaName` | Retain in Groovy; the collision loop is shared by XML upload/text saves and procedural generated/refined schema saves. |

Unused helper surface should be deleted rather than justified. `loadSchemaNode` was removed because current callers load schema text and parse it with the local `ObjectMapper` they already use for validation or flattening.
