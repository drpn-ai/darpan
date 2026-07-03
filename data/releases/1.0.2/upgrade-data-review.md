# Upgrade Data Review For Darpan 1.0.2

## Scope

- Backend compare range: `v1.0.1..v1.0.2`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

- No added or modified seed records were detected between the compared refs. The only
  `data/` changes in range are organizational (`1.0.1` current file archived, this pack).
- No operator data load is required for this release.

## Recommended operator review

- None required; final operator action: none (documented in `release-notes.md` and
  `release-checklist.md`).
