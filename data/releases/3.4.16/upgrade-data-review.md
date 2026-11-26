# Upgrade Data Review For Darpan 3.4.16

## Scope

- Backend compare range: `v3.4.15..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=ACK_NOTE_NULLABLE`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="ACK_NOTE_NULLABLE" componentName="darpan" serviceName="audit.AuditLedgerServices.migrate#AcknowledgementNoteNullable" sequenceNum="170" supportsDryRun="Y" enabled="Y" description="Let an acknowledgement have no note (DAR-UI-152): AuditFindingAcknowledgement.note becomes nullable.                 Schema only, no row changes. Idempotent: a nullable column is left alone."/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.

## Operator action

- After the boot loads the upgrade data, run `run#PendingMigrations` (dry run first) so `ACK_NOTE_NULLABLE` makes
  `AUDIT_FINDING_ACKNOWLEDGEMENT.NOTE` nullable. Schema only; no row changes. Until it runs, an acknowledgement with no
  note fails ("Could not save the acknowledgement"). Deploy app `v4.4.15` after it.
