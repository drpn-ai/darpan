# Darpan 2.1.5

Release date: 2026-10-29 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line: the transaction id check now runs from HotWax to Shopify.** Every payment reference
  HotWax holds for an order must be one of that order's successful Shopify transactions; Shopify may hold more
  (Aditi, 2026-10-29: "all transaction ids in HW for that order should be present in Shopify but not the other way
  around"). Shopify orders carry a new field, `transactionIds`, for it. Steve Madden captures at ship and its HotWax
  payment keeps the authorization id, so the old direction flagged every shipped card order.
- Riding along, already on `main` and pushed: DAR-BE-157 (HotWax products endpoint, gorjana R22 "every active
  product is in both NetSuite and HotWax"), DAR-BE-151 (conclusion-brain pilot, opt-in per compare scope; only
  gorjana R1/R2 opt in, through tenant config not loaded by this release) and DAR-BE-142 (HotWax GraphQL retries a
  transient failure).
- **Deferred:** the rule changes on Steve Madden's daily order run (after the deploy), the prod daily runs' rule
  direction (after prod takes this release), gorjana R22 and brain-pilot tenant data loads.

## Repo targets

| Repo | From | To | Content head |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.4` | `v2.1.5` | `504bbcb` |
| `drpn-ai/shopify-darpan` | `v0.8.3` | `v0.8.4` | `3326545` |
| `drpn-ai/darpan-hotwax` | `v0.10.3` | `v0.10.4` | `5322d3b` |
| `drpn-ai/netsuite-darpan` | `v0.4.2` | `v0.4.3` | `cd3fdb3` |
| `drpn-ai/darpan-docker-config` | `v2.1.4` | `v2.1.5` (prod pins move) | |
| `drpn-ai/darpan-ui`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **Transaction id check.** The rules board offers Shopify "Transaction ids": every successful transaction on the
  order (authorization, capture, sale, void, refund). Compare it as HotWax "paymentRefNums all in transactionIds".
- **HotWax products.** A new HotWax endpoint, "HotWax products", for NetSuite-to-HotWax product checks.

## Operator-visible changes

- Shopify order records gain `transactionIds`. `paymentTransactionIds` is unchanged, so rules on it read as before.
- HotWax GraphQL reads retry a transient failure instead of failing the run.
- A compare scope may name a conclusion brain; none does until its tenant data is loaded.

## Upgrade data

- Load `data/upgrade-data.xml`: 14 `darpan-seed` records (the `OMS_PRODUCTS_GQL` system source and connector, its 11
  connector fields, and the `SHOPIFY` `transactionIds` field), ordered parent before child.
- The 2.1.4 load target is archived, byte-identical to `v2.1.4`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.4/upgrade-data.xml`.
- Tenant config (gorjana R22, brain pilot) is not in this file and loads per tenant.

## Verification

- Focused suites; counts in `release-checklist.md`. The new `transactionIds` test was red before the change.
- Replay of the new rule on Steve Madden's 2026-10-28 run: 77/77 orders that carry both records pass, including the
  17 capture-at-ship orders the old direction flagged.
- Not run: a live Shopify read carrying `transactionIds` (after deploy, as a Steve Madden test run).

## Deferred items

- Steve Madden `RS_PROD_ORDERS`: replace rule 100001 with `paymentRefNums all in transactionIds`, add
  `hasPaymentPreference required by hasPaymentTransaction`, test-run, sync automation 100000.
- prod: the 8 daily order runs move to the same direction once prod takes 2.1.5.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.4`. Rules naming `transactionIds` then see no value and claim
  nothing; the loaded seed rows are inert.
