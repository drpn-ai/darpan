# Technical Changelog For Darpan 3.4.17

## Versioning decision

- Patch on the 3.4 line: one optional facade parameter (contract stays 5), no data change.

## Source ranges

- Backend: `v3.4.16..v3.4.17` (https://github.com/drpn-ai/darpan/compare/v3.4.16...v3.4.17)
- UI: app `v4.4.15` (DAR-UI-152, 731d561) deploys after the migration.

## Backend

### Fixed
- `AcknowledgementNoteMigration.statementFor` (ba8a8f24): MySQL/MariaDB `MODIFY` keeps VARCHAR/CHAR/VARBINARY/BINARY size
  from `COLUMN_SIZE`.

### Added
- `run#Audit` `checks` (06eec322, DAR-BE-373 part (c)): check codes to play; only their automations run.

### Tests
- `AcknowledgementNoteMigrationTests` 2 (new: MySQL keeps the size), `AuditAcknowledgeSmokeTests` 9;
  cause-review session: `AuditSettingsSmokeTests` 21, `ApiContractGeneratorTests` 8, `FacadeContractSnapshotTests` 1.

## UI

- None in this tag; app `v4.4.15` (DAR-UI-152) after the migration.

## Data and configuration

- No upgrade data. Migration `ACK_NOTE_NULLABLE` (registered by 3.4.16) runs after the deploy.

## Validation and rollout notes

- Roll only when the cause-review session says "clear" and `darpan-admin --target prod live` shows nothing running. Then
  `migrations run ACK_NOTE_NULLABLE --dry-run` (expect `MODIFY NOTE VARCHAR(<n>) NULL` or the column's full type), shown to
  Aditi, then the run, a browser look, then app `v4.4.15`.

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; upgrade review: `upgrade-data-review.md`.
- Backlog: DAR-UI-152, DAR-BE-373 (`roadmap/BACKLOG.md`).
