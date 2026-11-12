# Technical Changelog For Darpan 2.1.9

Release date: 2026-10-30

## Versioning decision

- Patch on the 2.1 line, cut from `v2.1.8` with two cherry-picks. Facade contract unchanged (`openapi.json` version
  line only); `component.xml` 2.1.8 -> 2.1.9. No connector or UI change.
- Per the hotfix rule, 2.1.9 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.8..32ef412` | 2, cherry-picked from `main` `15b2e8c`, `dcda241` (plus the 2.1.9 version commit) |
| darpan-docker-config | `v2.1.8..` | 0 (plus the 2.1.9 pin commit) |

## Backend

### Fixed

- DAR-BE-166 (darpan `32ef412`): `execute#RuleSetMatchedPairs` is declared `no-remember-parameters`. Moqui keeps a
  service call's in-parameters on the request's artifact-execution history until the request ends, and the rule stage
  calls it once per 500-pair batch with the parsed pairs: every batch stayed reachable (at 1.9 GB: 1.1 GB of HashMap
  tables, 792k Groovy LazyMaps).
- DAR-BE-166 (darpan `332ab1a`): `ReconciliationServices.executeRuleSetMatchedPairBatches` iterated
  `matchedPairDf.toJSON().toLocalIterator()`, which brings one whole partition to the driver; adaptive execution had
  coalesced all 125,602 matched pairs into one partition. New `boundedPartitions(df, rowsPerPartition)` repartitions
  to `ceil(rows / MATCHED_PAIRS_PER_PARTITION)` (2,000) first; an explicit partition count is not coalesced.

## UI

- No change; darpan-ui `v3.1.3` stays.

## Data and configuration

- Upgrade data: none.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.9`; the other pins unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then Retry run 100919 from "Comparing records".

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.8...v2.1.9
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.8...v2.1.9
