# Release Checklist For Darpan 1.3.0

## Linear prework

- Release tracking issue: not applicable — the Linear rule was retired 2026-07-01 and Linear is no longer the source of truth.
- Included issue IDs: not applicable.
- Open release issues: 0
- Issue tracking note: none tracked; scope was derived from the compare ranges in `technical-changelog.md`.
- Scope changes documented in Linear: not applicable.
- Deferred issues moved to the next release: recorded in the "Deferred items" section of `release-notes.md`.

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`, curated from the compare ranges rather than pasted from the commit log.
- Operator-visible changes reviewed: yes — two required actions (upgrade-data load, then the one-time backfill), stated in order with the consequence of skipping each.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes — `technical-changelog.md`, split into Added/Changed/Fixed/Security per repo.
- Compare URLs captured: yes — form is `https://github.com/drpn-ai/<repo>/compare/<prev-tag>...<tag>`; ranges are `v1.2.0..HEAD` (darpan), `v0.5.0..HEAD` (darpan-hotwax), `v2.3.0..HEAD` (darpan-ui).
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes — the single record originates in `data/SourceSystemConnectorSeedData.xml`, not hand-authored into `upgrade-data.xml`.
- Candidate diff reviewed: yes — `upgrade-data-review.md` lists one modified record (OMS connector, `filterParameterName="sourceFilters"`).
- Final load path decided: operators run `./gradlew loadDarpanUpgradeData`, then the one-time service `facade.ReconciliationFacadeServices.migrate#AutomationExcludeFilters`.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes — `data/releases/1.2.0/upgrade-data.xml`, verified byte-identical to `git show v1.2.0:data/upgrade-data.xml`.

## Verification

- Backend checks complete: yes — `cleanTest test` under JDK 21. `darpan` 709 tests / 86 classes, `darpan-hotwax` 84 tests / 6 classes, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESSFUL in 10m 59s`. A plain `test` invocation was insufficient: Gradle reported the suite `UP-TO-DATE` and executed nothing.
- UI checks complete: yes — `npm run check`, 873 tests across 101 files, 0 failures, with stylelint design-system gate, ESLint, and `vue-tsc` type-check all executing.
- API contract regenerated for the version bump: yes — `docs/api-contract/openapi.json`, one-line diff, `methods.txt` unchanged at 90 methods.
- Live/deployed smoke coverage noted: none. No live reconciliation run against a real OMS tenant, no browser pass over the new exclusion or help surfaces, no deployed-environment smoke for this tag.
- Unverified items called out: yes — enumerated under "Not verified in this release" in `release-notes.md`.

## Approval

- Release owner sign-off: pending. The pack is complete and internally consistent; sign-off is the release owner's decision and has not been given by an automated step.
- Cut/tag blocked until this checklist is fully complete: this checklist is complete apart from sign-off and the live-verification gaps listed above, which are recorded as known and accepted risk rather than resolved.
