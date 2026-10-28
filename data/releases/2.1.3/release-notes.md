# Darpan 2.1.3

Release date: 2026-10-28 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line so Steve Madden's daily order run can move to the HotWax GraphQL read** (Aditi,
  2026-10-28: "sm is deployed on new tag.. can we update the daily reconciliation run now?", then "Hotfix 2.1.3").
  HotWax upgraded smus-maarg's GraphQL the same day; the run keeps its POS exclusion on the HotWax side, which the
  GraphQL read could not apply until now.
- Cut from `origin/main`, so the two DAR-BE-155 notification commits and the leaner transaction id read (DAR-BE-153,
  `32e1b5c`) pushed after 2.1.2 ride along.
- **Deferred:** darpan-ui is not re-cut (no UI change; v3.1.2 stays); the fulfillment and transaction id rules on
  Steve Madden's run are configuration, added after this deploy.

## Repo targets

| Repo | From | To | Content head |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.2` | `v2.1.3` | `3daa790` |
| `drpn-ai/darpan-hotwax` | `v0.10.2` | `v0.10.3` | `ec09b77` |
| `drpn-ai/darpan-docker-config` | `v2.1.2` | `v2.1.3` (prod pins move) | `267f8ab` |
| `drpn-ai/darpan-ui` | `v3.1.2` | not re-cut (no commits needed) | `fbac6b5` |
| `drpn-ai/shopify-darpan` | `v0.8.2` | not re-cut (no commits) | `e0fc762` |
| `drpn-ai/netsuite-darpan` | `v0.4.2` | not re-cut (no commits) | `f1eb7b1` |
| `drpn-ai/database-darpan` | `v0.2.2` | not re-cut (no commits) | `a53e97b` |
| `drpn-ai/moqui-gql` | `v0.1.1` | not re-cut (no commits) | `a8eddb5` |

## User-visible changes

- **Exclusions.** A HotWax source read over GraphQL offers the **Sales channel** pill, so a run can exclude POS
  orders on the HotWax side.
- **Chat.** A run whose only differences are value mismatches says which field differs ("On isCompleted =
  isFulfilled, HotWax has Y and Shopify has N.") instead of guessing a cause; the Details line names the busiest
  fields. Values are spoken only when both are flags, codes or numbers; anything else reads "the two systems hold
  different values", so no customer data reaches a chat space.

## Operator-visible changes

- **HotWax GraphQL order read.** Selects `salesChannelEnumId` when a filter or rule reads it. Needs the moqui-gql
  schema that carries `Order.salesChannelEnumId` (smus-maarg and gorjana since 2026-10-28); on an older schema a run
  filtering on it fails on its first GraphQL call (unknown field) rather than excluding nothing.
- **Transaction id read.** A rule that reads only `paymentRefNums` asks `paymentPreferences(first: 5) { manualRefNum }`
  instead of six full rows: about 1.08x the pre-2.1.2 HotWax calls, down from about 1.7x.

## Upgrade data

- `./gradlew loadDarpanUpgradeData` on every instance (sm-darpan first; prod and UAT when they take 2.1.3): 1 record in
  `data/upgrade-data.xml`, the `OMS_GQL` `salesChannelEnumId` connector field (the pill). No migration.
- The 2.1.2 load target is archived, byte-identical to `v2.1.2`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.2/upgrade-data.xml`.

## Verification

- Focused tests in the release clone (JDK 21): darpan-hotwax `OmsGql*` 158 tests, 152 pass; the 6 failures are the
  same 6 on the clean `v0.10.2` baseline (upgrade-pack assertions reading an older release's rows, and a 1 ms throttle
  rounding), not this change. `OmsGqlOrderPageSourceTests` 28/28 (the POS exclusion test was red before the fix),
  `OmsGqlConnectorWiringTests` 10/10, `OmsGqlQueriesTests` 18/18. darpan contract and connector tests: counts in
  `release-checklist.md`.
- Not run: full suites (standing instruction: focused tests only), an upgrade-data load on a 2.1.2 database, a live
  GraphQL read on smus-maarg (after deploy, as the first test run of the moved saved run).

## Deferred items

- Steve Madden `RS_PROD_ORDERS`: move HotWax to `OMS_GQL` with the POS exclusion, add `isCompleted = isFulfilled` and
  `paymentRefNums contains all paymentTransactionIds`, test-run, then sync automation 100000.
- prod and UAT deploys of 2.1.3 (nothing in it is required there; the leaner transaction id read benefits prod).

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.2`. The one 2.1.3 record only labels a pill and is inert under
  2.1.2, but a saved run that excludes on `salesChannelEnumId` over GraphQL fails at extract start under 2.1.2: move
  that source back to the REST orders connector before rolling back.
