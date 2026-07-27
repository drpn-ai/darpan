# Release Checklist For Darpan 1.1.0

## Linear prework

- Linear prework: N/A — issue-tracker prework retired 2026-07-01; work is tracked
  through git history and PRs.
- In-scope work: all commits in `v1.0.3..main` (backend, 38) and `v2.1.0..main`
  (UI, 72); companion ranges in `technical-changelog.md`.
- Open release issues: none.
- Deferred work: named explicitly in `release-notes.md` (observability watchdog +
  alerting, async saved-run submission, prod run-log routing, rule-sandbox P2,
  returns reconciliation, multi-file-source stitching, UAT auth-posture revert,
  non-root Docker user).

## Release notes

- User-facing release notes drafted: yes — `release-notes.md` (curated, not raw log).
- Operator-visible changes reviewed: yes — upgrade data, connector registry rows,
  transaction-detach behavior, deploy-order and pin-pairing requirements.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes (darpan `v1.0.3...v1.1.0`, darpan-ui `v2.1.0...v2.2.0`).
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes — all 6
  records live in `AutomationSeedData.xml`, `DarpanSystemSourceSeedData.xml`,
  `SecuritySeedData.xml`, `SourceSystemConnectorSeedData.xml`.
- Candidate diff reviewed: yes — record-by-record in `upgrade-data-review.md`;
  no test fixtures or samples included.
- Final load path decided: `./gradlew loadDarpanUpgradeData` on existing
  environments (startup `darpan-seed` load is idempotent where enabled).
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes —
  `data/releases/1.0.3/upgrade-data.xml`.

## Verification

- Backend checks complete: yes — `run_backend_checks.sh` compile step
  (`:runtime:component:darpan:compileGroovy`) BUILD SUCCESSFUL on the bumped tree;
  `xmllint --noout` passed on `component.xml`, `data/upgrade-data.xml`, and the
  release-pack mirror; OpenAPI snapshot JSON parse passed with `info.version` 1.1.0.
- UI checks complete: yes — `run_ui_checks.sh` (`npm run check`: lint + type-check +
  vitest with coverage) on the bumped tree: 93 test files, 694/694 tests passed,
  coverage ratchet floor enforced.
- Full backend Gradle suite: intentionally not rerun at cut time; the release
  commit adds only version metadata and seed/release data on top of `main`, whose
  feature work carried its own green test evidence when it landed (through
  2026-07-27). CI runs on the pushed tag.
- Live/deployed smoke coverage noted: UAT (`app-uat.drpn.ai`) has been running the
  head of this range; saved-run transaction detach and per-session logout were
  deploy-verified there 2026-07-27. Production deploy of the new pins is the
  remaining live path.
- Unverified items called out: production pin deploy not exercised at cut time;
  prod run-log routing gap remains open (deferred list).

## Approval

- Release owner sign-off: aditi.patel@hotwax.co — release cut requested 2026-07-27
  ("create a new minor release").
- Cut/tag blocked until this checklist is fully complete: complete at cut time;
  `release_preflight.py validate` result recorded in the release summary.
