# Release Checklist For Darpan 3.0.2

## Scope

Hotfix on the 3.0 line, cut from `main` in every repo with commits since 3.0.0 (darpan, darpan-ui, darpan-hotwax,
shopify-darpan, netsuite-darpan, darpan-agent) plus darpan-docker-config's pins.
Aditi, 2026-11-12 18:03 IST: "create a hotfix and deploy it... include all changes and push anything on local".
Every repo's local `main` matched `origin/main` before the cut (nothing unpushed); worktree branches ahead of `main`
are superseded DAR-BE-159 branches and the SM `v3.1.4` release worktree, not release content.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-207, DAR-BE-245, DAR-BE-246, DAR-BE-248, DAR-BE-250, DAR-BE-251, DAR-UI-111, DAR-BE-244 (docker-config parameter).
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on each backlog item.
- Deferred issues moved to the next release: none; post-deploy steps stay on their items (release-notes.md).

## Cut (2026-11-12)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.0.2` | the version commit on `85ed44c3` |
| `drpn-ai/darpan-ui` | `v4.0.2` | the version commit on `fe2d5e9` |
| `drpn-ai/darpan-hotwax` | `v1.0.1` | the version commit on `6c230e1` |
| `drpn-ai/shopify-darpan` | `v0.9.1` | the version commit on `0624393` |
| `drpn-ai/netsuite-darpan` | `v0.5.1` | the version commit on `eb361e3` |
| `drpn-ai/darpan-agent` | `v0.1.2` | the version commit on `882d3b7` |
| `drpn-ai/darpan-docker-config` | `v3.0.2` | the prod pin commit on `ef08069` |
| `drpn-ai/database-darpan`, `drpn-ai/moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (new column, Haiku call per run update, AgentEnabled parameter).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: not applicable; no seed record changed.
- Candidate diff reviewed: yes; none (`upgrade-data-review.md`; `git diff v3.0.0..` touches no `data/` seed file).
- Final load path decided: nothing to load; `data/upgrade-data.xml` holds no records.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/3.0.0/upgrade-data.xml` is
  byte-identical to `v3.0.0`'s `data/upgrade-data.xml`.

## Contract

- `docs/api-contract/openapi.json`: version line only (3.0.0 -> 3.0.2) from `generateApiContract`; `methods.txt`
  unchanged (243). darpan-ui's copy updated to match.
- `facade-contract.snapshot.txt`: unchanged; `FacadeContractSnapshotTests` green without regeneration.

## Verification

- Backend checks complete (focused, JDK 21, `--rerun --continue`, fresh non-empty result XMLs 2026-11-12 ~18:07 IST,
  362 tests, 0 failures, 1 skipped): darpan `AgentAccessAdminSmokeTests` 8, `AgentAccessSmokeTests` 8,
  `AuditReportAssemblerSmokeTests` 25, `AuditRunFactsTests` 7, `AuditRunUpdateSmokeTests` 10, `OrderWorldsTests` 6,
  `RunNotificationVoiceTests` 81, `TenantNotificationServiceSmokeTests` 11, `TenantOwnedEntityFilterContractTests` 5,
  `ApiContractGeneratorTests` 8, `FacadeContractSnapshotTests` 1; darpan-hotwax `OmsGqlOrderGrainAssemblerTests` 18,
  `OmsGqlQueriesTests` 24, `OmsOrderStatusLookupTests` 7; shopify-darpan `ShopifySourceCatalogAndQueryBuilderTests` 21,
  `ShopifyOrderPaymentSignalTests` 20; netsuite-darpan `NsShipGroupCountSupportTest` 7, `NsSuiteQlOrderSupportTest` 36;
  darpan-agent `AgentFacadeSmokeTests` 8, `AgentOrchestratorTests` 42 (1 skipped, the private leak list),
  `AuditRunWriterTests` 9. Classes chosen: every test class changed in each repo's range, plus both contract tripwires.
- UI checks complete: type-check exit 0; eslint on the 8 changed files exit 0; vitest `AuditReportPage`,
  `AuditSettingsPage`, the 6 mascot specs and `registryContract` 151/151.
- Not run: the full backend and UI suites (focused-tests rule); a real Haiku call.
- Live/deployed smoke coverage noted: prod health and version after deploy; the post-deploy steps in `release-notes.md`.
- Unverified items called out: the DAR-UI-111 chat space picker and DAR-BE-250's board in a browser.

## Approval

- Release owner sign-off: Aditi, "create a hotfix and deploy it" (2026-11-12).
- Cut/tag blocked until this checklist is fully complete: yes.
