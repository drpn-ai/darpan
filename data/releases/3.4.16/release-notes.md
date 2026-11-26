# Darpan 3.4.16

Release date: 2026-11-26

## Scope note

- **A backend release on the 3.4 line, cut with app `v4.4.15`** (Aditi, 2026-11-26 15:05 IST, "Yes, all four steps":
  cut and deploy 3.4.16, run the migration on prod after a dry run, a browser look, then the app). It carries everything on
  darpan `main` after `v3.4.15`: DAR-UI-152 only.
- **Upgrade data**: 1 record (the migration registry row `ACK_NOTE_NULLABLE`), loaded on boot. **Migration**: run
  `run#PendingMigrations` after the deploy.
- Facade contract stays at **5** (243): no method or parameter added; two parameters become optional.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.15` | `v3.4.16` |
| `drpn-ai/darpan-docker-config` | `v3.4.15` | `v3.4.16` (DARPAN_REF move) |
| `drpn-ai/darpan-ui` | `v4.4.14` | `v4.4.15` (after the migration) |
| every other component | unchanged | unchanged (each `main` equals its pin) |

## User-visible changes

- **Acknowledging a finding needs no note** (DAR-UI-152): leave the box empty and choose **Acknowledge**; the card shows
  who and when, with no empty note line. A change may clear the note. Feedback to Darpan still needs words.

## Operator-visible changes

- `AuditFindingAcknowledgement.note` is nullable; migration `ACK_NOTE_NULLABLE`
  (`audit.AuditLedgerServices.migrate#AcknowledgementNoteNullable`) relaxes an existing column: MySQL
  `ALTER TABLE ... MODIFY NOTE <its type> NULL`, H2 `SET NULL`, PostgreSQL `DROP NOT NULL`. Idempotent, dry run supported.

## Upgrade data

- `data/upgrade-data.xml`: the `darpan.migration.DarpanMigration` row `ACK_NOTE_NULLABLE` from
  `data/MigrationRegistrySeedData.xml`. The previous is archived at `data/releases/3.4.15/upgrade-data.xml` (equals the
  `v3.4.15` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- None from this release.

## Rollback or fallback notes

- Roll back by redeploying `v3.4.15` (docker-config `v3.4.15`) and app `v4.4.14`. The nullable column stays; a 3.4.15
  backend refuses a blank note again ("Add a note"); acknowledgements already saved with no note read as no note.
