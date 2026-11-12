# Technical Changelog For Darpan 2.1.8

Release date: 2026-10-30

## Versioning decision

- Patch on the 2.1 line, cut from `v2.1.7` with one cherry-pick. Facade contract unchanged (`openapi.json` version
  line only); `component.xml` 2.1.7 -> 2.1.8. No connector or UI change.
- Per the hotfix rule, 2.1.8 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.7..dc65032` | 1, cherry-picked from `main` `e276f4a` (plus the 2.1.8 version commit) |
| darpan-docker-config | `v2.1.7..` | 0 (plus the 2.1.8 pin commit) |

## Backend

### Fixed

- DAR-BE-166 (darpan `dc65032`): `ReconciliationServices.ingestFile` read a JSON extract with
  `spark.read().option("multiLine", "true")`, which makes each file one row; the matched-pair join then parsed both
  whole documents at once. New `JsonRecordLines` streams the records under the one top-level array the key paths
  explode (Jackson, token by token, numbers from their own text) into `<extract>.records.jsonl` (beside an extract under
  the data manager, else a temp file), and `ingestFile` reads that partitioned with the key paths taken from the record
  root (`recordRootPathInfos`); a saved document schema gives its record schema (`recordSchema`). Keys under no array, a
  nested array or two arrays, and documents with no record, are read whole as before.

## UI

- No change; darpan-ui `v3.1.3` stays.

## Data and configuration

- Upgrade data: none.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.8`; the other pins unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then Retry run 100867 from "Comparing records".

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.7...v2.1.8
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.7...v2.1.8
