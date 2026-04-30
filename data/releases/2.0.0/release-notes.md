# Darpan 2.0.0 Release Notes

Release date: `2026-04-30`

## Scope note

Release `2.0.0` covers:

- the current local major release candidate for the ruleset-first reconciliation cutover, saved-run result viewing, active-company scoping, schema-contract hardening, workflow keyboard/UI polish, and production-readiness fixes completed after `1.1.1`.
- the release is treated as major because too many product and contract surfaces changed to honestly present this as a minor cut: reconciliation execution, saved-run APIs, tenant/company scoping, facade authorization, seeded security setup, and frontend route/workflow assumptions all changed together.
- this pack is prepared for local `2.0.0` review only. No tag, GitHub release, push, deploy, or commit has been performed.
- the local backend component and UI package metadata now read `2.0.0`; the release is still not cut-ready because Linear has open non-archived bug work and both local repos have uncommitted candidate changes.

## Repo targets

- Backend candidate repo: `toaditi/darpan`
- Backend release-target repo: `hotwax/darpan`
- Backend candidate ref: current local `feature/dar-206` worktree at `52f1ac0` plus uncommitted candidate changes
- Backend compare range used for this draft: `v1.1.1..local 2.0.0 worktree`
- Backend release pack path: `data/releases/2.0.0`
- UI candidate repo: `toaditi/darpan-ui`
- UI candidate ref: current local `main` worktree at `bafcd5b` plus uncommitted candidate changes
- UI compare range used for this draft: `v1.1.1..local 2.0.0 worktree`
- Final cut target, when approved: commit the local `2.0.0` candidate, merge or fast-forward the approved release branches, then tag `v2.0.0`.

## User-visible changes

- Reconciliation workflows move to saved runs and ruleset-backed execution paths instead of the old pilot-specific generic-diff screens.
- Run results handle missing records, field mismatches, and generated output rows more cleanly, including rows that do not carry full record JSON payloads.
- Generic reconciliation now persists uploaded source file paths and the generated result path in `darpan.reconciliation.ReconciliationRunResult`, so saved-run result views and generated-output downloads can resolve the original data-manager artifacts by stable relative paths.
- Active company selection is now part of the authenticated session, so settings, schemas, runs, mappings, and generated outputs can be scoped to the current company.
- Schema listing and schema upload behavior were hardened so unavailable tenant data is filtered out and sample text containing literal `<` or `>` characters is accepted where raw input is expected.
- Workflow controls are more keyboard-friendly: dropdown selection and form progression now respond to Enter in the shared workflow components.
- Edit workflow pages have a calmer presentation, including removal of step progress treatment where it does not apply.
- The UI bundle now includes the Darpan favicon.

## Operator-visible changes

- Backend facade access now depends on the Darpan facade artifact group and active-company entity filter setup introduced after `1.1.1`.
- Run artifacts are written under `runtime://datamanager/reconciliation-runs/{runId}/{timestamp}/`; the source and result relative paths are stored on `ReconciliationRunResult` as `file1DataManagerPath`, `file2DataManagerPath`, and `resultDataManagerPath`.
- The Docker entrypoint runs MoquiStart `load` from the expanded WAR before Moqui starts so approved current upgrade data is loaded during container startup; set `DARPAN_LOAD_UPGRADE_DATA=N` to skip that startup load when an environment manages upgrades separately.
- Tenant/company group types, reusable tenant permission groups, and active-company preference keys are part of the candidate data review.
- The production upgrade data excludes RuleSet compare-scope smoke fixtures and demo tenant/user memberships.
- UI and backend should ship together because the frontend now assumes active-company/session contracts and saved-run/ruleset result contracts that are not present in `1.1.1`.
- Current backend component and UI package metadata read `2.0.0` for local release-candidate testing.

## Upgrade data

- Candidate upgrade file: `upgrade-data.xml`
- Candidate review report: `upgrade-data-review.md`
- The approved source of truth for reusable security setup is `SecuritySeedData.xml`; `upgrade-data.xml` carries the additive production upgrade subset.
- Expected final load path is `seed` for approved additive setup records.

## Verification

- Preflight pack was retargeted to `2.0.0` after versioning review and aligned to the current local candidate.
- Preflight validation was attempted and correctly failed because `release-checklist.md` records open release issues instead of `Open release issues: 0`.
- Backend compile passed with `plugins/darpan-workflows/scripts/run_backend_checks.sh` against the release pack files before the version retarget.
- File-path persistence verification passed on `2026-04-29` with focused backend tests for `DataManagerSupportTests`, `ReconciliationOutputSupportTests`, and `GenericReconciliationServiceSmokeTests`.
- Serial backend compile verification passed on `2026-04-29` with `./gradlew :runtime:component:darpan:compileGroovy`.
- `upgrade-data.xml` parses as XML.
- `release_preflight.py` syntax parses.
- Git fetch completed for backend and UI before this draft was generated.
- Linear bug audit completed on `2026-04-28`; open non-archived bug-fix issues remain and are listed below.
- Not yet verified for cut: UI checks, live browser smoke testing, deployed smoke testing, tag creation, GitHub release publication, and Firebase deploy.

## Deferred items

- Release cutting itself: deferred by release-owner instruction.
- Open production-readiness bug issues that block a clean "no outstanding bugs" release: `DAR-227`, `DAR-228`, `DAR-229`, `DAR-232`, `DAR-233`.
- Open backlog bug issues to either fix or explicitly defer before cut: `DAR-92`, `DAR-142`, `DAR-143`, `DAR-144`, `DAR-145`.
- Local UI dirty worktree cleanup: the active checkout is on `main`, behind `origin/main` by four commits, and has a large uncommitted `2.0.0` candidate diff that must be committed before release.
- Backend release-surface cleanup: candidate backend work is on `feature/dar-206`, not release `main`.

## Rollback or fallback notes

- Do not cut `v2.0.0` until open release-blocking bugs are either fixed or explicitly deferred, both repos are clean at approved refs, and backend/UI checks pass.
- If ruleset-backed reconciliation, saved-run result viewing, or active-company scoping fails in validation, roll back backend and UI together to `v1.1.1`.
- Treat missing facade authorization for `john.doe`, schema-less saved runs appearing in the UI, or tenant-scoped records leaking across active companies as no-go conditions.
