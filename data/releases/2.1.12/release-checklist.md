# Release Checklist For Darpan 2.1.12

## Scope

Hotfix on the 2.1 line for sm-darpan: DAR-BE-178 (an audit can play the month that just ended; SM's
`order-sync-month` could not play October on Nov 2).
Aditi, 2026-11-02: "cut a hot fix for sm, i will deploy main to prod".
Cut in `backend-worktrees/rel-2.1.12` (APFS clone, darpan detached at `v2.1.11`), with one cherry-pick from `main`.
Every other component in the clone was set to its `docker/prod/Dockerfile` pin before testing (darpan-hotwax v0.10.4,
shopify-darpan v0.8.5, netsuite-darpan v0.4.3, database-darpan v0.2.2, moqui-gql v0.1.1, moqui-sftp v1.0.3).

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; no item of its own).
- Included issue IDs: DAR-BE-178.
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on DAR-BE-178 in the backlog.
- Deferred issues moved to the next release: DAR-BE-178 phase 2 (not built).

## Cut (2026-11-02)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.12` | the version commit on the cherry-pick |
| `drpn-ai/darpan-docker-config` | `v2.1.12` | the prod pin commit |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | not re-cut | no commits |

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (`playableFrom`, `run#Audit` and the ledger accept the period just ended).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` References.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: not applicable; no seed record changed.
- Candidate diff reviewed: yes; none (`upgrade-data-review.md`; `git diff v2.1.11..` touches no `data/` or `entity/`).
- Final load path decided: nothing to load; `data/upgrade-data.xml` holds no records.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/2.1.11/upgrade-data.xml` is
  byte-identical to `v2.1.11`'s `data/upgrade-data.xml`.

## Contract

- `docs/api-contract/openapi.json`: version line only (2.1.11 -> 2.1.12), set by hand. `generateApiContract` in the
  clone first picked up `usedTransactionTypes` from the working copy's netsuite-darpan (6 commits past the pinned
  v0.4.3, not in it); that output was discarded. `ApiContractGeneratorTests` then ran against the pinned components.
- `facade-contract.snapshot.txt`: unchanged (no field change; DAR-BE-178 rewords two descriptions only).

## Verification

- Backend checks complete (focused, JDK 21, `--offline --rerun`, fresh result XMLs on the release line 2026-11-02
  06:26 UTC, 68 tests, 0 failures, 0 skipped): `AuditAutomationSmokeTests` 22/22, `AuditReportAssemblerSmokeTests`
  25/25, `AuditSettingsSmokeTests` 13/13, `ApiContractGeneratorTests` 7/7, `FacadeContractSnapshotTests` 1/1.
- UI checks complete: not applicable; no UI change ships.
- Live/deployed smoke coverage noted: none before deploy; the sm-darpan step in `release-notes.md` follows it.
- Unverified items called out: Previous Month on sm-darpan until deployed.

## Approval

- Release owner sign-off: Aditi, "cut a hot fix for sm" (2026-11-02).
- Cut/tag blocked until this checklist is fully complete: yes.
