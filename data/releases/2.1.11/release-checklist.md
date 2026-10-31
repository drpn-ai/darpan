# Release Checklist For Darpan 2.1.11

## Scope

Patch on the 2.1 line: DAR-BE-172 (an audit's first play runs the automation it makes) and DAR-BE-174 (a conclusion
lookup can attach every record under a key; a run's lookups are set from the app).
Aditi, 2026-10-31: "push and cut 2.1.11".
Cut in `backend-worktrees/release-2.1.2`, on `v2.1.10`, with three cherry-picks from `main`.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-172, DAR-BE-174.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-172 and DAR-BE-174 in the backlog.
- Deferred issues moved to the next release: none.

## Cut (2026-10-31)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.11` | the version commit on the three cherry-picks |
| `drpn-ai/darpan-docker-config` | `v2.1.11` | the prod pin commit |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (`save#RunConclusions` takes `lookups`).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: not applicable; no seed record changed.
- Candidate diff reviewed: yes; none (`upgrade-data-review.md`; `git diff v2.1.10..` touches no `data/` or `entity/`).
- Final load path decided: nothing to load; `data/upgrade-data.xml` holds no records.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.10/upgrade-data.xml` is
  byte-identical to `v2.1.10`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete (focused, JDK 21, `--rerun`, fresh result XMLs on the release line 2026-10-31 12:09 UTC,
  104 tests, 0 failures): `AuditSettingsSmokeTests` 13/13, `ConclusionLookupSupportTests` 6/6,
  `RunConclusionAuthoringSmokeTests` 7/7, `RunConclusionSupportTests` 21/21, `ConclusionConditionSupportTests` 11/11,
  `darpan.conclusion.brain.*` 38/38, `ApiContractGeneratorTests` 7/7 after `generateApiContract`,
  `FacadeContractSnapshotTests` 1/1 (snapshot diff: one line, `save RunConclusions` IN `lookups`).
- UI checks complete: not applicable; no UI change.
- Live/deployed smoke coverage noted: none before deploy; the sm-darpan steps in `release-notes.md` follow it.
- Unverified items called out: a first play and a `many` lookup on sm-darpan until deployed.

## Approval

- Release owner sign-off: Aditi, "push and cut 2.1.11" (2026-10-31).
- Cut/tag blocked until this checklist is fully complete: yes.
