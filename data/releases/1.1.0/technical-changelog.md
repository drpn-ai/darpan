# Technical Changelog For Darpan 1.1.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `1.1.0` is a **minor** release: additive entities, services, seed data, and API
  contract growth (composite-key params, run-status service) with no breaking
  contract changes. Single-field reconciliation output is byte-identical to v1.0.x
  (proved by 974fe9a).
- UI `2.2.0` is a **minor** for the same reason: additive components, routes, and
  contract types; no route or query-state contract breaks.
- Companion minors: `darpan-hotwax v0.4.0`, `shopify-darpan v0.4.0` (new service
  capabilities, no breaking changes).

## Source ranges

- Backend: `v1.0.3..main` → `v1.1.0` (38 commits)
  - https://github.com/drpn-ai/darpan/compare/v1.0.3...v1.1.0
- UI: `v2.1.0..main` → `v2.2.0` (72 commits)
  - https://github.com/drpn-ai/darpan-ui/compare/v2.1.0...v2.2.0
- darpan-hotwax: `v0.3.0..v0.4.0` (4 commits: daa7c55, 8daf228, 2af3d5a, 857d851)
- shopify-darpan: `v0.3.0..v0.4.0` (1 commit: b65fce7)

## Backend

### Composite-key reconciliation
- `RuleSetCompareSourceKeyField` entity for ordered per-side key fields (0f728f7);
  resolution into ordered id specs (1bb0439); Spark `concat_ws` composite
  `compare_id` with U+001F delimiter and null-field guard (8023ea5, 41f17ba).
- `create#RuleSetRun` accepts a composite `primary-id-expressions` array (9fbb0da);
  `save#RuleSetRun` composite support with cross-side key-field count guard
  (b413e80, c9d41ef); composite fields surfaced in saved-run system options
  (de0453a); non-entity source stub guard (d0c0687); `createdDate` preserved on
  recreated composite-key rows (6d17c71).
- Single-field `compare_id` proven unchanged (974fe9a); id-normalizer trims at the
  ingest boundary (41f17ba). Contract regenerated (6b0859f, f55217b).

### Run observability
- `ReconciliationRunStep` timeline entity + run status/error fields (87174a5);
  status/stage classification constants (1ea0be1); run-scoped MDC keys
  `runId`/`stage`/`savedRunId` (bea874f).
- `RunObservability` write API begin/step/heartbeat/end/complete/fail with a
  never-throws contract (433f344, 16d5f92); interactive pipeline instrumented
  (a3dd517); observability gated on pre-validation, NOTIFY isolation (33fbeb9);
  errorMessage truncation at the seam + MDC cleared by `clear()` (cbd9606).
- `get#ReconciliationRunStatus` read service — live status + step timeline
  (aa5324f); run list descriptors expose `currentStage`/`progressPercent` (7b16f4b).

### Verification pass + extraction efficiency
- Missing-diff verification pass (STAGE_VERIFY) re-checks missing-in-remote diff
  rows via the connector registry `lookupServiceName`; extract keep-fields
  projection via `keepFieldsParameterName`/`keepFieldsBase` (3daf3ae).
- Data manager `moveIntoLocation` streaming move for large extracts (89c1d5d).

### Database source type
- `AUT_SRC_DB` source type, `DATABASE` system enum, `databaseSourceQueryId` column
  (0484573); automation validation/execution gates (14e1b45, 13af311, 70dd0f4);
  dispatch wired to `databaseSourceQueryId` (aad5a1c, 6a13751).

### Reliability and auth
- Saved-run execution detached from the caller's 60s request transaction —
  suspend/resume around a transaction-free run body, `runDetachedFromCallerTransaction`
  helper with a 4-test contract (95ec023).
- `logout#Session` is per-session; the cross-session `hasLoggedOut` broadcast is
  removed (8ce62a3); contract synced on the UI side (246763c).

### Build/deploy
- Embedded `docker/` removed; Docker artifacts live only in
  `drpn-ai/darpan-docker-config` (1af89e7). `database-darpan` cloned with pinned
  ref in images (76427ff). Production conf stays MySQL (00e14ba reverts 6fc2701;
  Chelan Postgres lives on the docker-config `chelan` branch).

## UI

### Composite keys
- `WorkflowSelect` chip mode + `WorkflowChipTextInput` (afc8482, 863d7f6); wizard
  chip selection + array-valued `primary-id-expression` draft support (9e1ae87,
  6b1baff, 538ee03); run-settings chip editing + saved-run hydration (7937cdf,
  41b3801); legacy mapping runs block extra chips (71c401e); summary tiles show all
  composite fields (4d71e5d).

### Run observability (Phase 4)
- Live run status + step timeline (1e54ccf), collapsed by default (1b583da); live
  extract progress, VERIFY stage label, run-completion refresh (050b3ed); facade
  contract sync (6df8ae7).

### Error-state system
- `ErrorState` component + variant presets (7eab232, 06703fd); transparent global
  error boundary (3774973); `reportError` sink with dedupe + ring buffer (d6c8ecb,
  4a46243); 404 catch-all behind auth (b8dec1b, c9cea78); hard-403 access-denied
  route (00ab477); session expiry → login with return path (e408c35); offline
  banner with auto-recover (f75dd11); auto-retry idempotent reads, never writes
  (3cc7ffe, f6e2357); EmptyState icon + permission-gated CTA (559f2e2).

### API contract typing
- TS types generated from the backend OpenAPI 3.1 snapshot (9834133); drift guard —
  facade methods must exist in the contract (95ad8c6); 24+ response types migrated
  (e4865e3, af3702d, 5f504ce); SPA↔facade response-shape contract tests (2a1051a).

### Performance and state
- Near-instant page loads: cache headers, non-blocking auth, preconnect/prefetch,
  local fonts (4b87096); warm-cache recent run results (6a22492); TanStack Query
  replaced with Pinia stores (775f3de, d97b17f); run-result page decomposed into 4
  composables (643bb35).

### Security
- Hardened web security headers + CSP (f4a5619); CI security gate: secrets + SCA +
  lockfile (8f347b9, fb5922e); cookie-auth mode for UAT builds (a9dc430, 81a2fba)
  with a temporary header-auth switch pending the ingress ACAO fix (3e734c5);
  bearer token held in sessionStorage (b60022a); lockfile refresh clearing audit
  findings (7daf78c); ESLint bans explicit `any` (b8851bd); coverage ratchet floor
  (068d6bf). Public copy stays generic per the public-copy rule.

### Deploy targets
- `sm-darpan` env mode + hosting target (03e45e5, b8ccbd4).

### Fixes
- Single-day exclusive-end date range collapse (bb59da3); create-flow wizard drafts
  discarded on exit (649b477); UTC timezone label in the automation schedule editor
  (580ffab); tenant-switch flash removed (5f697aa); Shopify timezone select sizing
  (8126f5b); wizard action centering (1a18f10).

## Data and configuration

- Generic source data files under `runtime/component/darpan/data/*.xml` are the
  source of truth for seeded data; the 6 upgrade records were generated from the
  `v1.0.3..main` seed diff (see `upgrade-data-review.md`): `AUT_SRC_DB` +
  `DATABASE` enums, `RuleSetCompareSourceKeyField` artifact-group member + tenant
  entity filter, OMS connector keep-fields columns, Shopify connector
  `lookupServiceName`.
- `component.xml` 1.0.3 → 1.1.0; OpenAPI snapshot `info.version` → 1.1.0
  (version-only regen, method set unchanged).
- UI `package.json`/`package-lock.json` 2.1.0 → 2.2.0.
- Production pins in `darpan-docker-config/docker/prod/Dockerfile`:
  `DARPAN_REF=v1.1.0`, `DARPAN_HOTWAX_REF=v0.4.0`, `SHOPIFY_DARPAN_REF=v0.4.0`;
  `NETSUITE_DARPAN_REF=v0.2.0` unchanged.

## Validation and rollout notes

- Backend compile green; XML/JSON parse checks green; UI `npm run check` green
  (93 test files, 694 tests, coverage floor enforced). Feature commits carried
  their own test evidence when they landed on `main`.
- Deploy backend before UI (UI polls `get#ReconciliationRunStatus`); move all
  component pins together — seeded connector rows reference v0.4.0 component
  services.
- Operator load on existing environments: `./gradlew loadDarpanUpgradeData`.
- Schema changes are additive; do not roll back to v1.0.x after composite-key rule
  sets exist.

## References

- https://github.com/drpn-ai/darpan/compare/v1.0.3...v1.1.0
- https://github.com/drpn-ai/darpan-ui/compare/v2.1.0...v2.2.0
- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/
