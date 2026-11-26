# Technical Changelog For Darpan 3.5.3

## Versioning decision

- Patch on the 3.5 line: no facade change (contract 6, `check_contract_compat.py` v3.5.2 -> HEAD compatible); a new entity,
  two nullable columns, two component pins.

## Source ranges

- Backend: `v3.5.2..v3.5.3`, prod moves from `v3.5.1` (https://github.com/drpn-ai/darpan/compare/v3.5.1...v3.5.3)
- darpan-hotwax `v1.4.0..v1.4.1`, netsuite-darpan `v0.8.0..v0.8.1`
- UI: app `v4.4.15` after the migration.

## Backend

### Added / Changed
- DAR-BE-379 (f229364f): RunAdmission light lane for sync pulses; setting `maxConcurrentLightRuns` (default 4, 1-16);
  `run#DuePulses` drops `queuedCount`.
- DAR-BE-320 (82b059a6; darpan-hotwax addce6b; netsuite-darpan 6dbbaff): audit NetSuite reads by change; entity
  `AuditSharedRead`; `NsSuiteQlSourceQuery.omsChangedStatusIds`, `liveLineStates`.
- DAR-BE-374 ratchet re-baseline (2600e352).

### Fixed
- 3.5.2's `AcknowledgementNoteMigration` (e2a6e16f): DDL outside the caller's XA transaction.

### Tests
- Pulse session on f229364f: 24 classes / 316 tests, 0 failures. DAR 321 session on 82b059a6: AuditMorningsSmokeTests 7,
  AuditSharedReadSupportTests 2, AuditRecentReadSupportTests 7, AuditRecheckSupportTests 12, AuditAutomationSmokeTests 29,
  TenantOwnedEntityFilterContractTests 5, StaticSeamResetGuardTests 4, DisableAuthzRatchetTest 3, RunPathParityGateTest 7,
  FacadeContractSnapshotTests 1, ApiContractGeneratorTests 8, RunAdmissionTests 22; netsuite-darpan 169; darpan-hotwax 30.
- This cut (--rerun, components at their release commits): FacadeContractSnapshotTests 1, ApiContractGeneratorTests 8,
  TenantOwnedEntityFilterContractTests 5 (PENDING_NEXT_PACK emptied), DisableAuthzRatchetTest 3.

## UI

- None in this tag; app `v4.4.15` after the migration.

## Data and configuration

- 5 upgrade records; one table and two columns created on boot; one setting (catalog default).

## Validation and rollout notes

- Roll only on the cause-review session's "clear" + an empty prod live. Then `ACK_NOTE_NULLABLE` dry run (shown to Aditi),
  run, browser look, app `v4.4.15`. Pulse watches GORJANA's next pulse.

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; upgrade review: `upgrade-data-review.md`.
- Backlog: DAR-UI-152, DAR-BE-379, DAR-BE-320, DAR-BE-374 (`roadmap/BACKLOG.md`).
