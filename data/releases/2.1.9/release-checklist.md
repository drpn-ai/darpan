# Release Checklist For Darpan 2.1.9

## Scope

Patch on the 2.1 line, the rest of DAR-BE-166 (rule batches are released once run; matched pairs come in bounded partitions).
Aditi, 2026-10-30: "fix the compare ingest and create a hotfix", then "Watch a retry live". Cut in
`backend-worktrees/release-2.1.2`, detached at `v2.1.8`, with two cherry-picks from `main`.
Aditi asked "are you sure this will fix the issue"; it was cut only after the end-to-end run below passed.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-166.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-166 in the backlog.
- Deferred issues moved to the next release: none.

## Cut (2026-10-30)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.9` | the version commit on `32ef412` |
| `drpn-ai/darpan-docker-config` | `v2.1.9` | the prod pin commit |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (none beyond memory).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: not applicable; no seed record changed.
- Candidate diff reviewed: yes; none (`upgrade-data-review.md`).
- Final load path decided: nothing to load; `data/upgrade-data.xml` holds no records.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.8/upgrade-data.xml` is byte-identical
  to `v2.1.8`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete (focused, JDK 21, fresh result XMLs on the release line, 76 tests): `RuleEngineServiceSmokeTests` 4/4, `JsonRecordLinesTests` 6/6
  (incl. a one-partition frame coming back in partitions of at most 2,000, every row kept), `CompareDatasetJsonFrameTests`
  5/5, `RuleSetCompareSourceCompositeKeyTests` 17/17, `EvaluationFindingRowsTests` 7/7, `RuleSetCompareScopeServiceSmokeTests`
  19/19, `DuplicateKeySplitTests` 5/5, `RunRetrySmokeTests` 5/5, `FacadeContractSnapshotTests` 1/1, `ApiContractGeneratorTests`
  7/7 after `generateApiContract` (version line only).
- Live evidence (sm-darpan 2.1.8, run 100919): heap 36% -> 96% in six seconds while one Spark task wrote the pairs as JSON.
- End to end on this release line (a temporary probe, not committed): the whole run#SavedRunDiff on SM-sized extracts with
  SM's two rules and Spark coalescing forced, SUCCESS, 5,111 differences, ~29 s, at 2 GB and 768 MB heaps; the same
  probe on 2.1.8 code ran out of heap at 2 GB (heap histogram: 1.1 GB HashMap tables, 792k LazyMaps, rule consequence).
- UI checks complete: not applicable; no UI change.
- Live/deployed smoke coverage noted: none before deploy; the retry of Steve Madden run 100919 follows it.
- Unverified items called out: the verify passes and CONCLUDE against live systems on sm-darpan (the probe's scope has no
  conclusion rules and its sources are unreachable).

## Approval

- Release owner sign-off: Aditi, "fix the compare ingest and create a hotfix" (2026-10-30).
- Cut/tag blocked until this checklist is fully complete: yes.
