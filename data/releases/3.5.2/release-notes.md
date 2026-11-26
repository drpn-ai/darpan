# Darpan 3.5.2

Release date: 2026-11-26

## Scope note

- **A hotfix on the 3.5 line** (Aditi, 2026-11-26: DAR-BE-320 moved to its own thread; "cut 3.5.2 with e2a6e16f alone").
  It carries everything on darpan `main` after `v3.5.1`: the DAR-UI-152 migration fix only. Rolled after GORJANA's
  Nov 1-26 play (until about 19:00 IST) and the cause-review session's "clear".
- **Upgrade data**: none (0 records). **Migration**: run `ACK_NOTE_NULLABLE` after the deploy (dry run first).
- Facade contract stays at **6**; GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.1` | `v3.5.2` |
| `drpn-ai/darpan-docker-config` | `v3.5.1` | `v3.5.2` (DARPAN_REF move) |
| `drpn-ai/darpan-ui` | `v4.4.14` | `v4.4.15` (after the migration) |
| every other component | unchanged | unchanged |

## User-visible changes

- **Acknowledging a finding needs no note** (DAR-UI-152), once the migration has run and app `v4.4.15` is out. 3.5.1's run
  of the migration failed: MySQL refuses DDL inside the XA transaction `run#Migration` runs in (`XAER_RMFAIL`). Nothing
  changed then.

## Operator-visible changes

- `AcknowledgementNoteMigration.run` suspends the caller's transaction around its DDL (e2a6e16f), proven by
  `AcknowledgementNoteMigrationMoquiMysqlTests` (Moqui on MySQL with Bitronix, as prod).

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; the previous is archived at `data/releases/3.5.1/upgrade-data.xml`
  (equals the `v3.5.1` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-BE-320 (NetSuite daily reads): redesigned in its own thread.

## Rollback or fallback notes

- Roll back by redeploying `v3.5.1` (docker-config `v3.5.1`, image `prod-v3.5.1-202611261134-127fb7d`). A migration already
  run stays (the column stays nullable).
