# Technical Changelog For Darpan 2.1.5

Release date: 2026-10-29

## Versioning decision

- Patch on the 2.1 line. Facade contract unchanged (`openapi.json` version line only); `component.xml` 2.1.4 -> 2.1.5,
  shopify-darpan 0.8.3 -> 0.8.4, darpan-hotwax 0.10.3 -> 0.10.4, netsuite-darpan 0.4.2 -> 0.4.3.
- Per the hotfix rule, 2.1.5 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.4..504bbcb` | 16 (plus the 2.1.5 version commit) |
| shopify-darpan | `v0.8.3..3326545` | 1 |
| darpan-hotwax | `v0.10.3..5322d3b` | 7 |
| netsuite-darpan | `v0.4.2..cd3fdb3` | 2 |
| darpan-docker-config | `v2.1.4..` | 0 (plus the 2.1.5 pin commit) |

## Backend

### Changed

- DAR-BE-153 (shopify-darpan `3326545`, darpan `504bbcb`): `ShopifyOrderPaymentSignal.transactionIds` = every SUCCESS
  transaction id on the order, any kind, comma-joined and sorted; `extractShopifyOrders` emits it; connector field row
  `SHOPIFY` `$.records[*].transactionIds` (seq 11). Tests: `transactionIdsAreEverySuccessfulTransactionOfAnyKind`; the
  orders characterization golden gains the field.

### Added

- DAR-BE-157 (darpan `bf94ffb`..`0f94986`, darpan-hotwax `5971acb`..`5322d3b`, netsuite-darpan `d8681ea`, `cd3fdb3`):
  `OMS_PRODUCTS_GQL` endpoint (`extract#HotWaxOmsGqlProducts`, sellable products keyed on NETSUITE_PRODUCT_ID,
  `netsuiteIdState`, `sharedWith`, source filters); connector `windowed` flag (unwindowed endpoints are never
  recent-only in audits); NetSuite `ACTIVE_ITEMS` SuiteQL template incl. non-inventory items; gorjana R22 tenant data.
- DAR-BE-151 (darpan `ccfb599`..`6775e52`, darpan-hotwax `d1ed9bb`): conclusion brain (typed facts, DRL pack, ask
  planner, answer store, brain-first conclude with tree fallback), `RuleSetCompareScope.conclusionBrain`,
  `RuleSetConclusionRule.engine`; HotWax order reads keep raw payment rows when asked and the return its OMS order id.

### Fixed

- DAR-BE-142 (darpan-hotwax `ea15677`): a transient OMS GraphQL failure is retried instead of failing the whole read.

## UI

- No change; darpan-ui `v3.1.2` stays.

## Data and configuration

- Upgrade data: 14 `darpan-seed` records (see `upgrade-data-review.md`); tenant config excluded.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.5`, `SHOPIFY_DARPAN_REF=v0.8.4`,
  `DARPAN_HOTWAX_REF=v0.10.4`, `NETSUITE_DARPAN_REF=v0.4.3`.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then the Steve Madden rule changes after a test run; prod with its next deploy.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.4...v2.1.5
- https://github.com/drpn-ai/shopify-darpan/compare/v0.8.3...v0.8.4
- https://github.com/drpn-ai/darpan-hotwax/compare/v0.10.3...v0.10.4
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.4.2...v0.4.3
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.4...v2.1.5
