# Darpan 1.1.1 Release Notes

Release date: `2026-04-09`

## Scope note

Release `1.1.1` covers:

- a backward-compatible patch cut that finishes schema-system capture hardening in the pilot UI and aligns backend contract/docs with the shipped schema and text-payload behavior.
- explicit deferrals to the next release remain `DAR-115` plus deployed-environment smoke validation after tag publication.

## Repo targets

- Backend repo path or remote: `hotwax/darpan`
- Backend branch/tag target: `main` -> `v1.1.1`
- Backend compare range: `v1.1.0..main`
- Backend release pack path: `data/releases/1.1.1`
- UI repo path or remote: `toaditi/darpan-ui`
- UI branch/tag target: `main` -> `v1.1.1`
- UI compare range: `v1.1.0..main`

## User-visible changes

Curate from Git history, then rewrite for end users:
- Schema Studio create and edit flows now require an explicit system selection and preserve that schema-owned system in both the wizard and editor.
- Sample-derived schema verification now lets users toggle required fields before save and writes those required markers back into the stored schema text.
- Inline dropdown menus now dismiss predictably when another inline menu or the Ask Darpan command bubble opens, reducing overlapping popovers and stale open states in the pilot UI.

## Operator-visible changes

- Backend component version is `1.1.1` in `component.xml`; UI package version is `1.1.1` in both `package.json` and `package-lock.json`.
- No new seed or config records ship in this patch release; operator upgrade data remains empty for `1.1.1`.
- Roll out backend and UI together if you rely on schema sample upload or schema-system capture flows; the backend text-input validation hardening and UI schema-save flow are intended to ship as one patch.

## Upgrade data

- Candidate upgrade file: `upgrade-data.xml`
- Candidate review report: `upgrade-data-review.md`
- No release-scoped `seed` or `seed-initial` load is required for `1.1.1`; `upgrade-data.xml` is intentionally empty because no added or modified seed/config records were detected.
- Fresh or rebuilt environments should continue using `./gradlew loadDarpanSetup`; this patch does not introduce a new setup or load path.

## Verification

- `plugins/darpan-workflows/scripts/run_ui_checks.sh`
- `plugins/darpan-workflows/scripts/run_backend_checks.sh runtime/component/darpan/component.xml runtime/component/darpan/entity/JsonSchemaEntities.xml runtime/component/darpan/service/facade/ReconciliationFacadeServices.xml runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml runtime/component/darpan/data/releases/1.1.1/release-notes.md runtime/component/darpan/data/releases/1.1.1/technical-changelog.md`
- Intentionally not rerun in this release cut: deployed-environment smoke validation from `LIVE_TESTING.md`.

## Deferred items

- `DAR-115` frontend ui: align Schema Studio pages with static page design system
- deployed-environment smoke validation after tag publish

## Rollback or fallback notes

- Roll back backend and UI together to `v1.1.0` if schema create/edit flows fail to persist `systemEnumId`, sample-based schema saves regress, or inline menus still block command-palette usage.
- Treat HTML-validation failures on literal text payloads or missing schema-system capture in the wizard/editor as no-go conditions for rollout.
