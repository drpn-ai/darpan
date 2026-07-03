# Darpan 1.1.0 Release Notes

Release date: `2026-04-09`

## Scope note

Release `1.1.0` covers:

- completion of the native pilot PWA migration on `main`, including the new settings dashboards/workflows, refreshed schema-management surfaces, and updated reconciliation result/history pages.
- explicit deferrals to the next release remain `DAR-115`, `DAR-117`, and `DAR-134`, plus deployed-environment smoke validation outside this local release cut.

## Repo targets

- Backend repo path or remote: `hotwax/darpan`
- Backend branch/tag target: `main` -> `v1.1.0`
- Backend compare range: `v1.0.0..main`
- Backend release pack path: `data/releases/1.1.0`
- UI repo path or remote: `toaditi/darpan-ui`
- UI branch/tag target: `main` -> `v1.1.0`
- UI compare range: `v1.0.0..main`

## User-visible changes

Curate from Git history, then rewrite for end users:
- The pilot UI now ships its native PWA surfaces for settings, schema management, reconciliation run history, and reconciliation results on `main`.
- Settings moved to dedicated dashboard and workflow pages for LLM, SFTP, NetSuite auth, NetSuite endpoints, and run settings.
- Schema Studio and reconciliation pages were realigned to the newer static/workflow shell so the pilot experience is consistent across the native PWA.
- Shared shell, select, table-frame, and workflow primitives replaced older one-off page scaffolding across the shipped pilot UI.

## Operator-visible changes

- Backend component version is `1.1.0` in `component.xml`; UI package version is `1.1.0` in both `package.json` and `package-lock.json`.
- Backend setup seed files now use `darpan-seed` and `darpan-seed-initial`; fresh or rebuilt environments should load them through `./gradlew loadDarpanSetup`.
- `SecuritySeedData.xml` now grants `ALL_USERS` access to `facade.AuthFacadeServices.*`, narrows `DARPAN_APP` access to `ADMIN`, and adds the pinned-dashboard preference key used by the native PWA.
- New `RunSystemInstance*.xml` files provide optional demo/UAT sample data for the native PWA run-system finder surfaces; they are not required for a standard production upgrade.

## Upgrade data

- Candidate upgrade file: `upgrade-data.xml`
- Candidate review report: `upgrade-data-review.md`
- Existing environments that do not automatically pick up the required auth/user-preference records should load `upgrade-data.xml` as `seed`.
- Fresh or fully reloaded environments should keep using `./gradlew loadDarpanSetup`; the optional run-system sample bundles stay in their source seed files and are not part of the mandatory release-scoped upgrade XML.

## Verification

- `plugins/darpan-workflows/scripts/run_ui_checks.sh`
- `plugins/darpan-workflows/scripts/run_backend_checks.sh runtime/component/darpan/component.xml runtime/component/darpan/data/releases/1.1.0/upgrade-data.xml runtime/component/darpan/data/releases/1.1.0/release-notes.md runtime/component/darpan/data/releases/1.1.0/technical-changelog.md`
- Intentionally not rerun in this release cut: deployed-environment smoke validation from `LIVE_TESTING.md`.

## Deferred items

- `DAR-115` frontend ui: align Schema Studio pages with static page design system
- `DAR-117` frontend ui: schema library dashboard surface with workflow-based create and edit
- `DAR-134` combine NetSuite auth and endpoint settings into one dashboard with one-step workflows
- deployed-environment smoke validation after tag publish

## Rollback or fallback notes

- Roll back UI and backend together to `v1.0.0` if the native PWA auth, settings save flows, or run history/result pages regress in the target environment.
- Treat missing `ALL_USERS` access to `facade.AuthFacadeServices.*`, broken settings persistence, or missing reconciliation result/history data as no-go conditions for rollout.
