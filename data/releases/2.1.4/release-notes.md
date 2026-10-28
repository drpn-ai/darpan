# Darpan 2.1.4

Release date: 2026-10-28 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line: a voided card authorization no longer counts as a payment** in the Shopify side of the
  transaction id check (Aditi, 2026-10-28: "Fulfillment only, fix void"). Steve Madden's first run with the check
  flagged 19 cancelled orders, every one an authorization voided on cancel whose HotWax payment names the VOID.
- Only shopify-darpan changes; darpan is re-cut so the release set and the prod pins name one version.
- **Deferred:** re-enabling the transaction id rule on Steve Madden's daily order run after this deploy.

## Repo targets

| Repo | From | To | Content head |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.3` | `v2.1.4` (version only) | `05835c3` |
| `drpn-ai/shopify-darpan` | `v0.8.2` | `v0.8.3` | `1a9d701` |
| `drpn-ai/darpan-docker-config` | `v2.1.3` | `v2.1.4` (prod pins move) | `01b7cb6` |
| `drpn-ai/darpan-hotwax` | `v0.10.3` | not re-cut (no commits) | `ec09b77` |
| `drpn-ai/darpan-ui` | `v3.1.2` | not re-cut (no commits needed) | `fbac6b5` |
| `drpn-ai/netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut (no commits) | |

## User-visible changes

- **Transaction id check.** A cancelled order whose card authorization was voided no longer reads as a missing HotWax
  payment.

## Operator-visible changes

- Shopify `paymentTransactionIds`: an AUTHORIZATION that a successful VOID names as its parent is left out, as one a
  successful CAPTURE names already was. A failed VOID leaves the authorization in. The VOID id itself is not added.

## Upgrade data

- None. `data/upgrade-data.xml` holds no record; `loadDarpanUpgradeData` is a no-op.
- The 2.1.3 load target is archived, byte-identical to `v2.1.3`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.3/upgrade-data.xml`.

## Verification

- shopify-darpan component suite (JDK 21): 247 tests, 246 pass; the new voided-authorization test was red before the
  fix. The one failure, `ShopifyOrderLineUnitSupportTests.rejectedUnitsAreCollectedForTheConcludePass` (a
  NullPointerException), fails identically on the clean `v0.8.2` content.
- darpan `ApiContractGeneratorTests` 7/7 after `generateApiContract` (the `openapi.json` version line only).
- Not run: a live Shopify read with the fix (after deploy, as a Steve Madden test run).

## Deferred items

- Steve Madden `RS_PROD_ORDERS`: re-enable rule 100001 (`paymentRefNums contains all paymentTransactionIds`), test-run,
  sync automation 100000.
- prod: the 8 daily order runs given the transaction id rule on 2026-10-28 take this fix with a 2.1.4 deploy.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.3`; voided authorizations then read as missing HotWax payments
  again. Nothing is stored differently.
