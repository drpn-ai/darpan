# Darpan 1.1.0 Release Notes

Release date: `2026-07-27`

## Scope note

`v1.1.0` is the first minor release on the post-reset line. Headlines:

- **Composite-key reconciliation matching**: rule sets can match records on multiple
  key fields (create and edit paths), with single-field behavior byte-identical to
  v1.0.x.
- **Reconciliation run observability**: live run status, a persisted step timeline,
  stage/progress in run lists, and run-scoped structured log keys.
- **Missing-in-Shopify verification pass**: diffs reported as missing in Shopify are
  re-checked with point lookups before they reach the result, removing false rows
  caused by bulk-export index skew.
- **Database source type**: `AUT_SRC_DB` automation source and `DATABASE` system
  source flow through validation, dispatch, and saved-run gates (pairs with the
  `database-darpan` extractor component).
- **Long-run reliability**: saved-run execution is detached from the caller's 60s
  request transaction, so runs whose extraction takes longer than a request window
  complete instead of rolling back.
- **Per-session sign-out**: `logout#Session` ends only the calling session; other
  active sessions on the same account are unaffected.

UI `v2.2.0` ships alongside: composite-key chip editing, live run status + step
timeline, a full error-state system (404/403/session-expiry/offline), typed API
contract generated from the backend OpenAPI snapshot, and page-load performance work.

## Repo targets

- Backend repo: `drpn-ai/darpan` (`https://github.com/drpn-ai/darpan`)
- Backend branch `main`; tag `v1.1.0`; compare `v1.0.3...v1.1.0` (38 commits + release commit)
- UI repo: `drpn-ai/darpan-ui` (`https://github.com/drpn-ai/darpan-ui`)
- UI branch `main`; tag `v2.2.0`; compare `v2.1.0...v2.2.0` (72 commits + version bump)
- Companion component tags cut with this release:
  - `darpan-hotwax v0.4.0` (streaming + gzip + concurrent OMS order extraction,
    keep-fields record projection, extract progress heartbeats)
  - `shopify-darpan v0.4.0` (`lookup#ShopifyOrderIds` point-existence check used by
    the verification pass)
- `netsuite-darpan` unchanged at `v0.2.0`.
- Production pins (`darpan-docker-config/docker/prod/Dockerfile`): `DARPAN_REF=v1.1.0`,
  `DARPAN_HOTWAX_REF=v0.4.0`, `SHOPIFY_DARPAN_REF=v0.4.0`; `NETSUITE_DARPAN_REF=v0.2.0`
  unchanged. Move all pins together — v1.1.0 seeds connector rows that reference
  services introduced in the two v0.4.0 components.

## User-visible changes

- Rule sets accept multiple primary-ID fields entered as chips in the wizard and in
  saved-run settings; run summaries show every composite key field; legacy
  single-field mapping runs refuse extra chips instead of silently dropping them.
- The runs list shows the current stage and progress of an executing run; the run
  page shows a live step timeline (collapsed by default) and refreshes when the run
  completes.
- Missing-in-Shopify diff rows are verified with point lookups before being
  reported; a VERIFY stage appears in the run timeline when it runs.
- Automation schedule editor labels schedule times as UTC.
- Signing out ends only the current session.
- Session expiry routes to login and returns to the original page after sign-in;
  hard 403s land on an access-denied state; unknown routes get a proper 404; an
  offline banner appears when the connection drops and auto-recovers.
- Single-day API date ranges display as one date instead of an exclusive-end pair.
- Page loads are substantially faster (caching, non-blocking auth, font/asset
  preloading).

## Operator-visible changes

- New entity `darpan.rule.RuleSetCompareSourceKeyField` (composite key fields) with
  tenant scoping and DARPAN_APP authz delivered via upgrade data.
- New entity `darpan.reconciliation.ReconciliationRunStep` plus run status/error
  fields persist the run timeline; `get#ReconciliationRunStatus` serves live status;
  run list descriptors expose `currentStage`/`progressPercent`.
- Saved-run execution suspends the caller's transaction and runs transaction-free
  (`runDetachedFromCallerTransaction`), so extractions longer than the 60s request
  window no longer roll the run back. Submission is still synchronous — async
  submission is deferred.
- Source connector registry rows gain `keepFieldsParameterName`/`keepFieldsBase`
  (OMS extract projection) and `lookupServiceName` (Shopify verify pass); both ship
  in upgrade data.
- `AUT_SRC_DB` automation source and `DATABASE` system source enums are seeded;
  automation dispatch reads `databaseSourceQueryId`.
- Data manager `moveIntoLocation` streams large extract files instead of buffering.
- Run-scoped MDC keys (`runId`, `stage`, `savedRunId`) are present in backend logs.
  Production log routing for run logs is a known gap — see Deferred items.
- The component no longer carries a `docker/` directory; production images build
  from `darpan-docker-config` with the pins listed above.

## Upgrade data

- Current load target `runtime/component/darpan/data/upgrade-data.xml` contains 6
  records: 2 enums (`AUT_SRC_DB`, `DATABASE`), 2 security records for
  `RuleSetCompareSourceKeyField` (artifact group member + tenant entity filter), and
  2 modified `SourceSystemConnector` rows (OMS keep-fields, Shopify lookup service).
- Every record is present in its generic source seed file; the candidate was
  generated from the `v1.0.3..main` seed diff (see `upgrade-data-review.md`).
- The previous current file was archived at `data/releases/1.0.3/upgrade-data.xml`.
- Operator action on existing environments: `./gradlew loadDarpanUpgradeData`, or
  rely on the idempotent `darpan-seed` startup load where enabled.

## Verification

- `xmllint --noout` on `component.xml`, `data/upgrade-data.xml`, and the release-pack
  mirror: passed.
- OpenAPI snapshot version bump parse-checked (`info.version` 1.1.0): passed.
- Backend compile (`:runtime:component:darpan:compileGroovy` via
  `run_backend_checks.sh`): BUILD SUCCESSFUL.
- UI `npm run check` (lint + type-check + vitest with coverage) on the bumped tree:
  recorded in `release-checklist.md`.
- `release_preflight.py validate --version 1.1.0`: recorded in `release-checklist.md`.
- Full backend Gradle test suite: not rerun at cut time — the release commit touches
  only version metadata and seed/release data on top of `main`, whose feature commits
  were test-verified when they landed (546+ backend tests across the composite-key,
  observability, and verify-pass work; latest green runs 2026-07-27).
- Live smoke: UAT (`app-uat.drpn.ai`) has been running the head of this range;
  the saved-run detach fix and per-session logout were deploy-verified there on
  2026-07-27. Production deploy of the new pins is the remaining real-world path.

## Deferred items

- Observability watchdog (stale-run detection) and alerting phases (P3/P5 of the
  observability plan).
- Async saved-run submission with UI error surfacing for submission failures.
- Production log routing for run logs (empty-log symptom in prod).
- Out-of-process rule-evaluation sandbox (P2 hardening).
- Returns reconciliation and multi-file-source stitching (public roadmap).
- UAT auth posture: re-enable cookie auth together with the ingress ACAO fix, then
  drop the temporary backend CORS allowlist (order matters).
- Reinstate the non-root Docker user once the fsGroup change lands.

## Rollback or fallback notes

- Backend schema changes are additive (new entity, new columns); rolling back to
  `v1.0.3` leaves them in place harmlessly. Do not roll back after composite-key
  rule sets exist — v1.0.x cannot read multi-field key definitions.
- Deploy order within the release: backend first, then UI. UI `v2.2.0` polls
  `get#ReconciliationRunStatus`, which older backends do not serve.
- Keep the four component pins moving together; mixing `v1.1.0` darpan with
  `v0.3.0` connectors breaks the verify pass and the OMS keep-fields projection at
  dispatch time.
