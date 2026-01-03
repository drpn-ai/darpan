# JSON Reconciliation

## Overview

The JSON reconciliation feature allows you to compare two JSON files using ID-based comparison and identify differences. The reconciliation validates both files against a JSON schema and outputs a detailed report with full object data preserved.

## Key Features

- **Schema Validation**: Validates both JSON files against a JSON Schema before reconciliation
- **ID-based Comparison**: Uses JSONPath expressions to extract identifiers from complex nested structures
- **Full Object Preservation**: Returns complete JSON objects for missing/mismatched data
- **Detailed Output**: JSON format with metadata, summary statistics, and full difference details

## Services

### `ReconciliationJsonServices.reconcile#JsonFiles`

Core reconciliation service that compares two JSON files.

**Input Parameters:**
- `json1Location` (required): Location of first JSON file
- `json2Location` (required): Location of second JSON file
- `schemaFileName` (required): Name of schema file in `runtime://schemas/json/`
- `compareJsonPath` (required): JSONPath expression to extract ID field
- `schemaFileName2` (optional): Schema file for JSON 2 (defaults to `schemaFileName`)
- `compareJsonPath2` (optional): JSONPath for JSON 2 (defaults to `compareJsonPath`)
- `json1Label` (optional): Display label for first file (default: "JSON 1")
- `json2Label` (optional): Display label for second file (default: "JSON 2")
- `outputLocation` (optional): Output directory (default: `tmp/reconciliation/json-diff/output`)
- `outputFileName` (optional): Custom output filename
- `maxIdsReturned` (optional): Max IDs to return in `onlyInJson1`/`onlyInJson2` lists (default: 1000; set 0 to disable)
- `sparkMaster` (optional): Spark master URL (default: "local[*]")
- `sparkAppName` (optional): Spark application name (default: "ReconciliationJsonCompare")

**Output Parameters:**
- `validationPassed`: Boolean indicating if both files passed schema validation
- `validationErrors`: List of validation error messages
- `differenceCount`: Total number of differences found
- `onlyInJson1Count`: Number of IDs only in first file
- `onlyInJson2Count`: Number of IDs only in second file
- `onlyInJson1`: List of IDs only in first file
- `onlyInJson2`: List of IDs only in second file
- `diffLocation`: Location of the reconciliation results file
- `diffFileName`: Name of the reconciliation results file

### `ReconciliationJsonServices.run#JsonReconciliation`

Wrapper service for handling file uploads and running reconciliation.

**Input Parameters:**
- `json1File` (FileItem): First uploaded JSON file
- `json2File` (FileItem): Second uploaded JSON file
- `schemaFileName` (required): Name of schema file
- `compareJsonPath` (required): JSONPath expression
- `schemaFileName2` (optional): Schema file for JSON 2 (defaults to `schemaFileName`)
- `compareJsonPath2` (optional): JSONPath for JSON 2 (defaults to `compareJsonPath`)
- `json1Label`, `json2Label` (optional): Display labels

**Output Parameters:**
Same as `reconcile#JsonFiles`

## JSONPath Expression

The `compareJsonPath` parameter uses JSONPath syntax to extract ID values from your JSON structure.

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

## Output Format

The reconciliation output is a JSON file with the following structure:

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
- For large JSON arrays, validation samples the first element only to reduce memory usage
- The reconciliation uses ID-based comparison (like CSV reconciliation)
- Full objects are preserved in the output for data analysis
- JSONPath library (`com.jayway.jsonpath:json-path:2.8.0`) is required
