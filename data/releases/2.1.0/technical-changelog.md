# Technical Changelog For Darpan 2.1.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `2.1.0` is a minor release candidate because it adds backward-compatible order-source extraction, result viewing, route, docs, and setup improvements on top of the existing 2.x contracts.
- The backend baseline is `v2.0.3`; the UI baseline is `v2.0.0`.
- Integration component baselines are `shopify-darpan@v0.1.0`, `darpan-hotwax@v0.1.0`, and `netsuite-darpan@v0.0.1`.

## Source ranges

- Backend: `v2.0.3..HEAD`
- UI: `v2.0.0..HEAD`
- Shopify component: `v0.1.0..HEAD`
- HotWax component: `v0.1.0..HEAD`
- NetSuite component: `v0.0.1..HEAD`
- Backend compare URL: `https://github.com/toaditi/darpan/compare/v2.0.3...main`
- UI compare URL: `https://github.com/toaditi/darpan-ui/compare/v2.0.0...main`

## Backend

### Added
- `DARPAN_ADMIN` security seed group for app-level settings and core configuration.
- `ShopifyCreatedAtWindowPaginator` and tests for Shopify created-at extraction windows.
- Shared facade support tests around tenant/security helper behavior.

### Changed
- Reconciliation facade orchestration is consolidated around saved-run/generic execution helpers.
- Generated output handling is more resilient for saved-run result records and manifest lookup.
- Navigation search, tenant notification, JSON schema, and settings helper behavior are tightened around current facade contracts.
- Production Docker build inputs are parameterized and default to the coordinated release tags for backend and integration components.
- Darpan public/backend docs are aligned with tenant setup, security, production settings, and service-contract changes.

### Fixed
- Darpan system-source values are canonicalized before compare-source persistence.
- Generic reconciliation output handling avoids stale or missing generated-output edge cases.
- API-window handling and saved-run smoke coverage were expanded around current order-source flows.

### Security
- `UgtDarpanPermission` description is generalized from tenant-only permission groups.
- `DARPAN_SUPER_ADMIN` seed copy now focuses on tenant/user setup rather than generic core configuration.

## UI

### Added
- Collapsible JSON viewer components for structured result details.
- Saved-run editor routing helper and tests.
- Run-history and run-result tests for result navigation, generated output, and missing-record handling.

### Changed
- Run history and run result surfaces use more explicit linkability and detail states.
- Reconciliation create/diff flows align with canonical backend system-source values.
- API-backed Run Details system cards route to the matching HotWax or Shopify source config dashboards.
- Auth and route-guard tests reflect the current session/tenant contract.
- Tenant, user, and OMS REST settings UI text and layout are tightened.

### Fixed
- Result detail rendering handles empty and nested JSON values more predictably.
- OMS REST timezone display avoids awkward wrapping.
- Login/session guard tests cover the current redirect behavior.

### Security
- UI permissions remain backend-driven; this candidate only aligns client behavior and tests with the current backend contract.

## Data and configuration

- `data/SecuritySeedData.xml` is the generic source file for the Darpan permission seed changes.
- `data/upgrade-data.xml` and `data/releases/2.1.0/upgrade-data.xml` contain the curated upgrade records generated from `v2.0.3..HEAD`.
- `data/releases/2.0.3/upgrade-data.xml` archives the previous current upgrade-data file.
- The production Dockerfile defaults component refs to `v2.1.0`, `v0.2.0`, `v0.2.0`, and `v0.1.0` for the backend, HotWax, Shopify, and NetSuite components.

## Validation and rollout notes

- Roll backend, UI, Shopify, HotWax, and NetSuite component tags together for this minor candidate; production Docker builds use the coordinated tag refs by default after this cut.
- Public docs can ship independently on `main`, but the docs content describes the same setup surface as this candidate.
- Final compare links should be updated to tag-to-tag links after actual tags are cut.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
