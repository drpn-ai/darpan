# Darpan 1.0.0 Release Notes

Release date: `2026-07-03`

## Scope note

Release `1.0.0` is the first release of `drpn-ai/darpan` as a standalone repository.
Repository history was reset to a clean baseline on 2026-07-03 and versioning restarts
here: the retired pre-reset tag series (`v1.0.0`–`v2.1.3`) was deleted and must not be
compared against this or any future release.

Because there is no previous tag, the full baseline is the release content. This tag is
the reproducible-build anchor for production images: `docker/prod/Dockerfile` now pins
`DARPAN_REF=v1.0.0` (it temporarily defaulted to `main` after the reset).

Deferred to later releases: composite-key reconciliation matching, run idempotency keys,
returns reconciliation, and multi-file-source stitching (see the public product roadmap).

## Repo targets

- Backend repo: `drpn-ai/darpan` (`https://github.com/drpn-ai/darpan`)
- Branch: `main`
- Tag: `v1.0.0` on the release-prep commit (exact SHA recorded in `release-checklist.md`)
- Compare range: `1d01750..v1.0.0` (baseline commit to tag; no previous tag exists)
- UI: not included — `darpan-ui` versions and deploys independently.

## User-visible changes

This release establishes the platform baseline rather than a feature delta:

- Multi-system reconciliation of order and inventory data across connected source
  systems (HotWax OMS, NetSuite, Shopify via companion connector components), with
  Spark-based comparison runs, run scheduling, and run/system-instance management.
- Configurable reconciliation rule engine (Drools) with server-side rule generation,
  field-comparison rule types, and per-tenant rule scoping.
- Multi-tenant model with tenant-scoped data access and permission groups
  (`DARPAN_ADMIN`, `DARPAN_SUPER_ADMIN`).
- REST facade API with a versioned, snapshot-checked contract consumed by the Darpan UI.

## Operator-visible changes

- Production image builds must use `--build-arg DARPAN_REF=v1.0.0` or the in-file
  default; all product and framework refs in `docker/prod/Dockerfile` are now pinned to
  immutable tags or SHAs (no mutable-branch refs).
- Runtime baseline: JDK 21, Moqui 4 (embedded Bitronix JTA), Gradle 9. Required JVM
  flags are exported by `docker/entrypoint.sh`; do not strip them.
- CI security gate requires the `NVD_API_KEY` repository secret for OWASP
  dependency-check (Actions caches did not survive the repository reset).

## Upgrade data

- No incremental upgrade records ship with this tag: there is no previous release to
  diff against, and the generic seed files under `data/*.xml` (the source of truth)
  are unchanged since the baseline commit.
- The current load target `data/upgrade-data.xml` is an intentionally empty candidate
  for this release. The pre-reset current file was archived under
  `data/releases/pre-reset/unreleased-post-2.1.3/upgrade-data.xml`, and all pre-reset
  release packs were moved under `data/releases/pre-reset/` (retired numbering — see
  the README there).
- Fresh environments: run the standard full data load (`./gradlew loadDarpanData`).
- Existing environments: no data-load action is required for this tag. Running
  `./gradlew loadDarpanUpgradeData` is safe if convergence needs to be re-asserted;
  it idempotently upserts all `darpan-seed` records.

## Verification

Recorded in `release-checklist.md` alongside the exact commands: release-preflight pack
validation, XML well-formedness checks on every changed XML file, and the backend
compile check (`./gradlew :runtime:component:darpan:compileGroovy`). The full backend
test suite and live-deploy smoke were not re-run for this cut; the tagged tree differs
from the CI-green baseline only in release metadata, data-pack organization, and the
Dockerfile ref pin.

## Deferred items

- Composite-key reconciliation matching (multi-field record identity).
- Run idempotency keys.
- Returns reconciliation and multi-file-source stitching.
- Decision on retiring the old `darpan-ui` v1.x/v2.x tag series.

## Rollback or fallback notes

- No earlier post-reset tag exists. If `v1.0.0` must be rolled back, rebuild the
  production image with `--build-arg DARPAN_REF=<known-good SHA>` (the pre-release
  baseline is `93d98f0`).
- Never fall back to pre-reset tags (`v1.x`/`v2.x`); they no longer exist on the remote.
