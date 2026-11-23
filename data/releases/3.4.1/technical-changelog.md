# Technical Changelog For Darpan 3.4.1

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- **Patch** on the 3.4 line: one fix (DAR-BE-338), contract version 5 unchanged (an optional parameter comes back; no
  method added or removed). The tag sits on `main` (`v3.4.0` + DAR-BE-338 + this release commit): nothing else landed
  on `main` since `v3.4.0`.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.4.0..v3.4.1` | 13 DAR-BE-338 commits |
| darpan-hotwax | `v1.3.0..main` | 1 (not tagged) |
| darpan-ui | `v4.4.1..v4.4.2` | 1 + release commit |
| darpan-cli | `v0.2.4..v0.2.5` | 1 + release commit |
| darpan-docker-config | `v3.4.0..v3.4.1` | pin only |

## Backend

### Changed (reverts of DAR-BE-318 3b-2c, filter half)

- `ReconciliationAutomationSourceFilter` back: entity, `DARPAN_SCOPE_AUTOMATION_SRC_FILTER`, EECA, change-tracker row,
  transfer child.
- `AutomationExecutionSupport.sideKeepOf` reads `AutomationRuntimeSupport.loadAutomationSourceFilters` (the automation's
  rows, its tenant). The record root and key fields stay the compare scope's, because `RuleSetCompareScopeAdapter`
  ingests the side with them (review I1). Retries (`sideKeepOfResult`) and `SyncPulseReader` go through it.
- `save#Automation` / `sync#Automation` / `delete#Automation` and the audit setup write and clear the automation's
  filter rows; `buildAutomationSyncStatus` names `FILE_n.excludeFilters`.
- Agent EXCLUSION_FILTER lever: syncs the run's automations again; `automationDrift` compares with
  `attachCapturedFilters`, so filter drift refuses with AUTOMATION_DRIFT.
- Config transfer: the exporter writes the filter child; `ConfigTransferImporter.fillAutomationFiltersFromRun` gives a
  source without the child key (3.4.0 shape) its run side's filters from the bundle; `AUTOMATION_FILTERS_DIFFER` gone.
- `migrate#AutomationExcludeFilters` and its backfill stay retired.

### Added

- `reconciliation.ReconciliationNotificationServices.restore#AutomationSourceFilters` /
  `darpan.migration.AutomationFilterCopySupport.copyFromRuns` (migration `AUTOMATION_FILTER_COPY`, sequence 160): runs
  only when `AUTOMATION_FILTER_TABLE` has a SUCCESS ledger row.

### Removed

- `retire#AutomationSourceFilterTable`, `RetiredTableSupport`; `AUTOMATION_FILTER_TABLE` left the registry seed.

### Tests

- `DisableAuthzRatchetTest`: Groovy 17 -> 18 (the copy's create write), XML 64 -> 66 and 34 -> 35 (save/delete
  automation filter writes back).
- Migration smoke tests count `MigrationRegistrySmokeTests.registeredInSeed()` instead of a hand count (they were red
  on `main` since DAR-BE-334 added `TENANT_SCRIPTS_TO_QUERIES`).

## UI (4.4.2)

- The automation draft sends `excludeFilters` again; Sync copy and its mascot hint say "source setup and filters".

## CLI (0.2.5)

- `darpan automation sync` compares filters again; the guide says sync copies source setup and filters.

## Data and configuration

- Upgrade data: `AUTOMATION_FILTER_COPY` registry row (seed), `AUTOMATION_FILTER_TABLE` parked by a hand row (the seed
  row was removed; the pack validator's one documented exception), the automation filter tenant filter, mascot content
  refresh. No new setting or env var.

## Validation and rollout notes

- Deploy with the scheduler off; boot loads `darpan-seed`; run `run#PendingMigrations` (dry run first); confirm
  `AUTOMATION_FILTER_COPY` rowsWritten >= 80; scheduler on. Then app 4.4.2, then CLI 0.2.5.
- Rollback: see release notes.

## References

- Backlog: DAR-BE-338 (`roadmap/BACKLOG.md`); plan `docs/superpowers/plans/2026-11-23-dar-be-338-automation-isolation.md`.
- Reverted: darpan b877df6a, 3fa8f519, 20e98fe1, 5872f6f1, bac3733c, d2944ad3 and parts of 184507b6 (DAR-BE-318 3b-2c);
  darpan-hotwax b2a1792, a4e1124; darpan-ui 4b28f28, 49611a3; CLI be817e6, e0240f2.
