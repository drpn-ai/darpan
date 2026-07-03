# Release Checklist For Darpan 2.0.0

## Linear prework

- Release tracking issue: not created in this prep-only pass.
- Included issue IDs from candidate history: `DAR-39`, `DAR-180`, `DAR-185`, `DAR-186`, `DAR-187`, `DAR-197`, `DAR-200`, `DAR-206`, `DAR-207`, `DAR-227`, `DAR-228`, `DAR-229`, `DAR-232`, `DAR-233`, `DAR-234`, plus the 2.0.0 automation/settings candidate commits.
- Fresh Linear bug audit: refreshed on `2026-05-04`.
- Open release issues: 0
- In-progress Bug issues: none returned by refreshed Linear audit.
- In-review Bug issues: none returned by refreshed Linear audit.
- Backlog Bug issues: none returned by refreshed Linear audit.
- Scope changes documented in Linear: no release tracking issue was updated.
- Deferred issues moved to the next release: not applicable from refreshed open-bug audit.

## Release notes

- User-facing release notes drafted: yes.
- Operator-visible changes reviewed: yes.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, commit-based links until final tags exist.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes; validation checks the curated records against `runtime/component/darpan/data/*.xml` diffs.
- Candidate diff reviewed: yes; smoke-test RuleSet fixtures and duplicate enum candidates are excluded from the production load file.
- Final load path decided: `./gradlew -b runtime/component/darpan/build.gradle loadDarpanUpgradeData`.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Release mirror path: `runtime/component/darpan/data/releases/2.0.0/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: `runtime/component/darpan/data/releases/1.1.1/upgrade-data.xml`

## Verification

- XML well-formedness checked: yes. `xmllint --noout data/upgrade-data.xml data/releases/2.0.0/upgrade-data.xml data/releases/1.1.1/upgrade-data.xml` passed.
- Backend checks complete: yes. `./gradlew :runtime:component:darpan:compileGroovy` passed.
- UI checks complete: yes. `npm run check` passed, including lint, type-check, and 63 Vitest files / 443 tests.
- Docker component inclusion checked: yes for source refs. `git ls-remote --heads` resolved `toaditi/darpan-hotwax main` and `toaditi/shopify-darpan main`; `docker buildx build --check -f docker/Dockerfile .` was attempted but could not connect to a running local Docker daemon.
- Production Docker folder checked: yes. `docker/prod` now follows the referenced Unigate layout with `Dockerfile` and `entrypoint.sh`; all production Dockerfile source refs resolved with `git ls-remote`; `docker buildx build --check -f docker/prod/Dockerfile .` was attempted but could not connect to a running local Docker daemon.
- Release-pack validation complete: yes. `release_preflight.py validate --version 2.0.0 ... --backend-current-ref HEAD --ui-current-ref HEAD` passed after bug-fix commits and zero-open-bug audit.
- Live/deployed smoke coverage noted: not run in this prep-only pass.
- Unverified items called out: yes.

## Approval

- Release owner sign-off: approved for tag/release cut in the `2026-05-04` request to cut all component releases.
- Cut/tag blocked until this checklist is fully complete: not blocked for tag cut.
- Blocking reasons: production deploy and live/deployed smoke remain separate from the tag cut; final tags are being created from approved refs.
