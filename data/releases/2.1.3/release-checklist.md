# Release Checklist For Darpan 2.1.3

## Scope

Patch on the 2.1 line so Steve Madden's daily order run can read HotWax over GraphQL with its POS exclusion (Aditi,
2026-10-28: "Hotfix 2.1.3", then "yes push when green"). Cut in the isolated tree `backend-worktrees/release-2.1.2`
from `origin/main`, so no session's uncommitted or unpushed work rides along; the two DAR-BE-155 commits and
darpan-hotwax `32e1b5c`, already on `origin/main`, do.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-153, DAR-BE-155.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-153 in the backlog.
- Deferred issues moved to the next release: none; the Steve Madden run change is configuration after deploy.

## Cut (2026-10-28)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.3` | the release-pack commit on `3daa790` (component 2.1.3) |
| `drpn-ai/darpan-hotwax` | `v0.10.3` | `02004ab` on `ec09b77` |
| `drpn-ai/darpan-docker-config` | `v2.1.3` | `01b7cb6` on `267f8ab` |
| `drpn-ai/darpan-ui` | not re-cut | `v3.1.2` stays |
| `drpn-ai/shopify-darpan`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (GraphQL sales channel filter, leaner transaction id read, data load).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes; the one record lives in
  `SourceSystemConnectorFieldSeedData.xml`.
- Candidate diff reviewed: yes; 1 candidate, 1 record.
- Final load path decided: `./gradlew loadDarpanUpgradeData` on each instance that takes 2.1.3 (sm-darpan first).
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.2/upgrade-data.xml` is byte-identical
  to `v2.1.2`'s `data/upgrade-data.xml`.

## Deploy and data, in order

1. sm-darpan: build from docker-config `v2.1.3` (Jenkins), with `loadDarpanUpgradeData`.
2. Move `RS_PROD_ORDERS` HotWax to `OMS_GQL` keeping the POS exclusion, add the two rules, test-run, sync
   automation 100000.

## Verification

- Backend checks complete: focused, JDK 21, exit status read directly, counts from fresh JUnit XML. darpan-hotwax
  `OmsGql*`: 158 tests, 152 pass; the 6 failing are identical on the `v0.10.2` baseline
  (`OmsGqlOrderItemsConnectorWiringTests` 2, `OmsGqlReturnsConnectorWiringTests` 3 read older releases' rows from
  `upgrade-data.xml`; `OmsGqlThrottleGovernorTests` 1 is a 1 ms rounding). `OmsGqlOrderPageSourceTests` 28/28,
  `OmsGqlConnectorWiringTests` 10/10, `OmsGqlQueriesTests` 18/18. darpan: `FacadeContractSnapshotTests` 1/1 (no
  regeneration needed), `SourceSystemConnectorSupportSmokeTests` 48/48, `ApiContractGeneratorTests` 7/7 after
  `generateApiContract` (diff: the `openapi.json` version line only).
- UI checks complete: not applicable; no UI change and darpan-ui is not re-cut.
- Live/deployed smoke coverage noted: none before deploy; the first live read is the Steve Madden test run.
- Unverified items called out: full suites (standing instruction: focused tests only); no upgrade-data load on a 2.1.2
  database; `verify_release_pins.py` online runs after the tags are pushed.

## Approval

- Release owner sign-off: Aditi, "Hotfix 2.1.3" and "yes push when green" (2026-10-28).
- Cut/tag blocked until this checklist is fully complete: yes.
