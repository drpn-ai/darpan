# Release Checklist For Darpan 3.3.1

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-CLIENT-006
- Open release issues: 0
- Scope changes recorded on the backlog items: Aditi asked for a 1.5-hour Shopify/HotWax sync grace on Steve Madden's
  fulfilment check; the Shopify side is config (live on sm-darpan 2026-11-19); the HotWax side needs this fix. "push and
  cut a hotfix", then "1" (the current line, not a 2.1.x backport).
- Deferred items named and marked on the backlog: none.

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes folded into the 3.3 section of darpan-docs
  `releases/updates.mdx`.
- Operator-visible changes reviewed: yes, including SM's read cost and prod trees that may start matching.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes, none: no seed changed in `v3.3.0..HEAD`.
- Candidate diff reviewed: yes, empty.
- Final load path decided: nothing to load for 3.3.1. sm-darpan, coming from 2.1.12, loads every archived pack after
  2.1.12 in order (`release-notes.md`).
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml` (no records)
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.3.0/upgrade-data.xml`, byte-identical
  to `v3.3.0`'s.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py` exit status (before tagging): run in the cut.
- Docs repos pushed: in the cut.
- Post-deploy GraphQL re-check against prod owed: yes (schema unchanged).

## Verification

- Backend checks complete: yes, on `main` (b6b7fdaf = the same change), JDK 21, 2026-11-19 16:01 IST, executed, not
  cached: RunSideOptionsSmokeTests 10/10 (new: theConclusionTreesFieldsReachTheOmsOrderReadToo, red first),
  ReconciliationSavedRunSupportTests 23/23, SavedRunsFacadeSmokeTests 61/61, SourceFilterSupportTests 32/32.
- The release tree (`v3.3.0` + cherry-pick) was not built on its own: the cherry-pick applied cleanly and none of the
  four `main` commits it leaves out touch `ReconciliationSavedRunSupport.groovy` or the test file.
- Contract: `docs/api-contract/openapi.json` differs from `v3.3.0` only in `info.version` (as 3.2.1); no method change.
- UI checks complete: none: no UI change.
- Deploy pins: `verify_release_pins.py` runs in the cut.
- Unverified items called out: full suites not run; the fix not yet seen on a live scheduled run.

## Approval

- Release owner sign-off: Aditi, "push and cut a hotfix" + "1" (2026-11-19 15:45 IST).
