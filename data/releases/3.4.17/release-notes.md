# Darpan 3.4.17

Release date: 2026-11-26

## Scope note

- **A hotfix on the 3.4 line, cut with app `v4.4.15`** (Aditi, 2026-11-26: "One release, both changes"; DAR-BE-374 left
  out after its design changed). It carries everything on darpan `main` after `v3.4.16`: the DAR-UI-152 migration fix and
  DAR-BE-373 part (c). Rolled only after the cause-review session's "clear" and an empty prod live view.
- **Upgrade data**: none (0 records). **Migration**: run `ACK_NOTE_NULLABLE` after the deploy (dry run first).
- Facade contract stays at **5** (243): one optional parameter added (`run#Audit` `checks`).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.16` | `v3.4.17` |
| `drpn-ai/darpan-docker-config` | `v3.4.16` | `v3.4.17` (DARPAN_REF move) |
| `drpn-ai/darpan-ui` | `v4.4.14` | `v4.4.15` (after the migration) |
| every other component | unchanged | unchanged |

## User-visible changes

- **Acknowledging a finding needs no note** (DAR-UI-152), once the migration has run and app `v4.4.15` is out. 3.4.16 shipped
  the backend half, but its migration would have sent MySQL `MODIFY NOTE VARCHAR NULL` (no size), which MySQL refuses;
  the prod dry run caught it before anything ran.

## Operator-visible changes

- `AcknowledgementNoteMigration.statementFor` (ba8a8f24): MySQL's MODIFY restates the column's type with its size.
- `run#Audit` takes an optional `checks` list of check codes; a play then runs only those checks' automations
  (DAR-BE-373 part (c), 06eec322).

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; the previous is archived at `data/releases/3.4.16/upgrade-data.xml`
  (equals the `v3.4.16` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-BE-374 (ID index): redesigned to read only what Darpan already holds; its own release.

## Rollback or fallback notes

- Roll back by redeploying `v3.4.16` (docker-config `v3.4.16`). A migration already run stays (the column stays
  nullable); 3.4.16 accepts a blank note too.
