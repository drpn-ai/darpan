# Technical Changelog For Darpan 1.0.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `1.0.0` restarts the version series after the 2026-07-03 history reset. `drpn-ai/darpan`
  was recreated as a standalone repository on a single-commit baseline whose tree is
  byte-identical to the old main tip; the pre-reset tag series (`v1.0.0`–`v2.1.3`) was
  deleted. Treated as a major version because it is the first release of the new series
  and the reproducible-build anchor for prod images — not because of breaking API changes
  (the facade contract is unchanged from the baseline).

## Source ranges

- Backend: `1d01750..v1.0.0` (baseline commit to tag; no previous tag exists)
- Compare link: `https://github.com/drpn-ai/darpan/compare/1d01750...v1.0.0`
- UI: not included

## Backend

### Added

- Release pack `data/releases/1.0.0/` (notes, changelog, upgrade-data review, checklist).
- `data/releases/pre-reset/README.md` documenting the retired pre-reset pack numbering.

### Changed

- `component.xml` version `2.1.2` → `1.0.0` (was stale since before the reset).
- `docker/prod/Dockerfile`: `DARPAN_REF` re-pinned from `main` (temporary post-reset
  posture) to `v1.0.0`; closes the mutable-ref supply-chain regression flagged after
  `93d98f0`. All other refs already pinned (moqui-framework SHA `d12a86e`, moqui-runtime
  `v3.9.9`, moqui-sftp `v1.0.3`, darpan-hotwax `v0.2.0`, shopify-darpan `v0.2.1`,
  netsuite-darpan `v0.1.0`).
- Pre-reset release packs moved from `data/releases/<version>/` to
  `data/releases/pre-reset/<version>/`; the unreleased post-v2.1.3 current upgrade file
  archived as `data/releases/pre-reset/unreleased-post-2.1.3/upgrade-data.xml`.
- `data/upgrade-data.xml` re-seeded as the (empty) `1.0.0` candidate.
- `AutomationEntityContractTests` (and path mentions in `docs/code-map.md`,
  `entity/JsonSchemaEntities.xml`) re-pointed to the archived pack location
  `data/releases/pre-reset/2.0.3/…` — the pack move broke the test's hardcoded
  historical-contract path and failed CI on the first release-prep commit.

### Fixed

- `a7d0782` fix(ci): pass `NVD_API_KEY` to the OWASP dependency-check step (required
  after the reset destroyed Actions caches).

### Security

- Prod image supply chain: all product and framework clone refs in
  `docker/prod/Dockerfile` resolve to immutable tags or SHAs; builds are reproducible
  without build-arg overrides. Sole remaining branch ref is `MOQUI_GQL_REF=main`
  (Phase-0/1 component, not in the public tier; in-file note requires a tag pin before
  public exposure).

## UI

- UI is not included in this release.

## Data and configuration

- Generic source data files under `runtime/component/darpan/data/*.xml` are the source of truth for seeded data.
- No seed records were added or modified between the baseline commit and this tag
  (`upgrade-data-review.md`); the current load target `data/upgrade-data.xml` ships empty.
- No schema, job, remote-endpoint, or auth-exposure changes in this range.

## Validation and rollout notes

- Backward compatibility: no service, entity, or facade contract changes in the range;
  the tag is a metadata/organization cut on the CI-green baseline.
- Rollout: rebuild the prod image (in-file defaults suffice); no data load required for
  existing environments.
- Verification commands and results are recorded in `release-checklist.md`.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
