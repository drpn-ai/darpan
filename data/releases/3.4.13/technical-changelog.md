# Technical Changelog For Darpan 3.4.13

## Versioning decision

- Patch on the 3.4 line: additive contract change (six methods), contract stays 5; two new entities created on boot and
  their two tenant filters as upgrade data; no migration.

## Source ranges

- Backend: `v3.4.12..v3.4.13` (https://github.com/drpn-ai/darpan/compare/v3.4.12...v3.4.13)
- darpan-hotwax `v1.3.4..v1.3.5`, shopify-darpan `v0.11.2..v0.11.3`, netsuite-darpan `v0.7.1..v0.7.2`
- UI: none in this tag (app `v4.4.14` was cut with 3.4.12)

## Backend

### Fixed
- `AuditAutomationSupport.rowOf` (e156f9b5): `findingsFrom` reads a result row through it, carrying `lookedUp`, so
  `recordsOf` keeps the lookup's record on the finding; `recordFacts.shipMethod` reads `omsItemShipmentMethodTypeId`.
- `AuditFindingsVerifySupport.progress`/`sinceStamp` (ad7edfd6, 1ee6239b): `since` read as a Timestamp, epoch millis or an
  ISO instant.

### Added (DAR-CLI-008 part 3)
- `facade.LookupFacadeServices`: `lookup#Order`, `get#LookupCatalog`, `save#`/`delete#TenantLookupRead`,
  `save#`/`delete#TenantLookupLink`; the follow-the-order engine (`OrderLookup`), `LookupCatalog`, entities
  `entity/LookupEntities.xml`; settings `darpan.lookup.maxReads`, `darpan.lookup.timeoutSeconds`; CLI allowlist
  (ef033dc0, f8bb0df6, 1a844045, 8d1ffa67, fbee2f0f, b17c37ad, 96cdc906, 5a1749b0).

### Tests
- `FindingCardRulesTests` 6 (R13 row read as written to disk), `AuditRecheckSinceTests` 1 (new),
  `AuditReportAssemblerSmokeTests` 45, `AuditAutomationSmokeTests` 29; DAR-CLI-008 session: OrderLookupTests 20,
  LookupFacadeSmokeTests 8, LookupCatalogSmokeTests 3, LookupSeedDataSmokeTests 3, DarpanSettingsTests 24,
  CliMethodAllowlistTests 8, TenantOwnedEntityFilterContractTests 5 (`PENDING_NEXT_PACK` emptied by this pack).

## UI
- None in this tag. App `v4.4.14` (cut with 3.4.12: DAR-UI-149/150/151 and the UI side of DAR-BE-371/372) deploys after it.

## Data and configuration
- Upgrade data: 2 `moqui.security.EntityFilter` records (the lookup tables' tenant filters), loaded on boot.
- New entities `darpan.lookup.TenantLookupRead`, `darpan.lookup.TenantLookupLink`, created on boot.
- Product lookup reads and links in the system components' seed (`darpan-seed`, loaded on boot).
- Settings `darpan.lookup.maxReads` (30), `darpan.lookup.timeoutSeconds` (60, at most 240).
- Gorjana prod (2026-11-26, tenant config, not seed): R13's `omsOrder` lookup keeps `orderName` too.

## Validation and rollout notes
- Deploy the backend first (darpan with darpan-hotwax 1.3.5, shopify-darpan 0.11.3, netsuite-darpan 0.7.2), re-check
  Gorjana R13 and see F-130294 show HotWax's status, then app `v4.4.14`; then CLI `0.5.0`.

## References
- Release notes: `release-notes.md`; checklist: `release-checklist.md`; upgrade review: `upgrade-data-review.md`.
- Backlog: DAR-BE-372, DAR-BE-375, DAR-CLI-008 (`roadmap/BACKLOG.md`).
