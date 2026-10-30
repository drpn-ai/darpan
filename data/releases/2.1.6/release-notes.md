# Darpan 2.1.6

Release date: 2026-10-30 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line for Steve Madden's month-wide order run.** Run 100766 (Oct 1-30) read 125,602 HotWax
  records in 17m53s, then stopped 2m22s into the Shopify read with "Java heap space" on sm-darpan's 2 GB JVM. The
  Shopify read now streams to disk, and a failed run can be retried from the step it failed at, reusing what it
  already read (Aditi, 2026-10-30: "fix the shopify extract to stream", "add the ability to retry at steps",
  "push and create a hotfix").
- Cut from the 2.1.5 tags, not from `main`: only DAR-BE-162 and DAR-BE-163 ship. DAR-BE-156's Shopify read-by-id
  rides in shopify-darpan because DAR-BE-162 is built on it; it is inert until darpan passes it ids.
- **Not in this release:** everything on `main` since 2.1.5 (DAR-BE-156's darpan half, DAR-BE-157, DAR-BE-160,
  DAR-BE-161).

## Repo targets

| Repo | From | To | Content |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.5` | `v2.1.6` | `c476d14` (DAR-BE-163) |
| `drpn-ai/shopify-darpan` | `v0.8.4` | `v0.8.5` | `c8dd40d` (DAR-BE-156), `201a214` (DAR-BE-162) |
| `drpn-ai/darpan-ui` | `v3.1.2` | `v3.1.3` | `40e38c1` (DAR-BE-163) |
| `drpn-ai/darpan-docker-config` | `v2.1.5` | `v2.1.6` (prod pins move) | |
| `drpn-ai/darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **Retry from the failed step.** A failed run shows "Retry from <step>". The retry is a new run, linked to the
  failed one, over its window; the sides the failed run finished reading are reused and only the rest is read again.
  It is refused, in words, if the run's rule set was edited after the run started, or if the failed run read nothing.

## Operator-visible changes

- The Shopify orders and order-line-units reads download to a file and write their extract record by record. The
  orders read no longer holds the month in memory; the line-units read still assembles its units in memory.
- The extract files are byte-for-byte what they were; the raw `.jsonl` beside them is the downloaded file itself.
- `run#SavedRunDiff` takes `retryFromRunResultId`.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; `loadDarpanUpgradeData` is a no-op for this release.
- The 2.1.5 load target stays archived, byte-identical to `v2.1.5`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.5/upgrade-data.xml`.

## Verification

- Focused suites on the release line; counts in `release-checklist.md`.
- Not run before deploy: a month-wide Shopify read on sm-darpan, and a retry of run 100766 in the browser.

## Deferred items

- On sm-darpan after deploy: retry run 100766 from "Extracting Shopify" and watch COMPARE, the verify passes and
  CONCLUDE, which still parse the extracts whole (DAR-BE-063 noted a 512 MB JVM failing on a 3-day gorjana window).

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.5`. A retry run already made stays a normal run; the parameter is
  then unknown and ignored.
