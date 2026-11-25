# Darpan 3.4.7 Technical Changelog

## Versioning decision

- Patch on the 3.4 line: no facade method removed or field changed (contract stays 5), GraphQL unchanged.

## Source ranges

- darpan `v3.4.6..v3.4.7`, darpan-hotwax `v1.3.2..v1.3.3`, shopify-darpan `v0.11.1..v0.11.2`, darpan-ui
  `v4.4.8..v4.4.10`, darpan-docker-config `v3.4.6..v3.4.7`.

## Backend

### drpn-ai/darpan `v3.4.6..v3.4.7`

- `dc752961` DAR-BE-368: `AuditReportAssembler.recordFacts` adds `lines` ([{id, sku, name, quantity, status}]) from a
  Shopify order's `lineItems` (status from unfulfilled/current quantity: Unfulfilled, "1 of 3 unfulfilled", Fulfilled,
  Removed) or a HotWax order's `orderItems` (id = `externalId`, the Shopify line id; status in words);
  `AutomationExecutionSupport.withAskedReadFields` keeps a source's own `parameters.readFields` beside the rules'.
- `8b73bf82`, `e967fa2c`, `b71cf639` DAR-BE-362: `notifySavedRunError` (kind FAILED, trigger manual, dedupe
  `savedRun:<savedRunId>`); `runSavedRunDiff` sends rules-failed and thrown runs down the error path; subscribers get
  `DARPAN_ERROR_NOTICE` / `client.runfailed`; a run cancelled meanwhile is not told. `OpsNoticeSeedData.xml` adds the
  copy block; `OpsEntities.xml` changes a field description only.
- `79b68053` DAR-BE-363: `AuditDigestSupport` and `AuditDigest.xml` show the neutral not-run notice, never
  `health.summary`.
- `56f105ae` DAR-BE-366: `SettingAdminSupport.EMAIL_LIST_KEYS` includes `audit.unexplained.email.to`.
- `9b85d3be` 3.4.6 pack docs-impact completed after its tag.
- Release commit: component version 3.4.7, `openapi.json` version 3.4.7, upgrade data (1 record), this pack.

### drpn-ai/darpan-hotwax `v1.3.2..v1.3.3`

- `8bebb94` DAR-BE-368: derived order field `orderItems` (`OmsGqlQueries.ORDER_ITEMS_FIELD`), read by order id per page
  with SKUs from the product-name cache (`OmsGqlOrderPageSource.attachOrderItems`), only when asked; not covered by the
  shared orders read.
- Release commit: component version 1.3.3.

### drpn-ai/shopify-darpan `v0.11.1..v0.11.2`

- `24c7617` DAR-BE-368: `extract#ShopifyOrders` `withLineItems` (Boolean, default false) reads the extract-only source
  `SHOPIFY_ORDERS_WITH_LINES` (`allowsBulkConnections`, orders defaults plus `lineItems` id, sku, name, quantity,
  currentQuantity, unfulfilledQuantity); `ShopifyOrderLinesNesting` nests each `__parentId` line under its order holding
  one order at a time, counting orphans. `SHOPIFY_ORDERS` still refuses connections; `listSources` never lists the new
  source.
- Release commit: component version 0.11.2.

## UI

### drpn-ai/darpan-ui `v4.4.8..v4.4.10`

- `2f361d9` DAR-BE-368: `AuditRecord.lines`, `orderLineRows` (a Shopify line and its HotWax item as one row), a Lines
  block under Order on order findings.
- Release commit: app version 4.4.9 (`2de93e8`, tagged, never deployed).
- `1f32672` DAR-BE-368: the lines share one grid, so every row's SKU, statuses and quantity line up (found in the
  browser look on prod before the app deploy).
- Release commit: app version 4.4.10 (`2ec2444`), the app deployed with 3.4.7.

### drpn-ai/darpan-docker-config `v3.4.6..v3.4.7`

- `docker/prod/Dockerfile`: `DARPAN_REF v3.4.7`, `DARPAN_HOTWAX_REF v1.3.3`, `SHOPIFY_DARPAN_REF v0.11.2`.

## Data and configuration

- Upgrade data: 1 record (DAR-BE-362 `DARPAN_ERROR_NOTICE` / `client.runfailed`). New per-source options, off by default:
  Shopify orders `withLineItems`, HotWax orders `readFields: ["orderItems"]` (DAR-BE-368).

## Validation and rollout notes

- Focused tests and gates: `release-checklist.md`. Rollout: docker-config `v3.4.7` prod image via `deploy.sh`, then the
  app from a detached `v4.4.10` worktree.

## References

- Backlog: DAR-BE-362, DAR-BE-363, DAR-BE-366, DAR-BE-368.
