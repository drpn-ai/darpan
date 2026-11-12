# Technical Changelog For Darpan 2.1.7

Release date: 2026-10-30

## Versioning decision

- Patch on the 2.1 line, cut from `v2.1.6` with one cherry-pick. Facade contract unchanged (`openapi.json` version
  line only); `component.xml` 2.1.6 -> 2.1.7. No connector or UI change.
- Per the hotfix rule, 2.1.7 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.6..19ac854` | 1, cherry-picked from `main` `52b7610` (plus the 2.1.7 version commit) |
| darpan-docker-config | `v2.1.6..` | 0 (plus the 2.1.7 pin commit) |

## Backend

### Fixed

- DAR-BE-165 (darpan `19ac854`): `RuleSetCompareScopeAdapter.splitDuplicateKeys` runs on every side of a two-sided
  compare; every record under a repeated key becomes a `DUPLICATE_IN_FILE_n` row (DAR-BE-063's FINDING) and the key
  leaves both sides. Deleted: the `validateUniqueCompareIds` call (ruled runs threw), the base-diff collapse branch
  and `collapseDuplicateCompareIdsForBaseDiffOnly`, the `allowDuplicateCompareIds` read and the per-side handling
  value. `RuleSetCompareSource.duplicateKeyHandling` is described as retired. 54 lines added, 148 removed.

## UI

- No change; darpan-ui `v3.1.3` stays.

## Data and configuration

- Upgrade data: none.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.7`; the other pins unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then Retry run 100816 from "Comparing records".

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.6...v2.1.7
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.6...v2.1.7
