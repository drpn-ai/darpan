# JSON Reconciliation

## Overview

The JSON reconciliation feature allows you to compare two JSON files using ID-based comparison and identify differences. The reconciliation validates both files against a JSON schema and outputs a detailed report with full object data preserved.

## Key Features

- **Schema Validation**: Validates both JSON files against a JSON Schema before reconciliation
- **ID-based Comparison**: Uses JSONPath expressions to extract identifiers from complex nested structures
- **Full Object Preservation**: Returns complete JSON objects for missing/mismatched data
- **Detailed Output**: JSON format with metadata, summary statistics, and full difference details

## Services

### `ReconciliationGenericServices.reconcile#GenericFiles`

Primary entry point for uploads. It stages files, then delegates to the RuleSet compare-scope pipeline when `ruleSetId` is provided. During migration it can still fall back to the mapping bridge when only `reconciliationMappingId` is provided.

**Input Parameters:**
- `file1` (FileItem, required): First file (CSV or JSON)
- `file2` (FileItem, required): Second file (CSV or JSON)
- `ruleSetId` (preferred): RuleSet to run through the compare-scope + DRL path
- `compareScopeId` (optional): Compare-scope override; required only when a RuleSet has multiple scopes
- `file1SystemEnumId` / `file2SystemEnumId` (optional in RuleSet mode, required in mapping mode): source-system identifiers
- `reconciliationMappingId` (migration bridge): legacy mapping input retained temporarily for old callers
- `sparkMaster` (optional): Spark master URL (default: "local[*]")

Additional pilot-compatible input mode:

- `file1Name` + `file1Text`
- `file2Name` + `file2Text`

These optional parameters allow a remote facade to pass UTF-8 file payloads from `darpan-ui` over JSON-RPC when browser multipart `FileItem` upload is not available.

**Output:**
- `reconciliationType`: "CSV", "JSON", or "MIXED"
- `differenceCount`: Total differences found
- `diffLocation` / `diffFileName`: Path to the generated results
- `validationErrors`: Any schema validation errors found
- `processingWarnings`: Any normalization fallbacks that were applied

Behavior notes:
- RuleSet mode writes one generated JSON artifact that contains both missing-object rows and rule-generated rows.
- In RuleSet mode, `onlyInFile1Count` maps to IDs present only in file 1 (`missingInFile2Count` from the compare-scope service), and `onlyInFile2Count` maps to IDs present only in file 2 (`missingInFile1Count`).
- If the RuleSet defines exactly one compare scope, Generic routing resolves it automatically. When a RuleSet has multiple scopes, `compareScopeId` is required.
- The mapping route remains available only as a migration bridge until the later cutover tickets remove it.

Facade/PWA notes:
- `facade.ReconciliationFacadeServices.list#PilotRuleSetCompareScopes` lists the RuleSet compare-scope rows that drive the Generic PWA selector.
- `facade.ReconciliationFacadeServices.run#PilotGenericDiff` now accepts either `ruleSetId` plus optional `compareScopeId` or legacy `reconciliationMappingId`.
- `facade.ReconciliationFacadeServices.list#PilotGeneratedOutputs` now filters by `ruleSetId` and `compareScopeId` as well as `reconciliationMappingId`, so the RuleSet workflow can load saved history and latest-result cards without mapping IDs.

### `ReconciliationAutomationServices.poll#SftpAndReconcile`

SFTP automation stages remote files and now routes through the same RuleSet compare-scope pipeline as Generic reconciliation when `ruleSetId` is provided. Older automation jobs can continue to use `reconciliationMappingId` temporarily until they are migrated.

**Inputs (key):** `ruleSetId`, optional `compareScopeId`, temporary `reconciliationMappingId`, `file1SftpServerId`, `file2SftpServerId`, optional `file1SystemEnumId`/`file2SystemEnumId`, optional file-type/schema overrides, `file1RemotePath`, `file2RemotePath`, `stageLocation`, `outputLocation`, `sparkMaster`, `sparkAppName`.

**Outputs (key):** `dataAvailable`, `file1StagedLocation`, `file2StagedLocation`, `reconciliationType`, `diffLocation`, `diffFileName`, `differenceCount`, `onlyInFile1Count`, `onlyInFile2Count`, `validationErrors`, `processingWarnings`.

### `ReconciliationCoreServices.reconcile#FilesByMapping`

Shared mapping bridge used by older Generic uploads and older SFTP automation jobs. It normalizes staged file locations, resolves mapping metadata (types, schemas, ID fields), and hands everything to the unified comparator.

**Inputs (key):** `reconciliationMappingId`, `file1Location`, `file2Location`, `file1SystemEnumId`, `file2SystemEnumId`, optional `file1Name`/`file2Name`, `file1FileTypeEnumId`/`file2FileTypeEnumId`, `file1SchemaFileName`/`file2SchemaFileName`, `hasHeader`, `outputLocation`, `sparkMaster`, `sparkAppName`.

**Outputs (key):** `file1Type`, `file2Type`, `reconciliationType`, `diffLocation`, `diffFileName`, `differenceCount`, `onlyInFile1Count`, `onlyInFile2Count`, `validationErrors`, `processingWarnings`.

This service remains the Generic/SFTP migration bridge. New RuleSet-backed Generic and SFTP runs should use `reconcile#RuleSetCompareScope` instead.

### `ReconciliationCoreServices.prepare#RuleSetCompareScope`

Internal compare-scope extraction adapter introduced for the RuleSet cutover. It resolves `RuleSetCompareScope` plus `RuleSetCompareSource`, derives file-side config, and returns normalized Spark datasets for later missing-object and matched-pair stages.

**Inputs (key):** `ruleSetId`, `compareScopeId`, `file1Location`, `file2Location`, optional `file1Name`/`file2Name`, optional `file1FileTypeEnumId`/`file2FileTypeEnumId`, optional `file1SchemaFileName`/`file2SchemaFileName`, `hasHeader`, `sparkMaster`, `sparkAppName`.

**Outputs (key):** `objectType`, `file1Type`, `file2Type`, `file1SystemEnumId`, `file2SystemEnumId`, `file1IdExpression`, `file2IdExpression`, `file1IdDf`, `file2IdDf`, `file1DataDf`, `file2DataDf`, `validationErrors`, `processingWarnings`.

Generic uploads and SFTP automation now route here when `ruleSetId` is provided. `reconcile#FilesByMapping` remains only for the temporary legacy bridge path.

### `ReconciliationCoreServices.reconcile#RuleSetCompareScopeBaseDiff`

Internal base compare stage for the RuleSet cutover. It calls `prepare#RuleSetCompareScope`, runs the primary-ID anti-joins, emits missing-object Diff rows, and returns matched object pairs for later DRL execution.

**Inputs (key):** same as `prepare#RuleSetCompareScope`.

**Outputs (key):** `missingInFile1Count`, `missingInFile2Count`, `differenceCount`, `matchedPairCount`, `missingDiffDf`, `matchedPairDf`, `validationErrors`, `processingWarnings`.

Missing-object Diff direction is explicit:
- `MISSING_IN_FILE_1`: primary ID exists in file 2 but not file 1
- `MISSING_IN_FILE_2`: primary ID exists in file 1 but not file 2

The returned `matchedPairDf` keeps one row per matched primary ID with:
- `compareScopeId`
- `objectType`
- `primaryId`
- `file1`
- `file2`

Duplicate `primaryId` values on either file side are invalid for the compare-scope contract. The RuleSet compare-scope services reject those inputs before missing-object or matched-pair output is returned.

When no objects are missing on either side, `missingDiffDf` is still returned as an empty Dataset with the normal Diff-row schema. Callers should not need to special-case `null` for the no-difference path.

### `ReconciliationCoreServices.reconcile#RuleSetCompareScope`

Internal full RuleSet compare pipeline for the cutover. It preserves the base missing-object Diff stage, executes DRL only on matched pairs, and returns one normalized diff dataset for both missing-object and field/business-rule outcomes.

**Inputs (key):** same as `reconcile#RuleSetCompareScopeBaseDiff`, plus optional `ruleBatchSize` for bounded matched-pair execution.

**Outputs (key):**
- `missingInFile1Count`
- `missingInFile2Count`
- `missingObjectDifferenceCount`
- `ruleDifferenceCount`
- `differenceCount`
- `matchedPairCount`
- `ruleCount`
- `firedRuleCount`
- `diffDf`
- `missingDiffDf`
- `ruleDiffDf`
- `validationErrors`
- `processingWarnings`

The normalized `diffDf` rows use a stable internal contract:
- `diffType`
- `compareScopeId`
- `objectType`
- `primaryId`
- `field`
- `file1Value`
- `file2Value`
- `presentIn`
- `missingIn`
- `data`
- `ruleId`
- `severity`
- `message`

Behavior notes:
- Missing-object rows remain explicit as `MISSING_IN_FILE_1` / `MISSING_IN_FILE_2`.
- Field/business-rule rows are emitted from DRL as `FIELD_MISMATCH` or another rule-defined `diffType`.
- Matched pairs are processed in bounded batches before they are passed to Drools, so the Spark side stays responsible for the large-volume preparation.
- If DRL compilation or execution fails, the service preserves the base missing-object Diffs and reports the failure in `processingWarnings` with `ruleSetId`, `compareScopeId`, and sample `primaryId` values when available.

### `ReconciliationCoreServices.reconcile#UnifiedFiles`

Single reconciliation engine for all file type combinations. It converts CSV/JSON inputs into a normalized Spark structure, runs the compare once, and always emits a JSON diff. JSON and Mixed legacy service entry points are wrappers only.

**Inputs (key):** `file1Location`, `file2Location`, `file1Type`, `file2Type`, `file1IdField`/`file2IdField` (or `file1IdExpression`/`file2IdExpression`), `file1SchemaFileName`/`file2SchemaFileName` for JSON sources, `hasHeader`, `outputLocation`, `sparkMaster`, `sparkAppName`.

**Outputs (key):** `reconciliationType`, `diffLocation`, `diffFileName`, `differenceCount`, `onlyInFile1Count`, `onlyInFile2Count`, `validationErrors`, `processingWarnings`.

> **Note**: Direct usage of `ReconciliationJsonServices.reconcile#JsonFiles` is supported but the Generic service is preferred for UI integration.

### `ReconciliationGenericServices.delete#GeneratedOutputFile`

Deletes a single generated output file from `runtime://tmp/reconciliation/generic/output`.

**Inputs (key):** `filename` (required), optional `outputLocation`.

**Outputs (key):** `deleted`, `deletedFileName`, `statusMessage`.

### `ReconciliationGenericServices.purge#GeneratedOutputFiles`

Purges generated output files older than retention policy from `runtime://tmp/reconciliation/generic/output`.

**Inputs (key):** `retentionDays` (default `15`), optional `outputLocation`.

**Outputs (key):** `scannedCount`, `deletedCount`, `retainedCount`, `failedFiles`, `statusMessage`.

## Schema Management

For details on creating and managing schemas used in reconciliation, see [JSON Schema Management](json-schema-management.md).


## JSONPath Expression

The `compareJsonPath` parameter uses JSONPath syntax to extract ID values from your JSON structure.

When a schema is provided and you pass a simple key (like `id` or `$.id`), the resolver expands it to the full JSONPath. For array-root schemas this becomes `$[*].id`.

When source ID formats differ between systems, configure an ID normalizer per mapping member (`idValueNormalizer`) in Mapping Builder/Editor. In the compare-scope adapter path, the same normalizer behavior is configured per `RuleSetCompareSource`.
For backward compatibility, inline syntax with `|NORMALIZER` in `idFieldExpression` is still supported.

For compare-scope JSON sources, extraction can also be split into:
- `recordRootExpression`: where the repeated object records live
- `primaryIdExpression`: how to extract the compare ID from each object

The adapter combines those two values into the JSONPath shape used by the existing Spark ingestion logic. When `recordRootExpression` names a repeated collection without an explicit array marker, the adapter expands it to the collection wildcard form first. Example: `data.orders.edges` becomes `$.data.orders.edges[*]` before `primaryIdExpression` is appended.

Supported normalizers:
- `SHOPIFY_GID_TAIL`: extracts the numeric tail from Shopify GIDs (for example `gid://shopify/Product/7944971747446` -> `7944971747446`)
- `TRAILING_DIGITS`: extracts trailing digits from the value when present

### Examples

**Simple ID field at root:**
```
$.id
```

**Nested object:**
```
$.data.orderId
```

**Array of objects:**
```
$[*].id
```

**Deeply nested in array:**
```
$[*].data.orders.edges[*].node.legacyResourceId
```

**Shopify GID to numeric ID compare (mapping-driven):**
```text
System 1 ID expression: $[*].id
System 1 ID normalizer: SHOPIFY_GID_TAIL
System 2 ID expression: $[*].shopifyProductId
```

## Output Format

The reconciliation output is a JSON file with the following structure:

`metadata.timestamp` is written as an ISO-8601 UTC string.

```json
{
  "metadata": {
    "timestamp": "2026-01-02T12:00:00Z",
    "json1Label": "Orders File 1",
    "json2Label": "Orders File 2",
    "schemaFileName": "orders.schema.json",
    "compareJsonPath": "$[*].data.orders.edges[*].node.legacyResourceId",
    "validationPassed": true
  },
  "summary": {
    "totalDifferences": 4,
    "onlyInJson1Count": 2,
    "onlyInJson2Count": 2
  },
  "validationErrors": [],
  "differences": [
    {
      "type": "missing_in_json2",
      "id": "6470622019715",
      "presentIn": "Orders File 1",
      "missingIn": "Orders File 2",
      "data": {
        "legacyResourceId": "6470622019715",
        "id": "gid://shopify/Order/6470622019715",
        "name": "#GOR195874999",
        ...
      },
      "note": "Present in Orders File 1, missing in Orders File 2"
    }
  ]
}
```

For the pilot PWA flow, the backend facade keeps this JSON file as the stored source of truth but can convert the `differences` array to CSV on retrieval so the UI can offer a direct CSV download without reintroducing the larger legacy output-management surface.

## Usage Example

### Via Service Call

```xml
<service-call name="ReconciliationJsonServices.reconcile#JsonFiles">
    <field-map field-name="json1Location" value="component://darpan/data/orders-shopify.json"/>
    <field-map field-name="json2Location" value="component://darpan/data/orders-oms.json"/>
    <field-map field-name="schemaFileName" value="orders.schema.json"/>
    <field-map field-name="compareJsonPath" value="$[*].data.orders.edges[*].node.legacyResourceId"/>
    <field-map field-name="json1Label" value="Shopify Orders"/>
    <field-map field-name="json2Label" value="OMS Orders"/>
</service-call>
```

### Via File Upload Screen

1. Upload both JSON files
2. Select the JSON schema to validate against
3. Enter the JSONPath expression for the ID field
4. Optionally provide labels for the files
5. Submit to run reconciliation
6. Download the results JSON file
7. Use **View** next to a JSON result to see the summary and separate lists for records missing in each JSON file (with full order JSON payloads)

## Testing

Test files are located in `data/test/`:
- `test-orders-1.json`: Sample orders (3 orders)
- `test-orders-2.json`: Sample orders (3 orders, 1 overlapping)
- `test-json-reconciliation.groovy`: Test script

Schema: `runtime://schemas/json/test-orders.schema.json`

**Expected Results:**
- 2 orders only in file 1
- 2 orders only in file 2
- 1 order in both (should not appear in differences)

## Notes

- Schema validation is performed first; reconciliation continues even if validation fails
- For JSON array roots, validation reads the full array for small files and samples the first element for large payloads to reduce memory usage
- The reconciliation uses ID-based comparison (like CSV reconciliation)
- Full objects are preserved in the output for data analysis
- JSONPath library (`com.jayway.jsonpath:json-path:2.8.0`) is required
- Schema parsing for validation/generation uses Jackson ObjectMapper to avoid ad-hoc JSON handling
- Diff detail view streams large diff files and paginates missing-record lists independently (default 20 per page) using Moqui controls to keep previews responsive
- Diff detail view includes a Spark-backed record_id search (LIKE-based, full-file filter before pagination; auto-resets to the first page if the current page is beyond the filtered results)
- Schema generation requires non-empty JSON input files; empty uploads are rejected
- What changed: Diff View is hidden from the navigation menu and is accessed from the generated file list under File Reconciliation (direct `/Reconciliation/GenericReconciliationView` access removed)
- What changed: Simple ID keys are resolved against array-root schemas (e.g., `id` -> `$[*].id`) and small array payloads are fully validated before sampling large ones
- What changed: Schema generation in non-strict mode merges array item shapes to keep schemas compact instead of emitting large `anyOf` lists
- What changed: Mapping members now support explicit `idValueNormalizer` so ID transforms (for example Shopify GID tail extraction) can be configured separately from JSONPath/column expressions before comparison
- What changed: Generated Reconciliation Files now support inline delete actions in the UI, and seed data includes a daily purge job (`purge_ReconciliationGeneratedFiles_daily`) with `retentionDays=15`
