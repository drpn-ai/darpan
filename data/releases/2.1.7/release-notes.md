# Darpan 2.1.7

Release date: 2026-10-30 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line: a repeated key is a finding, never a failed compare.** Steve Madden's Oct 1-30 run
  (100816) read both systems and then stopped at the compare with no results, because HotWax holds two orders named
  `SMUS#11830086`. Aditi, 2026-10-30: "we need to fix darpan.. the person should be able to see the results on
  Darpan".
- Cut from `v2.1.6` with one cherry-pick (DAR-BE-165). Nothing else from `main` ships.

## Repo targets

| Repo | From | To | Content |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.6` | `v2.1.7` | `19ac854` (DAR-BE-165) |
| `drpn-ai/darpan-docker-config` | `v2.1.6` | `v2.1.7` (prod pin moves) | |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **Repeated keys are findings.** When one system holds more than one record under a key, every one of those records
  shows on the run page under "Duplicates", the key is left out of the compare on both sides, and the run finishes
  with the rest of its results. Before, a run with rules stopped at the compare, and a run without rules quietly
  counted one of the records as a match.

## Operator-visible changes

- The per-side repeated-key choice in the run editor no longer changes anything; every two-sided compare reports
  repeated keys. A run's difference count includes them.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; `loadDarpanUpgradeData` is a no-op for this release.
- The 2.1.6 load target stays archived, byte-identical to `v2.1.6`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.6/upgrade-data.xml`.

## Verification

- Focused suites on the release line; counts in `release-checklist.md`.
- Not run before deploy: Steve Madden run 100816 retried from "Comparing records".

## Deferred items

- On sm-darpan after deploy: Retry run 100816 from "Comparing records"; `SMUS#11830086` should show as two duplicate
  findings, then the verify passes and the conclusions run on the 125k-record extracts.
- Remove the repeated-key choice from the run editor and the `duplicateKeyHandling` column with the next release.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.6`. A repeated key then fails a ruled compare again.
