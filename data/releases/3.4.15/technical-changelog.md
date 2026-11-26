# Technical Changelog For Darpan 3.4.15

## Versioning decision

- Patch: brain rules and one access check; no method, schema or data change.

## Source ranges

- Backend: `v3.4.14..v3.4.15` (https://github.com/drpn-ai/darpan/compare/v3.4.14...v3.4.15)

## Backend

### Changed
- DAR-BE-373 (e12e8027): new brain question `orderItems` (`BrainQuestionCatalog`, subject Order, depth 1, OMS_GQL by
  orderId, `keepRecordFields [orderId, orderItems]`); `oms-netsuite.drl` asks it for an R7 order with no NetSuite sales
  order and no Items, `@mayEnable` CONC_RETIRED_SKU and CONC_PRODUCT_NOT_IN_NETSUITE (ceiling 66); the R7 statement rule
  matches `ORDER_APPROVED` or `ORDER_COMPLETED`. `BrainFactAdapter` builds Product/Item facts for ORDER findings from the
  answer (one `Answered(orderItems)` per order) through a shared `addItem` used by ORDER_LINE too.
- DAR-CLI-008 (de186515): `get#AuditChangeProposal` and `list#AuditChangeProposals` call
  `requireActiveTenantWriteAccess`; openapi.json descriptions regenerated.

### Tests
- `RetiredSkuPackTests` 10 R7 cases; `HeldByReturnPackTests` completed R7 order takes its cause; `BrainAskPlannerTests`
  ceiling `orderItems: 66`; `AuditChangeApplySmokeTests` view-only refusal (red first).
