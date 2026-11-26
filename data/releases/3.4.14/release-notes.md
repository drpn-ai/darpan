# Darpan 3.4.14

Release date: 2026-11-26

## Scope note

- **A backend hotfix on the 3.4 line** (Aditi, 2026-11-26 12:49 IST "go ahead" on DAR-CLI-008 part 3's release, deploy and live
  check). It carries everything on darpan `main` after `v3.4.13`: one fix found in the part 3 live check. Ships with `darpan`
  CLI `0.5.0`. Publicly it folds into the 3.4 notes.
- **Upgrade data**: none (0 records). No entity change, no migration. Contract stays 5 (243 methods). GraphQL unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.13` | `v3.4.14` |
| `drpn-ai/darpan-docker-config` | `v3.4.13` | `v3.4.14` (DARPAN_REF move) |
| `drpn-ai/darpan-cli` | `v0.4.0` | `v0.5.0` (`darpan lookup`) |
| darpan-ui, darpan-hotwax (v1.3.5), shopify-darpan (v0.11.3), netsuite-darpan (v0.7.2) and every other component | unchanged | unchanged |

## User-visible changes

- **An order lookup lists every open finding of the order** (DAR-CLI-008): also findings a check keyed by another system's
  ids, matched through the records they name (an order, its internal id, or one of its lines). Before, gorjana's
  "completed in HotWax and not billed" findings, keyed by their Shopify ids, did not show on `darpan lookup M1213101`.

## Upgrade data

- None. The previous archived at `data/releases/3.4.13/upgrade-data.xml` (equals the `v3.4.13` tag's).

## Verification

- `release-checklist.md`.

## Rollback or fallback notes

- Roll back by redeploying `v3.4.13` (docker-config `v3.4.13`); a lookup then misses those findings again.
