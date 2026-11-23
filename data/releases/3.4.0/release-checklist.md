# Release Checklist For Darpan 3.4.0

Prepared 2026-11-23, 11:03 to 12:00 IST, in a clean backend clone (`backend-worktrees/dar-be-328`, every component at
`origin/main`). Release freeze on every product `main` from 11:03 IST until the tags are pushed.

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-318 (plans 2, 3a, 3b-1, 3b-2a, 3b-2b, 3b-2c (added by Aditi
  11:25 IST) and the fact-conversion speed plan), DAR-BE-328, DAR-OPS-026, DAR-BE-320, DAR-BE-336, DAR-BE-192, DAR-BE-323, DAR-BE-325,
  DAR-BE-327, DAR-BE-332, DAR-CLI-007, DAR-UI-132, DAR-UI-134, DAR-UI-135, DAR-BE-301, DAR-CLIENT-006, DAR-BE-329,
  DAR-BE-142, DAR-BE-326, DAR-BE-337 (added by Aditi 11:46 IST), DAR-BE-334 and DAR-BE-335 (added 11:57 IST), DAR-UI-136 (added 12:27 IST); DAR-BE-319/321/322 already shipped in 3.3.1/3.3.2.
- Open release issues: 0
- Scope changes recorded: Aditi, 2026-11-23: 3b-2c in (11:25), DAR-BE-337 in (11:46), DAR-BE-334 in (11:57),
  contract version 5 but release stays 3.4.0 (12:18-12:20), DAR-UI-136 in UI 4.4.0 (12:27). Earlier, 10:14-10:23 IST: cut after DAR-BE-328 and the moqui-rules speed work
  pushed; tags and the UAT deploy approved; prod reads approved; prod go or no-go after the UAT smoke.
- Deferred items: `release-notes.md` "Deferred items" (DAR-BE-334 follow-ups).

## Release notes

- User-facing release notes drafted: `release-notes.md`; public notes in darpan-docs `releases/updates.mdx`
  (`## v3.4.0 — November 2026`) and `releases/roadmap.mdx`.
- Operator-visible changes reviewed: yes, with the deploy order, the extract purge, `run_DataRetention_daily`, the
  moqui-rules seed and the rollback set.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes
- Compare URLs captured: yes, one per tagged repo
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes. moqui-rules' seed rows were added as
  `data/MoquiRulesSeedData.xml` (`darpan-seed`, darpan `60e4c3b2`, drift guard `MoquiRulesSeedDataMirrorTests`).
- Candidate diff reviewed: yes, `upgrade-data-review.md`; 2,421 records; every hand-edit made to the rolling file
  since v3.3.2 is in the new pack or in a seed file loaded at boot (36 old conclusions-page mascot rows were retired
  from the seed by DAR-UI-135 and stay inert in prod).
- `release_preflight.py validate`: passes except one deliberate record, the hand row
  `DarpanMigration AUTOMATION_FILTERS enabled="N"` (3b-2c). It has no seed backing by design: the registry seed no
  longer states `enabled` (DAR-BE-319) and the row's service is deleted, so only the pack can switch it off.
- Final load path decided: first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y` (prod
  `UpgradeDataTypes=darpan-seed`); connector Keep lines come from the system repos' own `darpan-seed` files in the same
  load. Otherwise `./gradlew loadDarpanUpgradeData` once.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.3.2/upgrade-data.xml`, byte-identical
  to v3.3.2's current file (unchanged).

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes, except the GraphQL rows, which wait for the SDL
  from UAT on the release commit.
- `verify_release_docs.py` exit status (before tagging): run against UAT's SDL after the UAT deploy.
- Docs repos pushed (Mintlify publishes from `main`): at the prod deploy (darpan-docs 6 commits, darpan-developers
  4 commits, local).
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`) owed: yes.

## Verification

- Backend checks complete: the contract and upgrade-data tripwires listed in `release-notes.md` "Verification", at the
  release commit; item-level suites on each item. No full suite (focused tests only).
- UI checks complete: DAR-BE-328 pair page spec (Peak hours' review); version bump only at the cut.
- Live/deployed smoke coverage noted: UAT smoke owed; prod checks after the deploy owed.
- Unverified items called out: the image build with moqui-rules at the v0.2.0 tag (proved on UAT first), the
  Spark UDFs under the deployed classloader (UAT smoke).

## Approval

- Release owner sign-off: Aditi approved tags and the UAT deploy (2026-11-23 10:23 IST); prod deploy is hers.
- Cut/tag blocked until this checklist is fully complete: yes
