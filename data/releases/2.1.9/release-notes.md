# Darpan 2.1.9

Release date: 2026-10-30 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line: the rest of DAR-BE-166.** On 2.1.8 Steve Madden run 100919 (Oct 1-30) read its extracts
  in partitions as intended, then ran out of heap in the rule batches. Watched live on sm-darpan, then reproduced
  end to end offline: every rule batch's parsed pairs stayed in memory until the run ended, and the pairs came to
  the driver as one partition.
- Cut from `v2.1.8` with two cherry-picks. Nothing else from `main` ships.

## Repo targets

| Repo | From | To | Content |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.8` | `v2.1.9` | `332ab1a`, `32ef412` (DAR-BE-166) |
| `drpn-ai/darpan-docker-config` | `v2.1.8` | `v2.1.9` (prod pin moves) | |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **Large windows compare.** A month of orders goes through the rules a bounded number of pairs at a time, and each
  batch is released once its rules have run.

## Operator-visible changes

- None beyond the compare's memory profile.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; `loadDarpanUpgradeData` is a no-op for this release.
- The 2.1.8 load target stays archived, byte-identical to `v2.1.8`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.8/upgrade-data.xml`.

## Verification

- Focused suites on the release line; counts in `release-checklist.md`.
- End to end before deploy: the whole run (compare, rules, output) on SM-sized extracts (125,602 / 125,611 orders, SM's
  two rules, Spark coalescing as on sm-darpan) SUCCEEDS with 5,111 differences in ~29 s at a 2 GB heap and at 768 MB;
  on 2.1.8 code the same run ran out of heap at 2 GB.
- Not run before deploy: the verify passes and conclusions against live HotWax/Shopify on sm-darpan.

## Deferred items

- On sm-darpan after deploy: Retry run 100919 from "Comparing records" and watch the verify passes and the conclusions.
- The result writers' `toLocalIterator` over the differences is left unbounded (row order matters there).

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.8`. A month-wide compare then runs out of heap at the rule batches.
