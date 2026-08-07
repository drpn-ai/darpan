# Release Checklist For Darpan 1.4.0

## Linear prework

- Release tracking issue: not applicable — the Linear rule was retired 2026-07-01 and Linear is no longer the source of truth.
- Included issue IDs: not applicable. Work in this range is tagged with internal requirement ids (RQ-1 through RQ-19) and DAR-BE-017, carried in the commit subjects and mapped in `technical-changelog.md`.
- Open release issues: 0
- Issue tracking note: none tracked; scope was derived from the compare ranges in `technical-changelog.md`.
- Scope changes documented in Linear: not applicable.
- Deferred issues moved to the next release: recorded in the "Deferred items" section of `release-notes.md`.

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`, curated from the compare ranges and the commit bodies rather than pasted from the commit log.
- Operator-visible changes reviewed: yes — one required action (upgrade-data load), preceded by an explicit prerequisite check that `1.3.0`'s upgrade data and one-time backfill actually loaded, since this release's file is release-scoped and does not re-carry them.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes — `technical-changelog.md`, split into Added/Changed/Fixed/Security per repo. The generator placed every commit under "Added" for both repos; the buckets were rewritten by hand against the commit bodies.
- Compare URLs captured: yes — `https://github.com/drpn-ai/darpan/compare/v1.3.0...v1.4.0`, `https://github.com/drpn-ai/darpan-hotwax/compare/v0.6.0...v0.7.0`, `https://github.com/drpn-ai/darpan-ui/compare/v2.4.0...v2.5.0`.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes — all three records originate in `data/AutomationSeedData.xml`, `data/DarpanSystemSourceSeedData.xml`, and `data/SourceSystemConnectorSeedData.xml`, not hand-authored into `upgrade-data.xml`.
- Candidate diff reviewed: yes — `upgrade-data-review.md` lists three added records and no modified records.
- Record ordering re-checked parent-before-child by hand: yes — the `OMS_TRANSFER_ORDERS` Enumeration precedes the `SourceSystemConnector` row whose `systemEnumId` references it. The generator sorts by source file, which agrees with the dependency here by coincidence; the note in `upgrade-data.xml` records that this must be re-checked, not assumed, on every regeneration.
- Final load path decided: operators run `./gradlew loadDarpanUpgradeData`. No one-time migration service is added by this release.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Current file and release-pack mirror are byte-identical: yes — verified with `cmp`.
- Previous upgrade data archived under prior tag folder: yes, and re-archiving was deliberately bypassed. `data/releases/1.3.0/upgrade-data.xml` already existed and was verified byte-identical to `git show v1.3.0:data/upgrade-data.xml` before generating. The working-copy file had been appended to since `v1.3.0` (4 records against the archive's 2), so allowing the generator to archive it would have overwritten a correct archive with post-release content. Confirmed intact after generation.

## Verification

- Backend checks complete: yes — `--rerun-tasks` under JDK 21. `darpan` 778 tests / 88 classes, `darpan-hotwax` 109 tests / 6 classes, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESSFUL in 12m 11s`. A plain `test` invocation is insufficient: a `component.xml` version bump is not a test input, so Gradle reports the suite `UP-TO-DATE` and executes nothing.
- Note on suite isolation: `darpan`'s failure aborts the combined invocation before `darpan-hotwax:test` runs, so a red `darpan` suite silently yields zero hotwax coverage. Both counts above come from a single green run in which both tasks executed.
- UI checks complete: yes — `npm run check`, 900 tests across 102 files, 0 failures, with stylelint design-system gate, ESLint, and `vue-tsc` type-check all executing.
- API contract regenerated for the version bump: yes — `docs/api-contract/openapi.json`, one-line diff (`1.3.0` to `1.4.0`), `methods.txt` unchanged at 90 methods.
- API contract compatibility checked: yes — `scripts/check_contract_compat.py` against `v1.3.0` reports 90 base methods preserved, contract version `2`. Additive only, no unversioned breakage.
- Facade contract snapshot regenerated and reviewed: yes — `FacadeContractSnapshotTests` was failing on `main` because `errorDetail` reached the service contract and `openapi.json` but not the committed snapshot. Regenerated; the accepted diff is a single line in `SERVICE get ReconciliationRunStatus`. The diff was reviewed before acceptance because regeneration rewrites the whole snapshot from live definitions and would otherwise silently absorb unrelated contract drift.
- Version metadata bumped: yes — `darpan/component.xml` to `1.4.0`, `darpan-hotwax/component.xml` to `0.7.0`, `darpan-ui/package.json` and the two `package-lock.json` version keys to `2.5.0`. The lock file was edited at its root `version` and `packages[""].version` only.
- Live/deployed smoke coverage noted: none. No reconciliation run against a real OMS tenant, no browser pass over the release candidate, no deployed-environment smoke for this tag.
- Unverified items called out: yes — enumerated under "Not verified in this release" in `release-notes.md`.

## Approval

- Release owner sign-off: pending. The pack is complete and internally consistent; sign-off is the release owner's decision and has not been given by an automated step.
- Cut/tag blocked until this checklist is fully complete: this checklist is complete apart from sign-off and the live-verification gaps listed above, which are recorded as known and accepted risk rather than resolved.
- Nothing has been committed, tagged, pushed, published, or deployed by the preparation step. Prod image pins in `darpan-docker-config` still point at `v1.3.0` / `v0.6.0` and are deliberately left untouched until the tags exist.
- Outstanding deploy-time question for the release owner: whether `1.3.0`'s upgrade data and its one-time backfill were ever loaded in production. This release does not re-carry them, and the answer changes the deploy runbook.
