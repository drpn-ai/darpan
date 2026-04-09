# Technical Changelog For Darpan 1.1.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `1.1.0` is a minor release because it completes the native pilot PWA surfaces and backend contract alignment without introducing a planned breaking contract change.

## Source ranges

- Backend: `v1.0.0..main`
- UI: `v1.0.0..main`

## Backend

### Added
- Shared support helpers for settings, reconciliation, dashboard preferences, and rule-engine execution that back the native PWA routes without legacy backend page dependencies.
- Release-scoped upgrade artifacts under `data/releases/1.1.0/` for operator review and targeted upgrades.

### Changed
- `service/facade/AuthFacadeServices.xml`, `SettingsFacadeServices.xml`, `JsonSchemaFacadeServices.xml`, and `ReconciliationFacadeServices.xml` now carry the primary UI-facing contract with thinner, reused Groovy support layers.
- Existing setup data switched from generic `seed` readers to `darpan-seed` / `darpan-seed-initial` so Darpan component setup can load through its dedicated Gradle flow.
- New optional `RunSystemInstance*.xml` bundles add demo/UAT finder data for the pilot PWA without forcing that sample data into the mandatory upgrade path.

### Fixed
- Native PWA flows no longer rely on scattered one-off facade scripts for settings, JSON schema, and reconciliation operations.
- Dashboard pinned mapping preferences now have an explicit backend preference key and save path.

### Security
- `DARPAN_APP` authorization now targets `ADMIN` instead of `ALL_USERS`.
- `DARPAN_AUTH_API` explicitly exposes `facade.AuthFacadeServices.*` to `ALL_USERS` so the stateless pilot login/bootstrap path remains available.

## UI

### Added
- Shared native PWA primitives such as `AppSelect`, `AppTableFrame`, workflow field tests, and dedicated settings workflow pages.
- Native PWA settings dashboards for LLM, SFTP, NetSuite settings, and run settings.

### Changed
- Schema Studio browse/editor/layout/wizard routes now follow the newer static/workflow shell instead of the older page scaffolding.
- Reconciliation home, create-flow, run-history, and run-result surfaces were refreshed to match the current pilot shell and shared UI primitives.
- `App.vue`, routing, and command-palette behavior were updated to treat the native pilot PWA as the primary `main` branch experience.

### Fixed
- Removed legacy auth-required and legacy NetSuite settings pages that no longer matched the shipped pilot experience.
- Replaced older pinned-run helpers with a reusable record-label path used by the current dashboard/result UI.

### Security
- The UI continues to use the stateless auth model introduced in `1.0.0`; this release keeps the browser path on the explicit auth facade instead of reintroducing a session-bound screen flow.

## Data and configuration

- Candidate upgrade records live in `upgrade-data-review.md` and `upgrade-data.xml`.
- Mandatory operator data for existing environments is limited to the auth artifact group/member/authz changes plus the pinned-dashboard user preference enumeration.
- The new `RunSystemInstance*.xml` files are optional sample/demo bundles; they stay out of the mandatory release-scoped upgrade XML.
- Developers and operators should use `./gradlew loadDarpanSetup` for fresh setup so the new `darpan-seed*` reader types are applied in the intended order.

## Validation and rollout notes

- Roll out backend and UI together. The backend auth exposure and preference-key seed changes are part of the same release contract as the native PWA pages.
- Backward compatibility note: legacy backend settings pages are no longer the intended shipped path on `main`; use the native PWA routes after rollout.
- Compare URLs:
  - Backend: `https://github.com/hotwax/darpan/compare/v1.0.0...v1.1.0`
  - UI: `https://github.com/toaditi/darpan-ui/compare/v1.0.0...v1.1.0`
- Linear release tracking: `DAR-139`

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
