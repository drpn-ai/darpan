# Technical Changelog For Darpan 3.5.6

## Versioning decision

- Patch on the 3.5 line: link builders + one metadata field (no method, field or entity change; contract 6), a security
  hardening, and seed data.

## Source ranges

- Backend: `v3.5.5..v3.5.6` (https://github.com/drpn-ai/darpan/compare/v3.5.5...v3.5.6).

## Backend

### Changed
- 34721b81 (DAR-BE-384): `ReconciliationOutputSupport.getGeneratedOutputDifferences` adds `metadata.reconciliationRunResultId`
  from the run row it already reads (after the tenant access check). `TenantNotificationSupport.buildRunResultUrl` returns
  `<app>/runs/<savedRunId>/<runId>?tenantId=…` when the run id is known, else the long link. `AutomationExecutionSupport.runErrorUrl`
  is that one call (its run-live branch deleted). `NavigationSearchSupport`: a result a run row names targets
  `reconciliation-run-live` `/runs/…` with no query. Email sample link updated.

### Security
- 6a80afdd (DAR-BE-383 follow-up): the ID index's temp copy is created with NIO `createTempFile` (owner-only, 0600 on POSIX);
  no `deleteOnExit`.

### Data
- eb8f39eb, 471d4a61, b73600a3: `AppContentSeedData.xml` regenerated for darpan-ui e502c9e (DAR-UI-156), 82db59e (DAR-UI-154),
  f800cfd (DAR-UI-157).

### Tests
- DAR-BE-384 (on 34721b81): TenantNotificationServiceSmoke 12, AutomationExecutionSupport 132, SftpAutomationSupport 22,
  NavigationSearchFacadeSmoke 4, SavedRunsFacadeSmoke 62, ErrorDigest* 33, EmailTemplatesRender 29, EmailAdminServicesSmoke 18:
  312/312; new expectations red first; search test mutation-checked.
- This cut (`--rerun`): ApiContractGeneratorTests 8, FacadeContractSnapshotTests 1, AppContentRulesTests 11,
  TenantOwnedEntityFilterContractTests 5, DisableAuthzRatchetTest 3. Not run: the full suites (focused tests only).

## UI

- None in this cut.

## Data and configuration

- Upgrade data: the mascot seed (2382 rows). No setting or entity change.

## Validation and rollout notes

- Prod roll checked first: `darpan-admin --target prod live` empty; next fires 12:30 IST (Mephisto, RAILS Canada), so the roll
  finishes before 12:20 or waits until after 14:00 (Black Friday pulses 13:30).

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`.
- Backlog: DAR-BE-384, DAR-BE-383, DAR-UI-154, DAR-UI-156, DAR-UI-157 (`roadmap/BACKLOG.md`).
