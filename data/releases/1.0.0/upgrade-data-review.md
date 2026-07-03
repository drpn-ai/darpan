# Upgrade Data Review For Darpan 1.0.0

## Scope

- Backend compare range: `1d01750..v1.0.0`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

- No added or modified seed records were detected between the compared refs
  (`git diff 1d01750..HEAD -- data/` is empty for generic seed files).
- No operator data load is required for this release. `1.0.0` is the full-baseline
  release after the 2026-07-03 history reset: fresh environments load everything via
  `./gradlew loadDarpanData`; existing environments already carry the baseline seed
  (the loader is type-based `darpan-seed` and idempotent, so re-running
  `./gradlew loadDarpanUpgradeData` is safe but not needed).

## Archive state

- The pre-reset current upgrade file (an unreleased post-v2.1.3 candidate) was archived
  at `data/releases/pre-reset/unreleased-post-2.1.3/upgrade-data.xml`.
- All pre-reset release packs (`1.1.0`–`2.1.2`) were moved under
  `data/releases/pre-reset/` to keep the retired numbering separate from the post-reset
  series (see `data/releases/pre-reset/README.md`).

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- Final operator action for this release: none (documented in `release-notes.md` and `release-checklist.md`).
