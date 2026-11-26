# Upgrade Data Review For Darpan 3.5.2

## Scope

- Backend compare range: `v3.5.1..HEAD`
- Data directory reviewed: `data`

## Candidate records

- No added or modified seed records (`git diff v3.5.1..HEAD -- data/ entity/ service/` is empty). The release changes the
  `ACK_NOTE_NULLABLE` migration's code only.

## Recommended operator review

- Confirm `migrations` lists `ACK_NOTE_NULLABLE` as PENDING (its 3.5.1 run failed and changed nothing).

## Operator action

- After the deploy, run `run#PendingMigrations` (dry run first): `ACK_NOTE_NULLABLE` makes
  `AUDIT_FINDING_ACKNOWLEDGEMENT.NOTE` nullable. Then app `v4.4.15`.
