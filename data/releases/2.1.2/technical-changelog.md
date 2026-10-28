# Technical Changelog For Darpan 2.1.2

Release date: 2026-10-28

## Versioning decision

- Patch on the 2.1 line (Aditi, 2026-10-28: "we need a new hot fix... in order for this to reach SM prod"; scope
  "Tag current main"). The facade contract is additive only (`retry#AuditCheck` and `get#ActiveRunResult` added, no
  service removed or changed), so `x-darpan-contract-version` stays; `component.xml` 2.1.1 -> 2.1.2. Every component
  with commits since its 2.1.1-set tag gets a patch tag; database-darpan and moqui-gql have none and keep theirs.
- Per the hotfix rule, 2.1.2 is the 2.1 line's one public release: its public notes carry the 2.1 story with these
  changes folded in, and the 2.1.1 pages are superseded.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.1..f4ba9e1` | 14 |
| darpan-ui | `v3.1.1..727ef84` | 7 |
| darpan-hotwax | `v0.10.1..7252d38` | 1 |
| netsuite-darpan | `v0.4.1..4c92f22` | 1 |
| shopify-darpan | `v0.8.1..4d31bd2` | 1 |
| darpan-docker-config | `v2.1.1..2d6530c` | 0 (plus the 2.1.2 pin commit) |

## Backend

### Added

- DAR-BE-153 (darpan `f4ba9e1`, darpan-hotwax `7252d38`, shopify-darpan `4d31bd2`): FIELD_COMPARISON operators
  `all in` / `contains all` in `RuleDiffSupport` and `FieldComparisonRuleLogicGenerator` (two goldens captured from the
  UI generator); Shopify `paymentTransactionIds` (bulk selects `transactions.id`, `transactions.parentTransaction.id`);
  OMS GraphQL `paymentRefNums` from six payment slots with `manualRefNum` when a rule asks, null when the slots come
  back full; `sharedOrdersCover` excludes it.
- DAR-BE-143 / DAR-BE-141 (darpan `07f325a`, `2faea69`): `RunAdmission` for every execution: instance cap 8, tenant
  share 5, turns, heap hold 85, `maxWaitingPerTenant` 50; runs execute as anonymous through `AsyncServiceInfo`, so the
  automation's tenant applies.
- DAR-ADMIN-009 / DAR-UI-086 (darpan `6d99825`): `retry#AuditCheck`; the live board marks a failure a later run
  recovered.
- DAR-BE-145 (darpan `588a75d`): `TenantSetting.goLiveDate`; audit windows start at go-live.
- DAR-BE-146 (darpan `6e29b79`): `get#ActiveRunResult`; `list#GeneratedOutputs` filters by saved run in the query.
- DAR-BE-149 (darpan `0f4af0a`, netsuite-darpan `4c92f22`): `ITEM_AMOUNT` column macro; gorjana R21 as tenant config.
- DAR-CLIENT-005 (darpan `982fe91`): RAILS R7 as tenant config.

### Changed

- DAR-BE-144 (darpan `7873665`): the streak line reads `AutomationStreak`'s day count only; the capped run-count pool,
  `streakLine`, `LOOKBACK_LIMIT` and `RunNotificationStreak` are deleted; `migrate#AutomationStreaks` (registry
  `AUTOMATION_STREAKS`).
- DAR-BE-148 (darpan `5ac05da`): `readOutputDocumentHeader` reads metadata and summary from the first 64 KB for search,
  list rows and the ownership fallback.

### Fixed

- DAR-BE-142 (darpan `75e5633`): a replay carries a source filter's `.excluded.json` sidecar.
- DAR-BE-142 / DAR-ADMIN-008 (darpan `43f6d47`): the audit ledger dedupes examined keys in a Set (was `List.unique()`,
  minutes on 189k keys); the live board reaches an audit through `AuditStatementCheck`.
- DAR-BE-147 (darpan `2c14753`): `ExtractReplaySupport.replayResults` reads by `findOneOwnedByTenant`.

### Security

- DAR-BE-147: another tenant's execution reads as not found on replay.
- DAR-BE-143: one tenant's waiting runs cannot defer another tenant's due runs.

## UI

### Added

- DAR-BE-153 (`b3a09d1`): rules board operators `all in` / `contains all`.
- DAR-UI-086 (`9f9c6be`): "Retry from last read".

### Changed

- DAR-UI-087 (`f404a22`), DAR-UI-088 / 089 / 090 (`7ee7cb4`), DAR-UI-091 (`727ef84`): audit page layout; finding
  cards name their ids; cause groups fold (folded by default) and every finding loads; no blank page under the board.
- DAR-BE-146 (`4da9002`): the run page asks `get#ActiveRunResult`.
- DAR-UI-076 (`fe0a979`): the not-found page says one thing.

### Fixed

- None beyond the above.

### Security

- None.

## Data and configuration

- `data/upgrade-data.xml`: 3 `darpan-seed` records (1 `DarpanMigration`, 2 `SourceSystemConnectorField`). See
  `upgrade-data-review.md`.
- Tenant config not in the pack: gorjana R21 (10 records), RAILS R7 (108 records).
- New column by add-missing on start: `TenantSetting.goLiveDate`.
- `facade-contract.snapshot.txt` regenerated (see `release-checklist.md` for the reviewed diff); `openapi.json`
  version only, 177 methods.

## Validation and rollout notes

- Deploy darpan with darpan-ui (`get#ActiveRunResult`, `retry#AuditCheck`) and with darpan-hotwax, shopify-darpan and
  netsuite-darpan at their 2.1.2-set tags; build from docker-config `v2.1.2`.
- Order: start the image (add-missing), `loadDarpanUpgradeData`, `run#PendingMigrations`.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.1...v2.1.2
- https://github.com/drpn-ai/darpan-ui/compare/v3.1.1...v3.1.2
- https://github.com/drpn-ai/darpan-hotwax/compare/v0.10.1...v0.10.2
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.4.1...v0.4.2
- https://github.com/drpn-ai/shopify-darpan/compare/v0.8.1...v0.8.2
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.1...v2.1.2
