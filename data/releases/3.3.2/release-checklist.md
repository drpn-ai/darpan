# Release Checklist For Darpan 3.3.2

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-319, DAR-BE-321, DAR-BE-322
- Open release issues: 0
- Scope changes recorded on the backlog items: Aditi asked to move the two-factor reminder email to 7pm IST on SM and
  prod; the job edit failed on both (DAR-BE-321 on prod, DAR-BE-322 on SM's daylight-saving clock), then "cut a hotfix
  and deploy". DAR-BE-319 rides along so the prod deploy keeps the five disabled migrations disabled.
- Deferred items named and marked on the backlog: none.

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes folded into the 3.3 section of darpan-docs
  `releases/updates.mdx`.
- Operator-visible changes reviewed: yes, including the reminder time on a daylight-saving clock.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes, `data/MigrationRegistrySeedData.xml`.
- Candidate diff reviewed: yes, 12 registry rows, changed only by no longer stating `enabled`.
- Final load path decided: no load needed; loading the rows leaves each enabled flag as it is, and the boot seed
  carries them.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.3.1/upgrade-data.xml`, byte-identical
  to `v3.3.1`'s.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py` exit status (before tagging): run in the cut.
- Docs repos pushed (Mintlify publishes from `main`): in the cut.
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/...`) owed: yes (schema unchanged).

## Verification

- Backend checks complete: yes, on `main` (the same three changes), JDK 21, 2026-11-19 18:53-18:58 IST, executed with
  `--rerun`, not cached: JobScheduleRulesTests 8/8 (new: aDailyCronOnADaylightSavingClockKeepsAOneDayFloor, red
  first with SM's message "Runs every 1380 minutes at its tightest"), ScheduleAdminServicesSmokeTests 18/18,
  ScheduleJobDefaultsSmokeTests 6/6, EntityFindPagingGuardTests 1/1, MigrationAgentSurfaceSmokeTests 15/15.
- The release tree (`v3.3.1` + three cherry-picks) was not built on its own: the cherry-picks applied cleanly and none
  of `main`'s left-out commits (DAR-UI-132, DAR-BE-320) touch the six changed files.
- Contract: `docs/api-contract/openapi.json` differs from `v3.3.1` only in `info.version`; no method change.
- UI checks complete: none: no UI change.
- Live/deployed smoke coverage noted: after deploy, `darpan-admin jobs` on prod and sm-darpan, and the five migrations
  still DISABLED on prod.
- Unverified items called out: full suites not run.

## Approval

- Release owner sign-off: Aditi, "cut a hotfix and deploy" (2026-11-19 18:50 IST).
- Cut/tag blocked until this checklist is fully complete: yes, complete.
