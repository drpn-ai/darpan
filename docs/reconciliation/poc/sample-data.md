# POC Sample Data

The sample compare service reads JSON files from `runtime/component/darpan/data/sample`.

## Files and Fields

- `runtime/component/darpan/data/sample/omsData.json`
  - OMS bulk export as a JSON array.
  - Order ID field used by default: `EXTERNAL_ID`.
- `runtime/component/darpan/data/sample/shopifyData.json`
  - Shopify bulk export in GraphQL format.
  - Order ID field used by default: `data.orders.edges[].node.legacyResourceId`.
- `runtime/component/darpan/data/sample/omsData.json`
  - Simplified OMS sample array.
  - Order ID field: `shopify_order_id`.
- `runtime/component/darpan/data/sample/shopifyData.json`
  - Simplified Shopify sample array.
  - Order ID field: `shopify_order_id`.

## Usage Notes

- The old `compare#SampleOrderIds` debug service has been removed.
- Use the production generic reconciliation services with explicit saved-run or mapping configuration for sample-data validation.
