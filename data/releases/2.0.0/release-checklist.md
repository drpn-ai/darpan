# Release Checklist For Darpan 2.0.0

## Linear prework

- Release tracking issue: not created in this prep-only pass.
- Included issue IDs from candidate history: `DAR-39`, `DAR-180`, `DAR-185`, `DAR-186`, `DAR-187`, `DAR-197`, `DAR-200`, `DAR-206`, `DAR-207`, plus ruleset-cutover commit series.
- Open release issues: 10 non-archived `Bug` issues found on `2026-04-30`.
- In-progress `Bug` issues: `DAR-228`, `DAR-229`, `DAR-233`.
- In-review `Bug` issues: `DAR-227`, `DAR-232`.
- Backlog `Bug` issues: `DAR-92`, `DAR-142`, `DAR-143`, `DAR-144`, `DAR-145`.
- Scope changes documented in Linear: no release tracking issue was updated.
- Deferred issues moved to the next release: not done; release owner must decide fix vs defer before cut.

## Release notes

- User-facing release notes drafted: yes, with release-blocker caveats.
- Operator-visible changes reviewed: yes, production upgrade-data filtering applied.
- Release notes link or path: `release-notes.md`
- Local current state aligned to `2.0.0`: yes.

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: candidate links only; final tag compare links pending actual cut.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Candidate diff reviewed: yes for production-safe tenant setup records.
- Final load path decided: `seed` for approved additive setup records.
- Current load target matches release copy: yes.
- Upgrade data file link or path: `upgrade-data.xml`

## Verification

- Backend checks complete: yes. `:runtime:component:darpan:compileGroovy` passed through `run_backend_checks.sh`.
- File-path persistence checks complete: yes. On `2026-04-29`, focused backend tests passed for `DataManagerSupportTests`, `ReconciliationOutputSupportTests`, and `GenericReconciliationServiceSmokeTests`.
- Current serial backend compile complete: yes. On `2026-04-29`, `./gradlew :runtime:component:darpan:compileGroovy` passed.
- Docker entrypoint upgrade-data hook complete: yes. `docker/entrypoint.sh` runs `./gradlew loadDarpanUpgradeData` before starting Moqui unless `DARPAN_LOAD_UPGRADE_DATA=N`.
- UI checks complete: no.
- Release-pack validation complete: attempted; blocked because open release issues are not zero and final ref-based upgrade-data validation requires committed refs.
- Upgrade XML well-formedness checked: yes.
- Release preflight script syntax checked: yes.
- Live/deployed smoke coverage noted: yes, not run.
- Unverified items called out: yes.

## Approval

- Release owner sign-off: local state should be treated as `2.0.0`; explicit instruction was not to cut the release.
- Cut/tag blocked until this checklist is fully complete: blocked.
- Blocking reasons: open Linear bugs remain; backend candidate is on `feature/dar-206`; local backend and UI worktrees are dirty; local UI is behind `origin/main`; full release checks have not run.
