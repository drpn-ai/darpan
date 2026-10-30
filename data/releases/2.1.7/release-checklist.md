# Release Checklist For Darpan 2.1.7

## Scope

Patch on the 2.1 line for DAR-BE-165 (a repeated key is a finding, never a failed compare). Aditi, 2026-10-30: "we
need to fix darpan.. the person should be able to see the results on Darpan", continuing the 2.1.6 hotfix ("push and
create a hotfix"). Cut in `backend-worktrees/release-2.1.2`, detached at `v2.1.6`, with one cherry-pick from `main`.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-165.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-165 in the backlog.
- Deferred issues moved to the next release: removing the repeated-key choice and the `duplicateKeyHandling` column.

## Cut (2026-10-30)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.7` | the version commit on `19ac854` |
| `drpn-ai/darpan-docker-config` | `v2.1.7` | the prod pin commit |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (repeated keys reported; the repeated-key choice is inert).
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
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.6/upgrade-data.xml` is byte-identical
  to `v2.1.6`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete (focused, JDK 21, fresh result XMLs on the release line): `RuleSetCompareScopeServiceSmokeTests`
  19/19, `DuplicateKeySplitTests` 5/5, `Step3RealHarnessDenialTests` 4/4, `RunRetrySmokeTests` 5/5,
  `FacadeContractSnapshotTests` 1/1, `ApiContractGeneratorTests` 7/7 after `generateApiContract` (version line only).
- UI checks complete: not applicable; no UI change.
- Live/deployed smoke coverage noted: none before deploy; the retry of Steve Madden run 100816 follows it.
- Unverified items called out: heap through the verify passes and CONCLUDE on 125k-record extracts.

## Approval

- Release owner sign-off: Aditi, "we need to fix darpan.. the person should be able to see the results on Darpan" (2026-10-30).
- Cut/tag blocked until this checklist is fully complete: yes.
