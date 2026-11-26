# Darpan 3.5.3

Release date: 2026-11-26

## Scope note

- **The one prod roll tonight** (Aditi 18:02 via the coordinator: one roll, 3.5.3; 18:11: DAR-BE-320 in if ready by CLEAR;
  re-baseline the authz ratchet with review). It carries everything on darpan `main` after `v3.5.1` (prod): 3.5.2's
  migration fix (cut, never deployed), DAR-BE-379, the DAR-BE-374 ratchet re-baseline and DAR-BE-320. Rolled after GORJANA's
  Nov 1-26 play and the cause-review session's "clear".
- **Upgrade data**: 5 records, loaded on boot. **Migration**: run `ACK_NOTE_NULLABLE` after the deploy.
- Facade contract stays at **6** (242); GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.1` (deployed; `v3.5.2` cut, never deployed) | `v3.5.3` |
| `drpn-ai/darpan-hotwax` | `v1.4.0` | `v1.4.1` (addce6b) |
| `drpn-ai/netsuite-darpan` | `v0.8.0` | `v0.8.1` (6dbbaff) |
| `drpn-ai/darpan-docker-config` | `v3.5.1` | `v3.5.3` (three pins) |
| `drpn-ai/darpan-ui` | `v4.4.14` | `v4.4.15` (after the migration) |
| shopify-darpan, database-darpan, darpan-agent, moqui-rules, framework pins | unchanged | unchanged |

## User-visible changes

- **A sync pulse no longer waits behind its tenant's audit** (DAR-BE-379). Pulses run on their own small lane, up to 4 at
  once across the instance (`darpan.reconciliation.automation.maxConcurrentLightRuns`), outside the run caps, so an audit
  holding its tenant's share no longer skips them (GORJANA's month close skipped both pulses on 2026-11-26). A pulse is
  still skipped when every light slot is in use or memory holds new starts. `darpan-admin live` shows lightRunning/lightCap.
- **A daily one-sided NetSuite audit check reads what could have moved since its last complete read**, not the month to date
  (DAR-BE-320), for every tenant whose check query can be read by change; the checks of one morning share one read.
  HotWax-enriched queries (GORJANA R10-R12) stay whole-window until `omsChangedStatusIds`/`liveLineStates` are set (not
  approved yet).
- **Acknowledging a finding needs no note** (DAR-UI-152) once the migration has run and app `v4.4.15` is out.

## Operator-visible changes

- `ACK_NOTE_NULLABLE` runs outside its caller's XA transaction (3.5.2's fix).
- `DisableAuthzRatchetTest` re-baselined with per-site review for DAR-BE-374's ID index (it shipped red in 3.5.0/3.5.1),
  plus DAR-BE-320's own sites (bare 34; netsuite-darpan Groovy 7).
- darpan-hotwax `reconciliation.HotWaxOmsExtractionServices.read#HotWaxOmsChangedOrderIds` (internal); load darpan-hotwax
  before netsuite-darpan.

## Upgrade data

- `data/upgrade-data.xml`: EnumerationType `AuditSharedRead` and its statuses `AUDSHR_DONE`, `AUDSHR_FAILED`,
  `AUDSHR_READING` (`data/AuditSeedData.xml`), EntityFilter `DARPAN_SCOPE_AUD_SHARED_READ` (`data/SecuritySeedData.xml`). The
  previous is archived at `data/releases/3.5.2/upgrade-data.xml` (equals the `v3.5.2` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- GORJANA R10-R12's change fields (`omsChangedStatusIds`, `liveLineStates`): a config step not approved yet.

## Rollback or fallback notes

- Roll back by redeploying `v3.5.1` (docker-config `v3.5.1`, image `prod-v3.5.1-202611261134-127fb7d`) with darpan-hotwax
  `v1.4.0` and netsuite-darpan `v0.8.0` (that tag's pins). The new table, columns and filter stay unused; a migration already
  run stays.
