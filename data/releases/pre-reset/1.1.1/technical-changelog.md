# Technical Changelog For Darpan 1.1.1

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `1.1.1` is a patch release because it hardens released schema-management and inline-menu behavior without introducing a planned breaking contract change.

## Source ranges

- Backend: `v1.1.0..main`
- UI: `v1.1.0..main`

## Backend

### Added
- No new backend service surface or seed bundle ships in this patch beyond the release artifacts under `data/releases/1.1.1/`.

### Changed
- `service/facade/ReconciliationFacadeServices.xml` and `service/reconciliation/ReconciliationGenericServices.xml` now declare literal text payload inputs with `allow-html="any"` so JSON or text bodies containing `<` and `>` pass Moqui validation in the intended reconciliation flows.
- `entity/JsonSchemaEntities.xml` now relies on Moqui's built-in update stamp instead of carrying a duplicate explicit `lastUpdatedStamp` field definition, and the schema docs were updated to match the shipped runtime model.

### Fixed
- Schema metadata documentation now matches the live entity contract, reducing drift around `createdDate` and `lastUpdatedStamp`.
- Runtime-generated `moqui_logs/` output is ignored so backend verification artifacts do not dirty release branches during local release cuts.

### Security
- No new authz or exposure changes ship in this patch release.

## UI

### Added
- Schema wizard now includes a required system-selection step driven by `DarpanSystemSource` enum options, and the schema editor exposes the saved schema system through an inline selector.
- Shared inline-menu events coordinate `AppSelect`, `WorkflowSelect`, and the Ask Darpan launcher so only one inline menu stays open at a time.

### Changed
- Sample-derived schema verification lets users toggle required-field flags before save and writes those required arrays back into the persisted schema payload.
- `.firebase/` hosting cache output is ignored so local Firebase tooling no longer dirties the UI release branch.

### Fixed
- Inline dropdown menus now close when a peer menu opens or the command palette launches, preventing overlapping popovers and stale focus states.
- The Ask Darpan launcher now dismisses inline menus first and renders above lower floating affordances to avoid layering conflicts.

### Security
- No auth or session model changes ship in this patch; the UI continues to use the stateless auth path introduced in `1.1.0`.


## Data and configuration

- Candidate upgrade records live in `upgrade-data-review.md` and `upgrade-data.xml`.
- No added or modified seed/config records were detected between `v1.1.0` and `main`; `upgrade-data.xml` is intentionally empty for this patch release.
- Operators do not need a release-scoped data load for `1.1.1`; existing `./gradlew loadDarpanSetup` behavior remains unchanged.

## Validation and rollout notes

- Roll out backend and UI together so schema-system capture, sample-derived schema saves, and literal text payload validation stay aligned.
- Backward compatibility note: the patch preserves the `1.1.0` contract surface; the runtime change is limited to more permissive literal text-input validation and UI interaction hardening.
- Compare URLs:
  - Backend: `https://github.com/hotwax/darpan/compare/v1.1.0...v1.1.1`
  - UI: `https://github.com/toaditi/darpan-ui/compare/v1.1.0...v1.1.1`
- Linear release tracking: `DAR-146`

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
