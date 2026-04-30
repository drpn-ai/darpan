# Technical Changelog For Darpan 2.0.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `2.0.0` is the draft target because this cut changes enough product and integration surface to require major-release treatment: primary reconciliation execution moves to ruleset-backed saved runs, active-company scoping becomes a core session contract, facade/security setup expands, seeded auth data changes, and the UI route/workflow assumptions shift with the backend contracts.
- This does not claim intentional breaking changes for every user path; it is a conservative major release because the combined blast radius is too large for a minor release.
- Version metadata is aligned for local release-candidate testing. Backend `component.xml`, UI `package.json`, and UI `package-lock.json` now read `2.0.0`.

## Source ranges

- Backend: `v1.1.1..local 2.0.0 worktree` (`feature/dar-206` at `52f1ac0` plus uncommitted candidate changes)
- UI: `v1.1.1..local 2.0.0 worktree` (`main` at `bafcd5b` plus uncommitted candidate changes)

## Backend

### Added
- RuleSet compare-scope entities, source definitions, compare adapters, diff stages, and rule-stage execution support for ruleset-backed reconciliation.
- `ReconciliationRunResult` entity storage for saved-run artifact manifests, including uploaded source-file data-manager paths and generated result data-manager paths.
- Data-manager path helpers for reconciliation run artifacts under `runtime://datamanager/reconciliation-runs/{runId}/{timestamp}/`.
- Saved-run facade support for listing saved runs, running saved-run diffs, and reading generated outputs through the newer reconciliation contract.
- Active-company session and tenant access support, including company membership, permission level, user preference, and entity-filter setup.
- Navigation search facade support for the custom UI command/search surface.
- Smoke-test coverage for saved runs, tenant filtering, navigation search, generic reconciliation, RuleSet compare scopes, SFTP automation, and output handling.

### Changed
- Generic reconciliation, SFTP automation, and mapping-backed flows now route through ruleset/saved-run contracts instead of pilot-only screens and services.
- Generic reconciliation persists uploaded source artifacts and result JSON to the data-manager run folder and returns generated-output `fileName` values as safe data-manager relative paths.
- Backend screens retain minimal compatibility paths while pointing users toward the ruleset-backed contracts.
- Schema contracts now carry company scope and accept raw schema/sample payload text where user input legitimately includes literal angle brackets.
- Dashboard preferences and pinned reconciliation records were renamed and reorganized around saved runs instead of pilot mappings.

### Fixed
- Schema lists are filtered by active company so users do not see records they cannot open.
- Raw schema sample upload no longer rejects ordinary `<` and `>` text characters.
- Saved generated output timestamp, retrieval, and manifest path behavior is stabilized for file-backed run results.
- JSON-specific RuleSet execution service and debug-only call path are removed in favor of the generic RuleSet execution entrypoint.

### Security
- Darpan facade services are grouped under `DARPAN_FACADE_APP`, with artifact authorization for `ADMIN` and `DARPAN_USER`.
- `DARPAN_ACTIVE_COMPANY_SCOPE` filters tenant-owned entities such as schemas, RuleSets, generated run results, NetSuite auth/restlet config, mappings, and SFTP servers by the active company in user context.
- Production seed data defines reusable tenant group types and permission groups without granting demo users access to default tenants.

## UI

### Added
- Active company session switching in the shell and auth client.
- Saved-run and ruleset-oriented reconciliation routes and result surfaces.
- Darpan favicon asset.

### Changed
- The UI auth/session client aligns with the backend active-company contract.
- Workflow forms and selectors handle Enter progression and option selection more consistently.
- Edit workflow pages suppress the multi-step progress treatment where it does not apply.
- Saved run labels are softened for readability while preserving known acronyms.

### Fixed
- Reconciliation result rows that represent field mismatches no longer render as empty JSON objects.
- Workflow dropdown selection now responds to keyboard Enter in the shared workflow components.
- Edit-surface presentation is cleaned up so single-page edits do not appear as incomplete guided flows.

### Security
- The draft local UI diff includes a stricter Firebase Content-Security-Policy change, but that change is not part of the committed `origin/main` candidate used for this preflight. It must be reviewed separately before any hosted deploy.


## Data and configuration

- Candidate upgrade records live in `upgrade-data-review.md` and `upgrade-data.xml`.
- `SecuritySeedData.xml` is the source of truth for Darpan facade access, active-company filtering, tenant group types, and reusable tenant permission groups.
- `upgrade-data.xml` excludes smoke-test fixtures and demo tenant/user memberships from production upgrade data.
- Docker startup now invokes MoquiStart `load` from the expanded WAR before Moqui starts, with `DARPAN_LOAD_UPGRADE_DATA=N` available as an opt-out.
- UI package and lockfile versions are set to `2.0.0` for local candidate testing.

## Validation and rollout notes

- Roll out backend and UI together. The UI assumes backend active-company, saved-run, and ruleset result contracts introduced in the backend candidate.
- Backend candidate is currently not on release `main`; merge/rebase policy must be resolved before cutting.
- UI local checkout is dirty and behind `origin/main`; the current local worktree is the `2.0.0` candidate and must be committed before validation and tagging.
- File-path persistence was verified on `2026-04-29` with focused backend tests for `DataManagerSupportTests`, `ReconciliationOutputSupportTests`, and `GenericReconciliationServiceSmokeTests`; serial `:runtime:component:darpan:compileGroovy` also passed.
- Open non-archived bug issues found in Linear on `2026-04-30`: `DAR-227`, `DAR-228`, `DAR-229`, `DAR-232`, `DAR-233`, `DAR-92`, `DAR-142`, `DAR-143`, `DAR-144`, `DAR-145`.
- Candidate compare references:
  - Backend: `https://github.com/toaditi/darpan/compare/v1.1.1...52f1ac0`
  - UI: `https://github.com/toaditi/darpan-ui/compare/v1.1.1...67745da`
- Final release compare links should point at the actual `v2.0.0` tags after cutting.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
