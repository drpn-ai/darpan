# Technical Changelog For Darpan 3.4.16

## Versioning decision

- Patch on the 3.4 line: two facade parameters become optional (contract stays 5), one entity field loses not-null, one
  schema migration registered.

## Source ranges

- Backend: `v3.4.15..v3.4.16` (https://github.com/drpn-ai/darpan/compare/v3.4.15...v3.4.16)
- UI: `v4.4.14..v4.4.15` (https://github.com/drpn-ai/darpan-ui/compare/v4.4.14...v4.4.15)

## Backend

### Changed
- `acknowledge#AuditFinding`, `update#AuditFindingAcknowledgement` (ce175d4a): a blank note is no note; over 4,000
  characters still refused. The ledger's `replace#`/`update#FindingAcknowledgements` take a blank note.
- `AuditFindingAcknowledgement.note`: no longer not-null.

### Added
- `AcknowledgementNoteMigration.allowNull` and migration `ACK_NOTE_NULLABLE` (seq 170, dry run).

### Tests
- `AcknowledgementNoteMigrationTests` (new), `AuditAcknowledgeSmokeTests` 9, `MigrationRegistrySmokeTests` 4,
  `MigrationStatusSmokeTests` 2, `FacadeContractSnapshotTests` 1, `ApiContractGeneratorTests` 8.

## UI

- DAR-UI-152 (731d561): the popover's **Acknowledge** works with no text and sends `''`; `AuditAcknowledgement.note` is
  `string | null`; the card hides an empty note line; the row line drops it.

## Data and configuration

- Upgrade data: 1 record (`ACK_NOTE_NULLABLE`). Migration to run after the deploy.

## Validation and rollout notes

- Deploy the backend, run `run#PendingMigrations` (dry run, then run), look at the acknowledge popover with no note, then
  deploy app `v4.4.15`.

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; upgrade review: `upgrade-data-review.md`.
- Backlog: DAR-UI-152 (`roadmap/BACKLOG.md`).
