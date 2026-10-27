# Release Checklist For Darpan 2.1.1

## Scope

Patch on the 2.1 line, tagging every component's `main` head as of 2026-10-27 16:33 IST (Aditi: "Tag current main as
2.1.1"). Prod has run main-tracking builds (docker-config `v0.0.3`) since 2.1.0; this tag set makes the prod image
reproducible again. The cut ran in an isolated tree (`backend-worktrees/release-2.1.1`, darpan-ui worktree
`ui-worktrees/release-3.1.1`) so no session's uncommitted work rides along.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-ADMIN-006, 007, 008; DAR-BE-107, 108, 135, 136, 137, 138, 139, 140, 142; DAR-UI-082, 083,
  084, 085; DAR-CLIENT-005; DAR-DOCS-004, 005.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; scope is "every main head", recorded in the backlog.
- Deferred issues moved to the next release: darpan-admin-ui versioning; `darpan-cli` (DAR-CLI-001, 002).

## Cut (2026-10-27)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.1` | the release-pack commit on `0174765` (component 2.1.1, contract version 2.1.1) |
| `drpn-ai/darpan-ui` | `v3.1.1` | the version commit on `b870632` (package 3.1.1) |
| `drpn-ai/darpan-hotwax` | `v0.10.1` | the version commit on `e1e8e68` |
| `drpn-ai/netsuite-darpan` | `v0.4.1` | the version commit on `ef47428` |
| `drpn-ai/shopify-darpan` | `v0.8.1` | the version commit on `c922ce0` |
| `drpn-ai/database-darpan` | `v0.2.2` | the version commit on `8a68d8f` |
| `drpn-ai/moqui-gql` | `v0.1.1` | the version commit on `cfa8295` |
| `drpn-ai/darpan-docker-config` | `v2.1.1` | the prod pin commit on `2960a08` |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (retry from extracts, connector key repair, page retry, minted ids, data load).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes; all 5 records live in
  `SecuritySeedData.xml` and `SourceSystemConnectorSeedData.xml`.
- Candidate diff reviewed: yes; 167 generator candidates split into 5 `darpan-seed` records (the load target) and the
  `RailsAuditData.xml` `darpan-tenant-config` records, excluded by design.
- Final load path decided: `./gradlew loadDarpanUpgradeData`, every instance (prod, UAT, sm-darpan).
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.0/upgrade-data.xml` is byte-identical
  to `v2.1.0`'s `data/upgrade-data.xml`.

## Deploy and data, in order

1. Build the prod image from docker-config `v2.1.1` and start it (add-missing creates the new tables and columns).
2. `./gradlew loadDarpanUpgradeData` on every instance, then `run#PendingMigrations`.
3. Deploy darpan-ui `v3.1.1` to each instance's hosting with its backend.
4. Smoke: a password reset; the audit page during a play; a HotWax orders read; on a failed window with finished reads,
   `retry#AutomationExecutionFromExtracts`.

## Verification

- Backend checks complete: focused, on the tagged heads, JDK 21.0.11, Gradle exit 0, results read from JUnit XML:
  `TenantOwnedEntityFilterContractTests` 5/5, `AuditCatalogContractTests` 5/5, `SourceFilterEntityContractTests` 4/4,
  `ApiContractGeneratorTests` 7/7, `FacadeContractSnapshotTests` 1/1. darpan-hotwax, netsuite-darpan, shopify-darpan,
  database-darpan, moqui-gql: `compileTestGroovy` executed, exit 0, against darpan `0174765`.
- UI checks complete: version-only change (`package.json`, the two root keys of `package-lock.json`); UI code was
  tested by its own commits; no UI suite run for the cut.
- Live/deployed smoke coverage noted: none for this cut; prod already runs most of this code from `main` builds.
- Unverified items called out: full suites (standing instruction: focused tests only); no upgrade-data load on a 2.1.0
  database; no browser check; `verify_release_pins.py` online runs after the tags are pushed.

## Approval

- Release owner sign-off: Aditi, "create a hot fix tag", scope "Tag current main as 2.1.1" (2026-10-27).
- Cut/tag blocked until this checklist is fully complete: yes; `verify_release_pins.py` exit 0 offline before tagging,
  online after the push.
