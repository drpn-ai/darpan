# Release Checklist For Darpan 2.1.8

## Scope

Patch on the 2.1 line for DAR-BE-166 (the compare reads a JSON extract's records one per line). Aditi, 2026-10-30:
"fix the compare ingest and create a hotfix". Cut in `backend-worktrees/release-2.1.2`, detached at `v2.1.7`, with one
cherry-pick from `main`.

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
| `drpn-ai/darpan` | `v2.1.8` | the version commit on `dc65032` |
| `drpn-ai/darpan-docker-config` | `v2.1.8` | the prod pin commit |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (the `.records.jsonl` beside each compared JSON extract).
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
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.7/upgrade-data.xml` is byte-identical
  to `v2.1.7`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete (focused, JDK 21, fresh result XMLs on the release line, 71 tests): `JsonRecordLinesTests` 5/5,
  `CompareDatasetJsonFrameTests` 5/5, `RuleSetCompareSourceCompositeKeyTests` 17/17, `EvaluationFindingRowsTests` 7/7,
  `RuleSetCompareScopeServiceSmokeTests` 19/19, `DuplicateKeySplitTests` 5/5, `RunRetrySmokeTests` 5/5,
  `FacadeContractSnapshotTests` 1/1, `ApiContractGeneratorTests` 7/7 after `generateApiContract` (version line only).
- Reproduction (a temporary probe, not committed, in the 512 MB test JVM on SM-shaped extracts): before, OutOfMemoryError
  building the matched pairs, twice; after, all 125,602 pairs built and read through the rule batches in 22 s.
- UI checks complete: not applicable; no UI change.
- Live/deployed smoke coverage noted: none before deploy; the retry of Steve Madden run 100867 follows it.
- Unverified items called out: the verify passes and CONCLUDE on 125k-record extracts on sm-darpan.

## Approval

- Release owner sign-off: Aditi, "fix the compare ingest and create a hotfix" (2026-10-30).
- Cut/tag blocked until this checklist is fully complete: yes.
