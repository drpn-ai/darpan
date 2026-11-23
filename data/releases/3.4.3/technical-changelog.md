# Technical Changelog For Darpan 3.4.3

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- Patch: one additive facade method and one additive output field (contract stays 5: nothing removed or changed), a
  netsuite-darpan query-template widening and a test-only cleanup on the 3.4 line. No schema, seed or migration change.

## Source ranges

- Backend: `v3.4.2..v3.4.3` (`cfe684cc`, `628ce67a`, then the release commit)
- netsuite-darpan: `v0.7.0..v0.7.1` (`faaaeac`, then the release commit)
- UI: `v4.4.2..v4.4.3` (`3af5dd1`, `152d422`, `03c234e`, `cc58590`, `18c8715`, `7643e44`, then the release commit)

## Backend

### Added

- **DAR-UI-138** (`cfe684cc`) `facade.TenantScriptFacadeServices.save#TenantQueryPins` (authz `view`: a personal
  preference) stores the signed-in user's pinned query ids for the active tenant in the user preference
  `darpan.queries.pinnedIds.<tenantId>` as JSON, in the order given, once each, only the active tenant's queries
  (unknown or other-tenant ids dropped, never an error); "Choose a tenant first." with no active tenant.
  `list#TenantQueries` returns `pinnedQueryIds` (pin order, a deleted query drops out). Both reuse
  `ReconciliationDashboardPreferenceSupport`'s parse/normalize (the dashboard pins' helpers). On `CliMethodAllowlist`'s
  exclusion list. Contract: `methods.txt` +1, `openapi.json` request/response schemas, facade snapshot 210 -> 211
  services. Tests: `TenantQueryFacadeSmokeTests.pinsKeepTheTenantsOwnQueriesInOrder`,
  `aDeletedQueryDropsOutOfThePins`.

### Changed

- **DAR-OPS-026** (`628ce67a`, test-only effect): `resetForTests()` removed from `BrainConclude` (`fetchBatch`),
  `BrainEngine` (`CACHE`) and `RunConclusionStep` (`lastCaptureMillis`) by Aditi's call; their reset tests
  (`BrainEngineResetTests`, `RunConclusionStepResetTests`, part of `BrainStoreResetForTestsTests`) go and
  `StaticSeamResetGuardTests` allowlists the three statics with reasons. No production behaviour change.

## netsuite-darpan

### Added

- **DAR-BE-344** (`faaaeac`) `NsSuiteQlOrderSupport`: a `SALES_LINES` header column may be `LINE_SUM(...)` or
  `LINE_ITEMS(...)`; it is written as a sub-select over the line's order (`orderSubSelect`, beside `LINKED_RECORDS`)
  instead of `t.<col>`, so every line carries the order's aggregate. Same read, no new calls. Test
  `NsSuiteQlQueryTemplateTest.salesLinesCarryTheOrdersLineSumsAndItems`. Live read-only SuiteQL check on Malbon NetSuite
  (orders 122159, 122819, 123631).

## UI

- **DAR-UI-138** (darpan-ui `v4.4.3`): routes `/queries`, `/queries/new`, `/queries/:tenantScriptId` (old
  `/settings/tenant/scripts...` redirect with query and hash); Dashboard "Queries" action; Cmd+K "Queries"; Tenant
  Settings' Queries row removed; `/queries` as the Dashboard (Pinned Queries drop zone saving through
  `save#TenantQueryPins`, reverting on failure; tiles grouped by the first input's source, six per group then "N more");
  the query page as Data -> Script -> Result with `QueryDataPopup` editing one read input by name over
  `get#TenantQueryCatalog` (`queryRead.ts` reads and writes the read JSON, keeping keys it does not touch; inputs with
  or-conditions, groups, measures or an order go to the JSON); `scheduleState` deleted with the old list. Contract copy
  synced from `cfe684cc`.

## Data and configuration

- No seed, schema, job, setting, remote, auth or runtime-config change. `upgrade-data.xml` is empty for 3.4.3.
- `docs/api-contract/openapi.json`: version line 3.4.2 -> 3.4.3 in the release commit (the method's schemas came with
  `cfe684cc`).
- Docker: `docker/prod/Dockerfile` moves `DARPAN_REF` v3.4.3 and `NETSUITE_DARPAN_REF` v0.7.1.

## Validation and rollout notes

- Deploy with the scheduler on: nothing to migrate. Deploy the app (4.4.3) after the backend: it calls
  `save#TenantQueryPins`.

## References

- https://github.com/drpn-ai/darpan/compare/v3.4.2...v3.4.3
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.7.0...v0.7.1
- https://github.com/drpn-ai/darpan-ui/compare/v4.4.2...v4.4.3
- Backlog: DAR-UI-138, DAR-BE-344, DAR-OPS-026 (`roadmap/BACKLOG.md`)
