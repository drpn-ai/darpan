# Technical Changelog For Darpan 2.1.6

Release date: 2026-10-30

## Versioning decision

- Patch on the 2.1 line, cut from the 2.1.5 tags with cherry-picks (not from `main`). Facade contract: one optional
  in-parameter (`run#SavedRunDiff.retryFromRunResultId`), backward compatible. `component.xml` 2.1.5 -> 2.1.6,
  shopify-darpan 0.8.4 -> 0.8.5, darpan-ui 3.1.2 -> 3.1.3.
- Per the hotfix rule, 2.1.6 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.5..c476d14` | 1, cherry-picked from `main` `518b7bd` (plus the 2.1.6 version commit) |
| shopify-darpan | `v0.8.4..201a214` | 2, cherry-picked from `main` `3f987bc`, `93d3c8c` (plus the 0.8.5 version commit) |
| darpan-ui | `v3.1.2..40e38c1` | 1, cherry-picked from `main` `210c6f7` (plus the 3.1.3 version commit) |
| darpan-docker-config | `v2.1.5..` | 0 (plus the 2.1.6 pin commit) |

## Backend

### Fixed

- DAR-BE-162 (shopify-darpan `201a214`): `ShopifyBulkOperationClient` downloads the bulk result into an owner-only
  file in the extract's output folder and checks it line by line (`eachJsonlRecord`); no result String, no
  `List<Map>`. `extractShopifyOrders` normalizes, filters 500 at a time through `SourceFilterSupport.applyToRecords`
  (dropped rows spilled through `ExcludedRecordsSidecar.merge`) and writes each kept order through
  `ShopifyExtractSupport.ExtractFileWriter`, which emits `{metadata, records}` byte-identical to the old
  `JsonOutput.toJson`; the raw JSONL is moved into place. `extractShopifyOrderLineUnits` shares the download and the
  writer. Also fixes `ShopifyOrderLineUnitSupportTests.rejectedUnitsAreCollectedForTheConcludePass`.

### Added

- DAR-BE-163 (darpan `c476d14`): `RunRetrySupport.plan` / `replayedSide`, `ExtractReplaySupport.landedSides`;
  `runSavedRunDiff` hands back a landed side from `extractApiSource` and runs over the failed run's window with
  `parentRunResultId` set. Refused unless the run is the active tenant's, FAILED, of the same rule set, read a side,
  and nothing under its rule set (RuleSet, compare scope, sources, key fields, filters) has a `lastUpdatedStamp`
  after its start.
- DAR-BE-156 (shopify-darpan `c8dd40d`): `extract#ShopifyOrders` reads `lookupKeys` by order id in 250-id bulk
  operations. Inert in 2.1.6: darpan 2.1.6 never passes keys.

## UI

- DAR-BE-163 (darpan-ui `40e38c1`): "Retry from <failed step>" on a failed live run (`useRunRetry`); the registered
  run poll moves to `lib/runRegistration` and is shared with Run. Contract copy at darpan 2.1.6.

## Data and configuration

- Upgrade data: none.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.6`, `SHOPIFY_DARPAN_REF=v0.8.5`; the other pins
  unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins backend, Firebase `hosting:sm-darpan` for the UI), then a retry of run 100766.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.5...v2.1.6
- https://github.com/drpn-ai/shopify-darpan/compare/v0.8.4...v0.8.5
- https://github.com/drpn-ai/darpan-ui/compare/v3.1.2...v3.1.3
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.5...v2.1.6
