# Release Checklist For Darpan 2.1.10

## Scope

Patch on the 2.1 line, DAR-BE-168 (a conclusion tree saves on a compare scope with no `scopeMode`).
Aditi, 2026-10-31: "push to SM prod", then "push this audit to prod" (the Steve Madden order sync audit, DAR-CLIENT-006).
Cut in `backend-worktrees/release-2.1.2`, detached at `v2.1.9`, with one cherry-pick from `main`.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-168.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-168 in the backlog.
- Deferred issues moved to the next release: none.

## Cut (2026-10-31)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.10` | the version commit on `ce94a10` |
| `drpn-ai/darpan-docker-config` | `v2.1.10` | the prod pin commit |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (none).
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
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.9/upgrade-data.xml` is byte-identical
  to `v2.1.9`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete (focused, JDK 21, `--rerun`, fresh result XMLs on the release line 2026-10-31 04:38-04:44 UTC,
  14 tests): `RunConclusionAuthoringSmokeTests` 6/6 (incl. `aScopeWithNoModeIsATwoSystemRun`, which failed first on
  `main` with sm-darpan's exact error), `ApiContractGeneratorTests` 7/7 after `generateApiContract` (version line only),
  `FacadeContractSnapshotTests` 1/1.
- UI checks complete: not applicable; no UI change.
- Live/deployed smoke coverage noted: none before deploy; saving the order sync reasons on sm-darpan follows it.
- Unverified items called out: the save against sm-darpan's live `RS_PROD_ORDERS` scope until deployed.

## Approval

- Release owner sign-off: Aditi, "push to SM prod" (2026-10-31).
- Cut/tag blocked until this checklist is fully complete: yes.
