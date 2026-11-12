# Darpan 2.1.8

Release date: 2026-10-30 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line: a month of orders compares within the JVM's memory.** Steve Madden run 100867 (Oct 1-30)
  reused both reads (125,602 HotWax, 125,611 Shopify orders) and ran out of heap at the compare. Aditi, 2026-10-30:
  "fix the compare ingest and create a hotfix".
- Cut from `v2.1.7` with one cherry-pick (DAR-BE-166). Nothing else from `main` ships.

## Repo targets

| Repo | From | To | Content |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.7` | `v2.1.8` | `dc65032` (DAR-BE-166) |
| `drpn-ai/darpan-docker-config` | `v2.1.7` | `v2.1.8` (prod pin moves) | |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **Large windows compare.** A run over a month of orders reaches its results instead of stopping at "Comparing
  records".

## Operator-visible changes

- The compare reads each JSON extract's records one per line. A run's folder gains a `<extract>.records.jsonl` beside
  each JSON extract it compared; it is derived and can be removed with the run's files.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; `loadDarpanUpgradeData` is a no-op for this release.
- The 2.1.7 load target stays archived, byte-identical to `v2.1.7`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.7/upgrade-data.xml`.

## Verification

- Focused suites on the release line; counts in `release-checklist.md`.
- Reproduced before the fix: on SM-shaped extracts (73 MB HotWax, 96 MB Shopify) a 512 MB JVM ran out of heap
  building the matched pairs; with the fix it built all 125,602 pairs and read them through the rule batches in 22 s.
- Not run before deploy: Steve Madden run 100867 retried from "Comparing records".

## Deferred items

- On sm-darpan after deploy: Retry run 100867 from "Comparing records" and watch the verify passes and the conclusions
  on the 125k-record extracts.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.7`. A month-wide compare then runs out of heap again; the
  `.records.jsonl` files left beside extracts are inert.
