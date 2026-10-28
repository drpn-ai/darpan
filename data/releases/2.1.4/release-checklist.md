# Release Checklist For Darpan 2.1.4

## Scope

Patch on the 2.1 line for one shopify-darpan fix (Aditi, 2026-10-28: "Fulfillment only, fix void": cut it as a hotfix
for SM and prod). Cut in `backend-worktrees/release-2.1.2` from `origin/main`; nothing else is on origin since 2.1.3.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-153.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-153 in the backlog.
- Deferred issues moved to the next release: none.

## Cut (2026-10-28)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.4` | the version commit on `05835c3` |
| `drpn-ai/shopify-darpan` | `v0.8.3` | the version commit on `1a9d701` |
| `drpn-ai/darpan-docker-config` | `v2.1.4` | the prod pin commit on `01b7cb6` |
| `drpn-ai/darpan-hotwax`, `darpan-ui`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (Shopify paymentTransactionIds).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: not applicable; no record.
- Candidate diff reviewed: yes; 0 candidates.
- Final load path decided: none needed; `loadDarpanUpgradeData` is a no-op.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.3/upgrade-data.xml` is byte-identical
  to `v2.1.3`'s `data/upgrade-data.xml`.

## Verification

- Backend checks complete: shopify-darpan component suite 246/247, the one failure identical on `v0.8.2` content
  (`ShopifyOrderLineUnitSupportTests.rejectedUnitsAreCollectedForTheConcludePass`); new test red before the fix;
  `ShopifyOrderPaymentSignalTests` 19/19; darpan `ApiContractGeneratorTests` 7/7.
- UI checks complete: not applicable.
- Live/deployed smoke coverage noted: none before deploy; the Steve Madden test run follows it.
- Unverified items called out: a live Shopify read with the fix; `verify_release_pins.py` online after the push.

## Approval

- Release owner sign-off: Aditi, "Fulfillment only, fix void (Recommended)" (2026-10-28).
- Cut/tag blocked until this checklist is fully complete: yes.
