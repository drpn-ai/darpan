# Technical Changelog For Darpan 1.0.3

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- Patch hotfix: fresh-MySQL provisioning fixes (schema column types, seed record
  ordering, ops runbook env). No service, facade contract, or behavioral changes for
  running environments.

## Source ranges

- Backend: `v1.0.2..v1.0.3`
- Compare link: `https://github.com/drpn-ai/darpan/compare/v1.0.2...v1.0.3`
- UI: not included

## Backend

### Added

- Release pack `data/releases/1.0.3/`.

### Changed

- `component.xml` `1.0.2` → `1.0.3`; OpenAPI snapshot regenerated (version only,
  method set unchanged).
- `docker/prod/Dockerfile`: `DARPAN_REF` → `v1.0.3`; companion refs unchanged.
- `docker/Dockerfile` + `docker/prod/Dockerfile` (`b7a894d`): `DB_LOAD` runbook env
  now carries `darpan-seed-initial,darpan-seed` inside `-Ptypes` and drops `-Praw`.
  The old prod value appended the darpan types to `-Praw`, defining a Gradle property
  literally named `raw,darpan-seed-initial,darpan-seed`; no load task reads a `raw`
  property at all, so neither raw mode nor the darpan seed types ever applied and
  fresh deploys came up without darpan seed data.

### Fixed

- `entity/ReconciliationEntities.xml` (`5b059e9`): `NsRestletConfig` large-payload
  fields (`endpointUrl`, `headersJson`, `apiToken`, `tokenUrl`, `privateKeyPem`)
  `text-long` → `text-very-long`. Five `VARCHAR(4095)` utf8 columns cost ~61KB of
  MySQL's 65535-byte InnoDB row budget, so `CREATE TABLE NS_RESTLET_CONFIG` failed on
  every MySQL environment (H2 has no row limit and hid it), cascading into
  `ns_restlet_config doesn't exist` FK errors from `RECONCILIATION_AUTOMATION_SOURCE`
  and `RULE_SET_COMPARE_SOURCE`. `text-very-long` maps to a LOB stored off-page. No
  ALTER migration: Moqui's EntityDbMeta only adds columns, never alters them, but no
  MySQL environment has the old table (creation always failed) — it is created fresh
  on next boot. H2 dev databases keep `VARCHAR(4095)` until recreated.
- `data/SecuritySeedData.xml` (`9df7029`): UserGroup definitions (and
  `UgtDarpanPermission`) moved above every ArtifactAuthz that references them. A data
  file loads in document order inside one transaction; on a database where FK
  constraints already exist (tables created by a prior server boot), the forward
  reference from `ArtifactAuthz DARPAN_SCREEN_UI_SUPER_ADMIN` (was line 61) to
  `UserGroup DARPAN_SUPER_ADMIN` (was line 205) hit
  `SQLIntegrityConstraintViolationException` and rolled back the entire file, leaving
  fresh environments with no darpan security seed. Full fresh loads never saw this
  because table/FK creation and the data load interleave differently there.

### Security

- No permission or authz semantics changed; `SecuritySeedData.xml` records are
  identical, only reordered (verified: release preflight found zero added/modified
  candidate records in range).

## UI

- UI is not included in this release.

## Data and configuration

- Generic source data files under `runtime/component/darpan/data/*.xml` are the source of truth for seeded data.
- Candidate upgrade records generated into `upgrade-data-review.md`: none in range.
- Schema change operators must know: `NS_RESTLET_CONFIG` column types (see Fixed);
  no data-load action beyond re-running darpan-seed where a pre-1.0.3 load rolled back.

## Validation and rollout notes

- Backward compatibility: no contract or behavior change for running environments;
  rollback caveat in `release-notes.md` (do not roll back environments provisioned
  on 1.0.3 to 1.0.2 — fresh-MySQL provisioning is broken there).
- Rollout: rebuild prod images with `DARPAN_REF=v1.0.3` (first consumer:
  `sm-darpan-maarg`).

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
