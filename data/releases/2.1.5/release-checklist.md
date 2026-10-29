# Release Checklist For Darpan 2.1.5

## Scope

Patch on the 2.1 line for DAR-BE-153's reversed transaction id check (Aditi, 2026-10-29: "yes" to pushing and
cutting 2.1.5, with DAR-BE-157 / DAR-BE-151 / DAR-BE-142 riding along from `main`). Cut in
`backend-worktrees/release-2.1.2` from `origin/main`. darpan-hotwax and netsuite-darpan are re-cut because darpan's
`OMS_PRODUCTS_GQL` connector and gorjana R22 need their DAR-BE-157 commits.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-153, DAR-BE-157, DAR-BE-151, DAR-BE-142.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-153 in the backlog.
- Deferred issues moved to the next release: none.

## Cut (2026-10-29)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.5` | the version commit on `504bbcb` |
| `drpn-ai/shopify-darpan` | `v0.8.4` | the version commit on `3326545` |
| `drpn-ai/darpan-hotwax` | `v0.10.4` | the version commit on `5322d3b` |
| `drpn-ai/netsuite-darpan` | `v0.4.3` | the version commit on `cd3fdb3` |
| `drpn-ai/darpan-docker-config` | `v2.1.5` | the prod pin commit |
| `drpn-ai/darpan-ui`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (Shopify transactionIds, HotWax products endpoint, GraphQL retry).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes; `DarpanSystemSourceSeedData.xml`,
  `SourceSystemConnectorSeedData.xml`, `SourceSystemConnectorFieldSeedData.xml`.
- Candidate diff reviewed: yes; 14 `darpan-seed` records kept, tenant config excluded (`upgrade-data-review.md`).
- Final load path decided: load `data/upgrade-data.xml` (parent before child).
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.4/upgrade-data.xml` is byte-identical
  to `v2.1.4`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete (focused, JDK 21, fresh result XMLs): shopify-darpan `ShopifyOrderPaymentSignalTests` 20/20
  (2 new, red first), `ShopifyExtractScriptCharacterizationTests` 19/19, `ShopifySourceCatalogAndQueryBuilderTests`
  20/20; darpan `SourceSystemConnectorSupportSmokeTests` 49/49, `FacadeContractSnapshotTests` 1/1,
  `ApiContractGeneratorTests` 7/7 after `generateApiContract` (the `openapi.json` version line only); darpan-hotwax
  `*OmsGql*` 200/205, the 5 red are the known stale upgrade-pack assertions
  (`OmsGqlReturnsConnectorWiringTests` x3, `OmsGqlOrderItemsConnectorWiringTests` x2: they expect earlier releases'
  records in the current `upgrade-data.xml`, and failed the same way on 2.1.3 and 2.1.4).
- Replay: the new rule over Steve Madden run `20261028-093359085`'s 77 two-sided rows, 77/77 pass.
- UI checks complete: not applicable.
- Live/deployed smoke coverage noted: none before deploy; the Steve Madden test run follows it.
- Unverified items called out: a live Shopify read carrying `transactionIds`; netsuite-darpan suite not rerun for the
  version-only commit (its DAR-BE-157 session ran 103/103 after rebase).

## Approval

- Release owner sign-off: Aditi, "yes" (2026-10-29).
- Cut/tag blocked until this checklist is fully complete: yes.
