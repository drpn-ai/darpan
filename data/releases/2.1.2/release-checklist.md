# Release Checklist For Darpan 2.1.2

## Scope

Patch on the 2.1 line, tagging every component's `main` head as of 2026-10-28 12:45 IST (Aditi: "we need a new hot
fix... in order for this to reach SM prod", scope "Tag current main", then "push the tags"). sm-darpan runs 2.1.1-era
code; api.drpn.ai already runs most of this from main builds. The cut ran in an isolated tree
(`backend-worktrees/release-2.1.2`, darpan-ui worktree `ui-worktrees/release-3.1.2`), cut from `origin/main`, so no
session's uncommitted or unpushed work rides along (two local DAR-BE-151 commits in the shared darpan and
darpan-hotwax trees are not in it).

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-141, 142, 143, 144, 145, 146, 147, 148, 149, 153; DAR-ADMIN-008, 009; DAR-UI-076, 086,
  087, 088, 089, 090, 091; DAR-CLIENT-005.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; scope is "every main head", recorded in the backlog.
- Deferred issues moved to the next release: darpan-admin-ui versioning; `darpan-cli`; DAR-BE-151 (unpushed).

## Cut (2026-10-28)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.2` | the release-pack commit on `f4ba9e1` (component 2.1.2, contract version 2.1.2) |
| `drpn-ai/darpan-ui` | `v3.1.2` | the version commit on `727ef84` (package 3.1.2) |
| `drpn-ai/darpan-hotwax` | `v0.10.2` | the version commit on `7252d38` |
| `drpn-ai/netsuite-darpan` | `v0.4.2` | the version commit on `4c92f22` |
| `drpn-ai/shopify-darpan` | `v0.8.2` | the version commit on `4d31bd2` |
| `drpn-ai/database-darpan` | not re-cut | `v0.2.2` stays (no commits) |
| `drpn-ai/moqui-gql` | not re-cut | `v0.1.1` stays (no commits) |
| `drpn-ai/darpan-docker-config` | `v2.1.2` | the prod pin commit on `2d6530c` |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (run admission, retry from last read, go-live, payment transaction ids, data
  load and migration).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes; all 3 records live in
  `MigrationRegistrySeedData.xml` and `SourceSystemConnectorFieldSeedData.xml`.
- Candidate diff reviewed: yes; 121 generator candidates split into 3 `darpan-seed` records (the load target) and 118
  `darpan-tenant-config` records (gorjana R21, RAILS R7), excluded by design.
- Final load path decided: `./gradlew loadDarpanUpgradeData`, every instance (prod, UAT, sm-darpan), then
  `run#PendingMigrations`.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.1/upgrade-data.xml` is byte-identical
  to `v2.1.1`'s `data/upgrade-data.xml`.

## Deploy and data, in order

1. Build the prod image from docker-config `v2.1.2` and start it (add-missing creates `TenantSetting.goLiveDate`).
2. `./gradlew loadDarpanUpgradeData` on every instance, then `run#PendingMigrations` (runs `AUTOMATION_STREAKS`).
3. Deploy darpan-ui `v3.1.2` to each instance's hosting with its backend (sm-darpan: `hosting:sm-darpan` alone).
4. Smoke: a run page opening its new run; an audit page with folded causes; `run#PendingMigrations` reporting
   `AUTOMATION_STREAKS` done.

## Verification

- Backend checks complete: focused, on the tagged heads, JDK 21.0.11, Gradle exit status read directly, results read
  from fresh JUnit XML: `TenantOwnedEntityFilterContractTests` 5/5, `AuditCatalogContractTests` 5/5,
  `SourceFilterEntityContractTests` 4/4, `ApiContractGeneratorTests` 7/7, `RunFlowchartSchemaContractTests` 3/3,
  `AutomationEntityContractTests` 8/8, `FacadeContractSnapshotTests` 1/1 after regeneration (diff reviewed: exactly
  `retry#AuditCheck` and `get#ActiveRunResult` added, 133 -> 135 services). darpan-hotwax, netsuite-darpan,
  shopify-darpan, database-darpan, moqui-gql: `compileTestGroovy` against darpan `f4ba9e1`.
- UI checks complete: version and contract-copy change only (`package.json`, the two root keys of
  `package-lock.json`, `openapi.json` version line; `schema.d.ts` regenerated unchanged); UI code was tested by its
  own commits; no UI suite run for the cut.
- Live/deployed smoke coverage noted: none for this cut. DAR-BE-153's HotWax read ran live read-only on gorjana
  (212 orders, no errors); the Shopify bulk read with its new selections did not.
- Unverified items called out: full suites (standing instruction: focused tests only); no upgrade-data load on a 2.1.1
  database; no browser check; `verify_release_pins.py` online runs after the tags are pushed.

## Approval

- Release owner sign-off: Aditi, "we need a new hot fix", scope "Tag current main", "push the tags" (2026-10-28).
- Cut/tag blocked until this checklist is fully complete: yes; `verify_release_pins.py` exit 0 offline before tagging,
  online after the push.
