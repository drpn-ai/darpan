# Darpan 3.5.4

Release date: 2026-11-26

## Scope note

- **The one prod roll tonight, re-cut from 3.5.3** (Aditi via the coordinator 18:33: DAR-BE-382 P0 in tonight's roll if ready
  before CLEAR; it was). 3.5.4 = `v3.5.3` + DAR-BE-382 (56b4c88f, eab82caf). Prod moves from 3.5.1, so it gets everything in
  3.5.2 and 3.5.3 too (see their packs).
- **Upgrade data**: none of its own; 3.5.3's 5 records load from their `darpan-seed` files on boot. **Migration**: run
  `ACK_NOTE_NULLABLE` after the deploy.
- Facade contract stays at **6** (242).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.1` (deployed; `v3.5.2`, `v3.5.3` cut, never deployed) | `v3.5.4` |
| `drpn-ai/darpan-hotwax` | `v1.4.0` | `v1.4.1` (3.5.3's) |
| `drpn-ai/netsuite-darpan` | `v0.8.0` | `v0.8.1` (3.5.3's) |
| `drpn-ai/darpan-docker-config` | `v3.5.1` | `v3.5.4` |
| `drpn-ai/darpan-ui` | `v4.4.14` | `v4.4.15` (after the migration) |

## User-visible changes

- **A sync pulse that does not finish twice in a row tells the Darpan team in its alerts room, never the client's chat room**
  (DAR-BE-382). Two causes of a failed pulse are fixed: a NetSuite read of more than 1,000 orders by key (each batch now reads
  its own folder; GORJANA pair 100000 failed at 18:32 IST on "FILE_NOT_EXIST"), and a failure reason too long to record
  (cut to 255).
- Everything in 3.5.3: a pulse never waits behind its tenant's audit (DAR-BE-379); a daily NetSuite-only audit check reads what
  changed (DAR-BE-320); the acknowledgement-note migration fix (DAR-UI-152).

## Operator-visible changes

- `ErrorChatSupport.postAlertText` carries "<tenant> · <pair>: 2 pulses in a row did not finish. Last: <reason>".

## Upgrade data

- None of its own. `data/upgrade-data.xml` holds no records; the previous is archived at `data/releases/3.5.3/upgrade-data.xml`
  (equals the `v3.5.3` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- GORJANA R10-R12 change fields (DAR-BE-320 config step, not approved).

## Rollback or fallback notes

- Roll back by redeploying `v3.5.1` (docker-config `v3.5.1`, image `prod-v3.5.1-202611261134-127fb7d`).
