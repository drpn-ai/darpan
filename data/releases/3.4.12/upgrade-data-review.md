# Upgrade Data Review For Darpan 3.4.12

## Scope

- Backend compare range: `v3.4.11..HEAD`
- Data directory reviewed: `data`

## Candidate records

- No added or modified seed records were detected between the compared refs (`git diff v3.4.11..HEAD -- data/` is
  empty before this pack). The release changes code, the facade contract's method list and one Gorjana prod lookup's
  kept fields (a tenant config edit made in prod on 2026-11-26, not seed data).

## Operator action

- None. No upgrade data to load, no migration.
