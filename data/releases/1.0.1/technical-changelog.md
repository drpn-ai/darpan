# Technical Changelog For Darpan 1.0.1

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- Patch: build-infrastructure fix only (Docker companion ref pins + release metadata);
  no service, entity, data, or facade contract changes.

## Source ranges

- Backend: `v1.0.0..v1.0.1`
- Compare link: `https://github.com/drpn-ai/darpan/compare/v1.0.0...v1.0.1`
- UI: not included

## Backend

### Added

- Release pack `data/releases/1.0.1/`.

### Changed

- `component.xml` version `1.0.0` → `1.0.1`; OpenAPI snapshot regenerated
  (`info.version` only, 65 methods unchanged).
- `docker/prod/Dockerfile`: `DARPAN_HOTWAX_REF` `v0.2.0` → `v0.3.0`,
  `SHOPIFY_DARPAN_REF` `v0.2.1` → `v0.3.0` (pre-migration tags declare the removed
  `:runtime:component:moqui-atomikos` project and fail Gradle evaluation);
  `DARPAN_REF` → `v1.0.1`. `NETSUITE_DARPAN_REF` stays `v0.1.0` (no `build.gradle`
  at that tag; nothing to evaluate).
- `AutomationEntityContractTests` + `docs/code-map.md` + `entity/JsonSchemaEntities.xml`
  path references updated for the pre-reset pack archive (shipped on main between the
  two tags; part of this compare range).
- `v1.0.0` pack updated in place with post-release notes documenting the broken
  companion pins in that tag's tree.

### Fixed

- Production image build from a tagged tree (failed at
  `darpan-hotwax/build.gradle:38` on `v1.0.0` pins).

### Security

- None. Ref-pin posture unchanged: all product/framework refs immutable except the
  documented `MOQUI_GQL_REF=main` phase-0 exception.

## UI

- UI is not included in this release.

## Data and configuration

- No seed or configuration changes; `data/upgrade-data.xml` remains an empty candidate
  and the `1.0.0` current file is archived under `data/releases/1.0.0/`.

## Validation and rollout notes

- No compatibility concerns; image rebuild is the only rollout action.
- Companion tags `darpan-hotwax v0.3.0` / `shopify-darpan v0.3.0` were cut at the
  component mains that darpan CI clones (`--branch main`) and proved green.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/
