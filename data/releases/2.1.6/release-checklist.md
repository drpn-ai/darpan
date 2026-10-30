# Release Checklist For Darpan 2.1.6

## Scope

Patch on the 2.1 line for DAR-BE-162 (Shopify extract streams) and DAR-BE-163 (retry from the failed step).
Aditi, 2026-10-30: "push and create a hotfix", then "Patch line: only 162+163". Cut in
`backend-worktrees/release-2.1.2` and `ui-worktrees/release-3.1.2`, detached at the 2.1.5 / 0.8.4 / 3.1.2 tags, with
cherry-picks from `main`.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-162, DAR-BE-163, DAR-BE-156 (shopify-darpan half only, inert).
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-162 and DAR-BE-163 in the backlog.
- Deferred issues moved to the next release: DAR-BE-156 (darpan half), DAR-BE-157, DAR-BE-160, DAR-BE-161 stay on `main`.

## Cut (2026-10-30)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.6` | the version commit on `c476d14` |
| `drpn-ai/shopify-darpan` | `v0.8.5` | the version commit on `201a214` |
| `drpn-ai/darpan-ui` | `v3.1.3` | the version commit on `40e38c1` |
| `drpn-ai/darpan-docker-config` | `v2.1.6` | the prod pin commit |
| `drpn-ai/darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (Shopify read to disk, retry parameter).
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
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.5/upgrade-data.xml` is byte-identical
  to `v2.1.5`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete (focused, JDK 21, fresh result XMLs on the release line): shopify-darpan `shopify.graphql.*`
  and `shopify.reconciliation.automation.*` 166/166 in 10 classes, 0 skipped; darpan `RunRetrySmokeTests` 5/5,
  `ExtractReplaySupportTests` 4/4, `FacadeContractSnapshotTests` 1/1 (snapshot: `retryFromRunResultId` only),
  `ApiContractGeneratorTests` 7/7 after `generateApiContract` (version line only), `TenantPinBesidePrimaryKeyGuardTest`
  2/2, `DisableAuthzRatchetTest` 2/2.
- UI checks complete: `vue-tsc` clean, ESLint clean on the changed files, vitest `useRunRetry` 4/4,
  `ReconciliationRunResultPage` 50/50, `ReconciliationDiffPage` 25/25.
- Live/deployed smoke coverage noted: none before deploy; the sm-darpan retry of run 100766 follows it.
- Unverified items called out: a month-wide Shopify read on a 2 GB JVM; the Retry button in a browser; heap through
  COMPARE / verify / CONCLUDE on 125k-record extracts.

## Approval

- Release owner sign-off: Aditi, "push and create a hotfix" (2026-10-30).
- Cut/tag blocked until this checklist is fully complete: yes.
