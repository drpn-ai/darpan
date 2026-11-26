# Upgrade Data Review For Darpan 3.5.4

## Scope

- Backend compare range: `v3.5.3..HEAD`
- Data directory reviewed: `data`

## Candidate records

- None: `git diff v3.5.3..HEAD -- data/ entity/ service/` is empty; DAR-BE-382 changes Groovy only.

## Recommended operator review

- Prod moves from 3.5.1; 3.5.3 was cut and never deployed. Its 5 records (AuditSharedRead type and statuses,
  DARPAN_SCOPE_AUD_SHARED_READ) still reach prod: the prod entrypoint loads every `darpan-seed` file on boot, and both
  source files (`data/AuditSeedData.xml`, `data/SecuritySeedData.xml`) are `darpan-seed`. Confirm they exist after the boot.

## Operator action

- After the boot: run `run#PendingMigrations` (dry run first) for `ACK_NOTE_NULLABLE`; then app `v4.4.15`.
