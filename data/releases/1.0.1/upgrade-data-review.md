# Upgrade Data Review For Darpan 1.0.1

## Scope

- Backend compare range: `v1.0.0..v1.0.1`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

- No added or modified seed records were detected between the compared refs. The only
  `data/` changes in range are organizational (the `1.0.0` current upgrade file archived
  under `data/releases/1.0.0/` and this pack's files).
- No operator data load is required for this release.

## Recommended operator review

- None required; final operator action for this release: none (documented in
  `release-notes.md` and `release-checklist.md`).
