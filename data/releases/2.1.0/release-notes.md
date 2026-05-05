# Darpan 2.1.0 Release Notes

Release prep date: `2026-05-05`

## Scope note

Release `2.1.0` is a minor release candidate prepared from the current changed Darpan UI, backend, integration-component, and public-docs work. It keeps the 2.x product contract intact while adding order-source extraction hardening, result-viewing improvements, tenant/admin permission cleanup, and split setup documentation.

This is a prep pack only. The `v2.1.0`, `v0.2.0`, and `v0.1.0` tags, GitHub releases, deploys, and live smoke tests have not been cut.

## Repo targets

- Backend candidate repo: `toaditi/darpan`; release-target remote also configured as `hotwax/darpan`
- Backend compare range: `v2.0.3..HEAD`
- Backend candidate branch: `main`
- Backend code candidate commit before this release pack: `15d90ad`
- Backend tag target when approved: `v2.1.0`
- UI candidate repo: `toaditi/darpan-ui`
- UI compare range: `v2.0.0..HEAD`
- UI candidate branch: `main`
- UI code candidate commit: `90fab62`
- UI tag target when approved: `v2.1.0`
- Integration component tag targets: `shopify-darpan@v0.2.0`, `darpan-hotwax@v0.2.0`, and `netsuite-darpan@v0.1.0`
- Public docs candidate repo: `toaditi/darpan-docs`
- Public docs branch: `main`

## User-visible changes

- Run history and run-result views are easier to inspect, with result-row links, more resilient missing-output states, and collapsible JSON detail rendering.
- Reconciliation create and diff flows use the canonical Darpan system-source values consistently across saved-run and API-backed workflows.
- API source system cards in Run Details now link back to the relevant HotWax or Shopify source configuration.
- OMS REST settings keep the timezone value readable and preserve the existing settings flow behavior.
- Tenant and user settings copy/permission behavior is aligned with the current role vocabulary.
- Public setup docs now separate HotWax setup from Shopify setup instead of routing users through the older combined Shopify OMS guide.

## Operator-visible changes

- Darpan backend `component.xml` and Darpan UI `package.json` / `package-lock.json` are aligned to `2.1.0`.
- `shopify-darpan`, `darpan-hotwax`, and `netsuite-darpan` component metadata are prepared for `0.2.0`, `0.2.0`, and `0.1.0` respectively.
- Backend Docker build refs default to `main` for UAT pre-cut testing. Pass tag refs explicitly after the component release tags are created.
- Darpan inventory adjustment retrieval code is removed from the core Darpan component and remains in the NetSuite integration boundary.
- Shopify order extraction adds the bulk-operation client path and related tests.
- HotWax OMS REST order extraction adds pagination/window handling support.
- Darpan security seed data adds `DARPAN_ADMIN`, clarifies `DARPAN_SUPER_ADMIN`, and updates the permission-group enum description.

## Upgrade data

- Generic source data files under `runtime/component/darpan/data/*.xml` are the source of truth.
- Current upgrade file: `runtime/component/darpan/data/upgrade-data.xml`
- Release-pack mirror: `data/releases/2.1.0/upgrade-data.xml`
- Candidate review report: `data/releases/2.1.0/upgrade-data-review.md`
- Previous current upgrade data was archived under `data/releases/2.0.3/upgrade-data.xml` during this prep pass.
- Operator load command when this release is approved: `./gradlew -b runtime/component/darpan/build.gradle loadDarpanUpgradeData`

## Verification

- Local verification is recorded in `release-checklist.md`.
- Tag creation, GitHub release publication, deployment, and live/deployed smoke testing were not performed in this prep-only pass.

## Deferred items

- Actual tag/release cut is deferred by request.
- Tag-pinned Docker builds that use `v2.1.0`, `v0.2.0`, and `v0.1.0` refs are deferred until those tags exist.
- Production deploy and live smoke validation remain separate release-cut activities.

## Rollback or fallback notes

- Cut the release only after all component tags can be created from the pushed `main` refs listed in the checklist.
- If order-source extraction, run-result navigation, or tenant/admin permission checks fail during release smoke, hold the tag cut and keep the current production tags in place.
