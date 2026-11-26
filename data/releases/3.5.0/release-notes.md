# Darpan 3.5.0

Release date: 2026-11-26

## Scope note

- **A minor release, contract 6** (Aditi, 2026-11-26 15:50 IST "One roll: re-cut with the ID index"). It carries everything
  on darpan `main` after `v3.4.16`: the 3.4.17 tag (cut, never deployed: DAR-UI-152's migration fix, DAR-BE-373 part (c))
  and DAR-BE-374 (ID index). Rolled only after the cause-review session's "clear" and an empty prod live view.
- **Breaking API change** (contract 5 -> 6): `facade.LookupFacadeServices` `get#LookupCatalog`, `save#`/`delete#TenantLookupRead`
  and `save#`/`delete#TenantLookupLink` are removed, and `lookup#Order` loses `all`. CLI `0.5.0`'s `lookup --all` and catalog
  commands stop working; CLI `0.6.0` follows the deploy.
- **Upgrade data**: 4 records (3 EntityFilters, 1 ServiceJob seeded paused), loaded on boot. **Migration**: run
  `ACK_NOTE_NULLABLE` after the deploy.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.16` (deployed; `v3.4.17` cut, not deployed) | `v3.5.0` |
| `drpn-ai/darpan-hotwax` | `v1.3.5` | `v1.4.0` |
| `drpn-ai/shopify-darpan` | `v0.11.3` | `v0.12.0` |
| `drpn-ai/netsuite-darpan` | `v0.7.2` | `v0.8.0` |
| `drpn-ai/darpan-docker-config` | `v3.4.16` | `v3.5.0` (four pins move) |
| `drpn-ai/darpan-ui` | `v4.4.14` | `v4.4.15` (after the migration) |
| database-darpan, darpan-agent, moqui-rules and the framework pins | unchanged | unchanged |

## User-visible changes

- **`darpan lookup` answers from an ID index of everything Darpan already reads** (DAR-BE-374): no call to Shopify, HotWax
  or NetSuite. Dataset reads and run extracts are indexed as Darpan reads them; ids Darpan has not seen come back as
  `notSeen`. With CLI `0.6.0`.
- **Acknowledging a finding needs no note** (DAR-UI-152), once the migration has run and app `v4.4.15` is out.
- **A play can run only the checks named** (DAR-BE-373 part (c)): `run#Audit` takes `checks`.

## Operator-visible changes

- `facade.IdIndexFacadeServices`: `search#RecordIds`, `get#IdIndexStatus`, `save#`/`delete#TenantIdIndexField`.
- Settings `darpan.idindex.months` (13) and `darpan.idindex.customerFieldPatterns`; job `maintain_IdIndex_nightly` (paused).
- The note migration's MySQL statement keeps the column's size; proven on MySQL 8.4 (`mysqlTest`).

## Upgrade data

- `data/upgrade-data.xml`: `maintain_IdIndex_nightly` (from `data/ReconciliationJobSeedData.xml`) and the EntityFilters
  `DARPAN_SCOPE_TENANT_ID_INDEX_FIELD`, `DARPAN_SCOPE_RECORD_ID_INDEX`, `DARPAN_SCOPE_RECORD_ID_INDEX_CURSOR` (from
  `data/SecuritySeedData.xml`). The previous is archived at `data/releases/3.4.17/upgrade-data.xml` (equals the `v3.4.17`
  tag's). The system components' index sources load on boot from their own seed.

## Verification

- `release-checklist.md`.

## Deferred items

- CLI `0.6.0` and the docs.drpn.ai lookup page (the DAR-BE-374 session, after the deploy).

## Rollback or fallback notes

- Roll back by redeploying `v3.4.16` (docker-config `v3.4.16`, image `prod-v3.4.16-202611260934-440a6bb`) and app
  `v4.4.14`. The ID index tables stay unused; the old lookup's read rows are gone from the system components' seed, so a
  3.4.16 `lookup#Order` finds less until 3.4.16's components reload them on its boot.
