# Darpan 2.0.0 Release Notes

Release prep date: `2026-05-04`

## Scope note

Release `2.0.0` is the major candidate after `v1.1.1`. It bundles the ruleset/saved-run reconciliation cutover, automation management, active-tenant scoping, production settings surfaces, and the backend seed/security changes those UI flows require.

This is a prep pack only. The `v2.0.0` tag, GitHub release, Firebase deploy, and production rollout have not been cut.

## Repo targets

- Previous backend tag verified after fetch: `v1.1.1`
- Previous UI tag verified after fetch: `v1.1.1`
- Backend candidate repo: `toaditi/darpan`
- Backend release-target remote also configured: `hotwax/darpan`
- Backend branch/ref: `main` at `e1efc7c`
- Backend compare range: `v1.1.1..e1efc7c`
- Backend release pack path: `data/releases/2.0.0`
- UI candidate repo: `toaditi/darpan-ui`
- UI branch/ref: `main` at `6a887e3`
- UI compare range: `v1.1.1..6a887e3`
- Final cut target, when approved: tag the approved backend and UI refs as `v2.0.0`.

## User-visible changes

- Reconciliation moves further onto saved-run and ruleset-backed execution paths, with result pages handling missing records, field mismatches, and generated output rows more cleanly.
- Automation setup is now represented in the product surface with automation dashboards/workflows, pending-run helpers, execution-state copy, and SFTP/API source modes.
- Tenant/user settings, Shopify settings, OMS REST settings, and SFTP settings are promoted into first-class UI surfaces aligned with the shared workflow/static-page patterns.
- Active tenant switching is part of the authenticated session and now drives settings, schemas, rulesets, saved runs, generated outputs, and tenant-scoped navigation behavior.
- Workflow controls are more keyboard-friendly, including Enter handling for shared dropdowns and progression controls.
- The old LLM settings pages are removed from the UI candidate.

## Operator-visible changes

- Backend `component.xml`, UI `package.json`, and UI `package-lock.json` are aligned to `2.0.0`.
- Backend and UI must ship together. The UI assumes backend active-tenant, automation, settings, saved-run, and ruleset result contracts introduced after `v1.1.1`.
- Docker startup runs the component-owned `loadDarpanUpgradeData` task through `/moqui-framework/runtime/component/darpan/build.gradle` before Moqui starts unless `DARPAN_LOAD_UPGRADE_DATA=N`.
- The production upgrade load contains automation enums, the automation scanner job, the `OMS` system-source update to HotWax, Darpan facade/entity-filter security records, SFTP scope enums, and Shopify/HotWax system-message remotes.
- Smoke-test RuleSet fixtures and duplicate source/file-type enumeration candidates were reviewed in `upgrade-data-review.md` and excluded from the production `upgrade-data.xml` load.

## Upgrade data

- Generic source data files under `runtime/component/darpan/data/*.xml` are the source of truth.
- Current upgrade file: `runtime/component/darpan/data/upgrade-data.xml`
- Release-pack mirror: `data/releases/2.0.0/upgrade-data.xml`
- Candidate review report: `data/releases/2.0.0/upgrade-data-review.md`
- Previous current upgrade data was archived under `data/releases/1.1.1/upgrade-data.xml` during this prep pass.
- Operator load command when this release is approved: `./gradlew -b runtime/component/darpan/build.gradle loadDarpanUpgradeData`

## Verification

- Fresh Linear bug audit completed on `2026-05-04`; 15 non-archived Bug issues remain open, so the cut is blocked.
- Release-pack validation is expected to fail until open release issues are explicitly zeroed or deferred by the release owner.
- XML well-formedness, backend compile, UI checks, and preflight validation are recorded in `release-checklist.md`.
- Live browser smoke testing, deployed smoke testing, tag creation, GitHub release publication, and Firebase deploy were not performed.

## Deferred items

- Release cut/tag/deploy are deferred.
- Open release-blocking bug review is still required for `DAR-190`, `DAR-260`, `DAR-258`, `DAR-236`, `DAR-235`, `DAR-233`, `DAR-232`, `DAR-228`, `DAR-229`, `DAR-227`, `DAR-92`, `DAR-142`, `DAR-143`, `DAR-144`, and `DAR-145`.
- Parent Moqui framework/runtime worktrees and unrelated component repos are outside this release pack.

## Rollback or fallback notes

- Do not cut `v2.0.0` until open Bug issues are fixed or explicitly deferred, backend/UI checks pass, and the tag target refs are approved.
- If active-tenant scoping, saved-run result viewing, automation execution, or settings visibility fails in validation, roll backend and UI back together to `v1.1.1`.
