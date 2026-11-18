# Release Checklist For Darpan 3.2.0

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-292 (seed text), DAR-BE-295, DAR-BE-296, DAR-BE-297,
  DAR-BE-298, DAR-BE-299, DAR-BE-300, DAR-BE-301, DAR-UI-119
- Open release issues: 0
- Scope changes recorded on the backlog items: Aditi asked for a hotfix (2026-11-18 13:28 IST) and chose 3.2.0 minor
  and "everything on main" once the added methods, tables and job were named.
- Deferred items named and marked on the backlog: tenant-admin role policy still counts disabled tenants
  (DAR-BE-297); unusable code field after the "Turn on two-factor" refusal (DAR-BE-295); a browser approval for each
  admin action (DAR-BE-295 follow-up); DAR-ADMIN-012 CRM (uncommitted, kept out of the cut).

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes in darpan-docs `releases/updates.mdx`
  (e41e6d7).
- Operator-visible changes reviewed: yes, including the deploy order (backend before darpan-ui), the darpan-admin
  device targets and the gates before the everyone date.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, one per tagged repo.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes, all 4 records come from `data/*.xml`
  (`v3.1.0..HEAD`), generated in a clean worktree of HEAD.
- Candidate diff reviewed: yes. `DARPAN_TENANT_USER` description, `EVERYONE` policy row, `DARPAN_2FA_REMINDER`
  template, `send_TwoFactorReminders_daily` job; no parent/child order between them.
- Final load path decided: first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y`; otherwise
  `loadDarpanUpgradeData` once.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.1.0/upgrade-data.xml`, byte-identical
  to `v3.1.0`'s (unchanged).
- Uncommitted CRM rows (DAR-ADMIN-012) stay in the working copy of `data/upgrade-data.xml`, appended after the 3.2.0
  records, and are not in the release commit.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes, 9 rows plus the developers-site sections.
- `verify_release_docs.py` exit status (before tagging), offline, 2026-11-18: release notes, graphql log, public
  openapi (3.2.0), runtime stack, docs impact (13 page references) PASS. "service catalog" FAILs only because it reads
  the working-tree contract, which carries the CRM session's 28 uncommitted `admin.CrmAdminServices.*` methods; against
  the release's committed methods.txt `check_service_catalog.py` reports 268 of 268. "published" fails for both docs
  repos until they are pushed. GraphQL pages: none, schema unchanged.
- Docs repos pushed (Mintlify publishes from `main`): no, awaiting Aditi: darpan-docs e41e6d7, darpan-developers
  bbd92bc.
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`) owed: yes.

## Verification

- Backend checks complete: yes. JDK 21, `--rerun`, fresh result XMLs, 2026-11-18 13:3x IST, 251 tests:
  ApiContractGeneratorTests 8/8, FacadeContractSnapshotTests 1/1, TenantOwnedEntityFilterContractTests 5/5,
  DisableAuthzRatchetTest 2/2, RpcMethodFenceTests 23/23, TenantAccessSupportTests 43/43, CliLoginSmokeTests 28/28,
  CliAuthSupportTests 19/19, TwoFactorSignInSmokeTests 38/38, PasskeySignInSmokeTests 27/27,
  TwoFactorReminderSmokeTests 6/6, AgentAccessSmokeTests 10/10, EmailTemplatesRenderTests 7/7,
  DiffDetailClassifierTests 26/26. CliMethodAllowlistTests 4/6: both failures list only the CRM session's
  uncommitted `admin.CrmAdminServices.*` methods from the working-tree contract; all 268 methods of the release's
  committed contract are placed in the allowlist (checked).
- Contract: `check_contract_compat.py` 3.1.0 -> 3.2.0 compatible, 2 added, 266 preserved, version 4. The version
  line moved to 3.2.0 in darpan, darpan-ui and darpan-admin-ui (admin-ui catches up from 3.0.3), committed without
  the CRM working-tree methods.
- UI checks complete: darpan-ui `npm run type-check` exit 0; vitest `contractDrift` + `registryContract` 20/20.
- Deploy pins: `verify_release_pins.py` to run after the tags are pushed; docker-config moves darpan v3.2.0,
  darpan-hotwax v1.2.0, darpan-agent v0.3.0.
- Live/deployed smoke coverage noted: release-notes "Operator actions after deploy".
- Unverified items called out: the full suites were not run (focused-tests rule); the upgrade file was not loaded into
  an empty database; passkey-only sign-in and the darpan-admin browser approval in a real browser; the reminder job on
  a deployed scheduler; the GraphQL SDL against prod.

## Approval

- Release owner sign-off: pending Aditi.
- Cut/tag blocked until this checklist is fully complete: yes; tags, pushes and GitHub releases wait for Aditi.
