# Technical Changelog For Darpan 3.5.2

## Versioning decision

- Patch on the 3.5 line: one migration's code; no contract, schema or data change.

## Source ranges

- Backend: `v3.5.1..v3.5.2` (https://github.com/drpn-ai/darpan/compare/v3.5.1...v3.5.2)

## Backend

### Fixed
- `AcknowledgementNoteMigration.run` (e2a6e16f): suspends the caller's transaction around the DDL and uses a connection
  outside it; MySQL refuses DDL on a connection enlisted in an active XA transaction.

### Tests
- `AcknowledgementNoteMigrationMoquiMysqlTests` (new, mysqlTest): failed with `XAER_RMFAIL` on the old code, passes now;
  `AcknowledgementNoteMigrationMysqlTests` 2; `AcknowledgementNoteMigrationTests` 2; `AuditAcknowledgeSmokeTests` 9.

## UI

- None in this tag; app `v4.4.15` (DAR-UI-152, 731d561) after the migration.

## Data and configuration

- No upgrade data. Migration `ACK_NOTE_NULLABLE` (registered by 3.4.16) runs after the deploy.

## Validation and rollout notes

- Roll only on the cause-review session's "clear" (after GORJANA's Nov 1-26 play, about 19:00 IST) and an empty
  `darpan-admin --target prod live`. Then `migrations run ACK_NOTE_NULLABLE --dry-run` (shown to Aditi), the run, a browser
  look, app `v4.4.15`.

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; upgrade review: `upgrade-data-review.md`.
- Backlog: DAR-UI-152 (`roadmap/BACKLOG.md`).
