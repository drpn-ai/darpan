# Technical Changelog For Darpan 1.0.2

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- Patch hotfix: conf expression fix + companion ref pin; no service, entity, data, or
  facade contract changes.

## Source ranges

- Backend: `v1.0.1..v1.0.2`
- Compare link: `https://github.com/drpn-ai/darpan/compare/v1.0.1...v1.0.2`
- UI: not included

## Backend

### Added

- Release pack `data/releases/1.0.2/`.

### Changed

- `component.xml` `1.0.1` → `1.0.2`; OpenAPI snapshot regenerated (version only,
  65 methods unchanged).
- `docker/prod/Dockerfile`: `DARPAN_REF` → `v1.0.2`, `NETSUITE_DARPAN_REF`
  `v0.1.0` → `v0.2.0` (post-migration tag cut at netsuite-darpan main).

### Fixed

- `docker/MoquiProductionConf.xml` (`8adb751`): `entity_ds_crypt_pass_old`
  default-property used shell `${var:-default}` syntax; Moqui expands the attribute as
  a Groovy GString (SystemBinding), where that parses as a call to a method named `$`
  → `MissingMethodException` in any process without the env var (kubectl-exec data
  loads). Entrypoint's unconditional export masked it for the server. Fixed with
  `${entity_ds_crypt_pass_old ?: entity_ds_crypt_pass}` (SystemBinding resolves
  missing vars to empty string, so Elvis reproduces the intended fallback; verified
  against Groovy 4.0.24 both with `_old` unset and set).

### Security

- None. Crypt-key rotation semantics preserved (decrypt-alt chain unchanged).

## UI

- UI is not included in this release.

## Data and configuration

- No seed or configuration data changes; `data/upgrade-data.xml` remains an empty
  candidate, `1.0.1` current file archived under `data/releases/1.0.1/`.

## Validation and rollout notes

- Tagged without awaiting the full CI suite at the release owner's request (hotfix
  urgency); fast gates (pack validate, XML, compileGroovy, checkApiContract, targeted
  Groovy harness) all passed locally. CI runs on the pushed commit.
- Rollout: rebuild the prod image; no data-load action.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/
