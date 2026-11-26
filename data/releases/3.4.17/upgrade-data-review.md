# Upgrade Data Review For Darpan 3.4.17

## Scope

- Backend compare range: `v3.4.16..HEAD`
- Data directory reviewed: `data`

## Candidate records

- No added or modified seed records were detected between the compared refs (`git diff v3.4.16..HEAD -- data/ entity/`
  is empty). The migration row `ACK_NOTE_NULLABLE` loaded with 3.4.16; 3.4.17 changes its code only.

## Operator action

- After the deploy, run `run#PendingMigrations` (dry run first): `ACK_NOTE_NULLABLE` is still pending on prod (3.4.16's
  dry run showed an invalid MySQL statement, which 3.4.17 fixes; it never ran).

## Recommended operator review

- Confirm `migrations` lists `ACK_NOTE_NULLABLE` as PENDING before the dry run, and that the dry run prints the column's
  full type (for example `VARCHAR(4095)`), not a bare `VARCHAR`.
