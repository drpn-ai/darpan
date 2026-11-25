# Darpan 3.4.5

Release date: 2026-11-25

## Scope note

- **A backend hotfix on the 3.4 line** (Aditi, 2026-11-25 12:17 IST: "push anything else pending.. then cut a hotfix
  and deploy"). It carries everything on darpan `main` after 3.4.4 and darpan-hotwax `main` after `v1.3.0`. The app
  stays at `v4.4.5`. Publicly it folds into the 3.4 notes (one `updates.mdx` section for the line).
- It comes out of Aditi's audit cause review the same morning ("records should be caught by the simplest statement and
  cause first, never be repeated... similar causes grouped into one"). The tenant config half of that review (Malbon,
  RAILS, Gorjana trees) was loaded before this release; this release carries the code half.
- **Upgrade data**: 2,325 records (`data/upgrade-data.xml`; loaded on boot as `darpan-seed`): the regenerated in-app
  help content (DAR-UI-140/141 mascot seed, loaded whole with its new seed mark) and the HotWax orders connector's new
  "Keep exchange orders" option (darpan-hotwax `v1.3.1`).
- Facade contract stays at **5**: no method added or removed, no field changed. GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.4` | `v3.4.5` |
| `drpn-ai/darpan-hotwax` | `v1.3.0` | `v1.3.1` (DAR-BE-345 option B, DAR-BE-352) |
| `drpn-ai/darpan-docker-config` | `v3.4.4` | `v3.4.5` (DARPAN_REF and DARPAN_HOTWAX_REF move) |
| darpan-ui, darpan-cli, darpan-agent, moqui-gql, database-darpan, shopify-darpan, netsuite-darpan, moqui-rules, moqui-jexl | unchanged | unchanged |

The moqui-framework fork's `master` (the image builds `MOQUI_FRAMEWORK_REF=master`) has no commit after the 3.4.4 build
(`bfad6aa`).

## What changes

- **Each audit statement lists only the causes it can reach** (DAR-BE-353). Statements whose checks share one saved run
  (Malbon R2, R3 and R5 all run on one Shopify-against-HotWax run) each listed that run's whole cause tree, so a cause
  showed under statements that can never conclude it, and a priority set on it there did nothing. A check now reaches
  the causes of the rows it reports (its link's row kinds, mapped to buckets the way the engine does). Known limit: two
  rule checks on one run still share their rule causes; no field yet ties a cause to one rule.
- **A Shopify order still on its way is not reported** (DAR-BE-354). The brain's 24-hour grace ("on its way to
  NetSuite") never applied to Shopify orders because their age was never computed; Dolls Kill R7 opened findings on
  orders 11 minutes old and closed them the next morning (281 of 283). Shopify orders are now aged from `createdAt`.
- **"Every order HotWax approves reaches NetSuite" can check exchange orders** (DAR-BE-345 option B, darpan-hotwax
  `v1.3.1`). A HotWax orders run side can now turn on "Keep exchange orders"; off by default, so every existing run
  reads and keeps exactly what it did. Once Malbon's and RAILS's R11 turn it on, every missing order is reported once,
  by R11, and the item-level statement (R4) hands it over.
- **Malbon R6's older store-return findings are re-checked each morning** (DAR-BE-352, darpan-hotwax `v1.3.1`): the
  HotWax returns read can now read by the return's Shopify order id.
- The browser CORS preflight allows `X-Darpan-Tenant` (DAR-UI-141, backend half; the app does not send it yet).

## After deploy (tenant config, prod-owed)

1. Malbon RS_100562 and RAILS RS_100612, HotWax side: `{"keepExchangeOrders":true}`, then sync each automation.
2. Remove the stopgap exchange-order nodes (Malbon RS_100515 node 75, RAILS RS_100019 node 19) so every missing order
   is R11's.
3. Dolls Kill R7: remove tree node 20 "Created in the last day" (the brain's grace now covers it).
