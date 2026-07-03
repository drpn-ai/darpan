# Release Checklist For Darpan 1.0.0

## Linear prework

- Issue-tracker prework: not required — the Linear-first rule was retired 2026-07-01;
  release scope is tracked in this pack and in git history.
- Included work: full post-reset baseline (`1d01750`) plus `a7d0782` (CI NVD key fix)
  and `93d98f0` (docker ref-pin stabilization).
- Open release issues: none.
- Deferred items: recorded in `release-notes.md` (composite-key matching, idempotency
  keys, returns reconciliation, multi-file-source stitching, darpan-ui old-tag decision).

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`.
- Operator-visible changes reviewed: yes (Dockerfile ref pins, JDK 21/Moqui 4 baseline,
  NVD_API_KEY secret requirement).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URL captured: `https://github.com/drpn-ai/darpan/compare/1d01750...v1.0.0`
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: n/a — no upgrade
  records in this release (no seed diff since baseline).
- Candidate diff reviewed: yes — empty; see `upgrade-data-review.md`.
- Final load path decided: fresh environments `./gradlew loadDarpanData`; existing
  environments no action.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml` (empty candidate)
- Previous upgrade data archived: yes — pre-reset packs under `data/releases/pre-reset/`,
  unreleased post-v2.1.3 current file at
  `data/releases/pre-reset/unreleased-post-2.1.3/upgrade-data.xml`.

## Verification

- Release preflight pack validated: `release_preflight.py validate --version 1.0.0` — passed.
- XML well-formedness checked for every changed XML file (`component.xml`,
  `data/upgrade-data.xml`, pack mirror): passed.
- Backend compile check: `./gradlew :runtime:component:darpan:compileGroovy` via
  `run_backend_checks.sh` — passed.
- API contract gate: `./gradlew :runtime:component:darpan:checkApiContract` — passed
  (65 methods, snapshot regenerated for the version bump).
- Full backend test suite: first release-prep commit (`a6811fd`) FAILED CI — 5
  `AutomationEntityContractTests` failures caused by the pre-reset pack move (the test
  pins the archived `2.0.3` upgrade pack as a historical contract source). Fixed by
  re-pointing the path to `data/releases/pre-reset/2.0.3/upgrade-data.xml` (plus the
  same path updates in `docs/code-map.md` and a `JsonSchemaEntities.xml` comment);
  verified by a focused local run and by CI on the final tagged commit.
- Not verified for this cut: live-deploy smoke (no deployed behavior changes in range).

## Approval

- Release owner: aditi.patel@hotwax.co (requested the prod tag 2026-07-03).
- Tag placement: `v1.0.0` on the final release-prep commit on `main` (the archived-pack
  path fix on top of `a6811fd`), re-pointed after CI verified it; the initial tag on
  `a6811fd` was moved before any GitHub release or deploy consumed it.
