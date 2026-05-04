# Technical Changelog For Darpan 2.0.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `2.0.0` is a major release candidate because the candidate changes the primary reconciliation execution model, active-tenant/session contract, facade/security setup, automation runtime surface, settings contracts, and frontend route assumptions together.
- The previous release baseline is `v1.1.1` for both backend and UI.
- Version metadata is aligned for release-candidate testing in backend `component.xml`, UI `package.json`, and UI `package-lock.json`.

## Source ranges

- Backend: `v1.1.1..main`
- UI: `v1.1.1..6a887e3`
- Backend compare URL: `https://github.com/toaditi/darpan/compare/v1.1.1...main`
- UI compare URL: `https://github.com/toaditi/darpan-ui/compare/v1.1.1...6a887e3`

## Backend

### Added
- Automation entities, execution support, SFTP/API automation source contracts, scanner service/job seed data, and automation facade smoke tests.
- Tenant notification support and tenant setup documentation for multi-user Darpan operation.
- Facade helper classes for auth, JSON schema, automation, API windows, saved runs, and output handling.
- Active-company entity filters for schemas, mappings, rulesets, run results, automations, tenant settings, tenant notifications, NetSuite settings, and SFTP servers.
- Production seed records for Darpan permission groups, SFTP server scope enums, facade artifact authz, and Shopify/HotWax system message remotes.

### Changed
- Reconciliation facade services route saved-run and ruleset execution through helper classes instead of standalone debug/pilot scripts.
- Generated reconciliation artifacts use stable data-manager paths and saved-run result manifests.
- Settings facade behavior is tenant-aware and stamps tenant/user ownership for scoped settings records.
- Docker entrypoint invokes the component-owned `loadDarpanUpgradeData` Gradle task using the component build file.
- Docker image packaging now includes the `darpan-hotwax` and `shopify-darpan` runtime component repositories before the `addRuntime` WAR build.
- A production Docker image layout was added under `docker/prod`, mirroring the Unigate config convention with a production Dockerfile and entrypoint.
- Documentation was reorganized around tenant setup, permissions, production settings, automation, and platform service contracts.

### Fixed
- Schema sample text and validation flows accept raw user input without the removed helper-script path.
- Generated output path handling avoids debug-service and traversal-oriented access patterns.
- SFTP automation scope is explicit for tenant/admin SFTP server usage.
- HcReadDb/debug screens and debug-only Groovy services are removed from the release surface.

### Security
- `DARPAN_FACADE_APP` governs facade service access for `ADMIN` and `DARPAN_USER`.
- `DARPAN_ACTIVE_COMPANY_SCOPE` filters tenant-owned rows by `activeTenantUserGroupId`.
- Tenant permission groups are separated from tenant identity groups.
- The release upgrade load excludes smoke-test fixtures and demo user memberships.

## UI

### Added
- Automation dashboard/workflow pages and tests.
- Tenant, user, Shopify, OMS REST, and SFTP settings pages/workflows.
- Shared list pagination, saved-run editor routing, user-display helpers, OMS Swagger helpers, and automation draft helpers.
- Tenant-switching mockup documentation for the UI candidate.

### Changed
- Home/dashboard, route guards, auth client, and shell state align with active-tenant session behavior.
- Reconciliation create, diff, ruleset editor, ruleset manager, run history, and run result pages align with saved-run/ruleset contracts.
- Workflow forms and shared selects handle keyboard progression and selection consistently.
- Runs settings use the shared workflow/static-page behavior instead of older LLM settings surfaces.

### Fixed
- Result rows with field mismatches render useful detail instead of empty JSON objects.
- Run history and run result surfaces handle missing/generated-output states more deliberately.
- Schema wizard input and verification behavior is hardened around raw sample text and editable verification details.

### Security
- UI route guards and auth tests now exercise active-tenant and login/session behavior.
- Tenant/user/settings pages rely on backend tenant-scoped facade contracts; no client-side-only isolation is treated as sufficient.

## Data and configuration

- Generic source data files under `runtime/component/darpan/data/*.xml` remain the source of truth for seed/config records.
- `data/upgrade-data.xml` and `data/releases/2.0.0/upgrade-data.xml` contain the curated production upgrade subset generated from `v1.1.1..main`.
- `data/releases/1.1.1/upgrade-data.xml` archives the previous current upgrade data before the 2.0.0 current file was regenerated.
- Candidate records from `ReconciliationCompareScopeFixtureData.xml` remain in `upgrade-data-review.md` for traceability but are excluded from the production load file.

## Validation and rollout notes

- Roll backend and UI together because the UI assumes backend contracts introduced after `v1.1.1`.
- Do not tag while Linear still reports open non-archived Bug issues.
- Full validation state is tracked in `release-checklist.md`; as of this prep pass, release-pack validation is intentionally blocked by open issue count.
- Final compare links should be updated to `v1.1.1...v2.0.0` after actual tags are cut.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
