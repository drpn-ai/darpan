# Technical Changelog For Darpan 3.5.0

## Versioning decision

- Minor: contract 5 -> 6 (`check_contract_compat.py v3.4.16..HEAD`: six breaking changes, all in
  `facade.LookupFacadeServices`), new entities, new system-component pins.

## Source ranges

- Backend: `v3.4.16..v3.5.0` (https://github.com/drpn-ai/darpan/compare/v3.4.16...v3.5.0)
- darpan-hotwax `v1.3.5..v1.4.0`, shopify-darpan `v0.11.3..v0.12.0`, netsuite-darpan `v0.7.2..v0.8.0`
- UI: app `v4.4.15` (DAR-UI-152, 731d561) after the migration.

## Backend

### Added
- DAR-BE-374 (e38ccb16): `facade.IdIndexFacadeServices` (4 methods); entities IdIndexSource, IdIndexField,
  TenantIdIndexField, RecordIdIndex, RecordIdIndexCursor; Dataset `refreshMinutes`/`marginMinutes`; job
  `maintain_IdIndex_nightly`; settings `darpan.idindex.months`, `darpan.idindex.customerFieldPatterns`.
- DAR-BE-373 part (c) (06eec322): `run#Audit` `checks`.

### Removed
- `facade.LookupFacadeServices` `get#LookupCatalog`, `save#`/`delete#TenantLookupRead`, `save#`/`delete#TenantLookupLink`;
  `lookup#Order` `all`; the LookupRead/LookupLink entities (prod tables stay, unused).

### Changed
- `lookup#Order` returns `{value, records, findings, notSeen, capped, cappedReason}` from the index.

### Fixed
- `AcknowledgementNoteMigration.statementFor` (ba8a8f24): MySQL keeps the type's size; `AcknowledgementNoteMigrationMysqlTests`
  (927b3417) proves it on MySQL 8.4.

### Tests
- DAR-BE-374 session, rebased tree: 268/268 across 27 classes, RpcMethodFenceTests 23; this cut: ApiContractGeneratorTests,
  FacadeContractSnapshotTests, TenantOwnedEntityFilterContractTests (`PENDING_NEXT_PACK` emptied); mysqlTest 2.

## UI

- None in this tag; app `v4.4.15` after the migration. The app calls none of the removed methods (checked).

## Data and configuration

- 4 upgrade records; ID index tables created on boot; `ACK_NOTE_NULLABLE` to run after the deploy.

## Validation and rollout notes

- Roll only on the cause-review session's "clear" and an empty `darpan-admin --target prod live`. Then the migration dry
  run (shown to Aditi), the run, a browser look, app `v4.4.15`; then CLI `0.6.0`.

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; upgrade review: `upgrade-data-review.md`.
- Backlog: DAR-BE-374, DAR-UI-152, DAR-BE-373 (`roadmap/BACKLOG.md`).
