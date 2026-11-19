# Technical Changelog For Darpan 3.3.2

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- **Patch** on the 3.3 line: three fixes, no method added or removed, no parameter changed, contract version 4. Only
  darpan and darpan-docker-config are tagged. The tag sits off `main` (`v3.3.1` + the three fixes + this release
  commit): `main` also carries DAR-UI-132 and DAR-BE-320, which change an entity, the contract and seeds, so they wait
  for 3.4.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.3.1..v3.3.2` | 3 (2451cc00, f45889c4, 19842dd3: cherry-picks of `main`'s 2469bf5c, 69388bb2, 29cde809) |
| darpan-docker-config | `v3.3.1..v3.3.2` | 0 |

Commit counts exclude each repo's release-metadata commit.

## Backend

### Fixed

- `schedule.ScheduleServices.describe#ServiceJob` (DAR-BE-321): the newest `ServiceJobRun` is read with
  `entity-find limit="1"` (SQL LIMIT) instead of `<limit-range>`, which Moqui runs as `getPartialList()` ->
  `ResultSet.absolute()`; production's forward-only result sets refuse the jump, so every `get#ServiceJobs` call, and
  every job edit (they return the described job), failed in production only. Guard:
  `darpan.schedule.EntityFindPagingGuardTests` keeps `limit-range`/`limit-view` out of every component's XML.
- `JobScheduleRules.smallestGapSeconds` (DAR-BE-322): each gap between two runs is the larger of the elapsed time and
  the wall-clock gap, so a daily cron on a daylight-saving clock is 1440 minutes apart on the 23-hour day and a
  1440-minute floor passes; the repeated fall-back hour still counts as the hour that passes. Zone-taking overloads of
  `cronProblem` / `smallestGapSeconds` / `smallestGapMinutes` for tests; the services keep the runner's zone.
- `data/MigrationRegistrySeedData.xml` (DAR-BE-319): no row states `enabled`; the column defaults to Y on create, so a
  new migration starts enabled and a boot's seed load no longer re-enables one disabled in production.

## UI

- None: no UI change; darpan-ui stays at 4.3.0.

## Data and configuration

- `data/upgrade-data.xml`: the 12 registry rows as generated (changed only by the dropped attribute); no load needed.
  docker-config: `DARPAN_REF` v3.3.2; every other pin unchanged.

## Validation and rollout notes

- Commands and results: `release-checklist.md`.

## References

- https://github.com/drpn-ai/darpan/compare/v3.3.1...v3.3.2
- https://github.com/drpn-ai/darpan-docker-config/compare/v3.3.1...v3.3.2
