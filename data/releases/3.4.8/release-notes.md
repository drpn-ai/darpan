# Darpan 3.4.8

Release date: 2026-11-25

## Scope note

- **A backend hotfix on the 3.4 line** (Aditi, 2026-11-25 18:39 IST "work on next tickets", "Finish this thread", then
  "Declare as options"). It makes DAR-BE-368's order lines a run side's extract option, so a tenant turns them on per
  check in the run editor and `automation sync` carries the choice, as HotWax `keepExchangeOrders` works. It carries
  everything on darpan `main` after `v3.4.7` and darpan-hotwax `main` after `v1.3.3`. Publicly it folds into the 3.4 notes.
- **Upgrade data**: 1 record (loaded on boot as `darpan-seed`): the SHOPIFY connector row declaring its option. The OMS_GQL
  row lives in darpan-hotwax's `data/HotWaxConnectorSeedData.xml`, which every boot loads itself (3.4.7's boot: "Loaded 6
  records from ... HotWaxConnectorSeedData.xml"). No entity change, no migration.
- Facade contract stays at **5**. GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.7` | `v3.4.8` |
| `drpn-ai/darpan-hotwax` | `v1.3.3` | `v1.3.4` (withOrderItems) |
| `drpn-ai/darpan-docker-config` | `v3.4.7` | `v3.4.8` (DARPAN_REF, DARPAN_HOTWAX_REF move) |
| shopify-darpan, darpan-ui, darpan-cli, darpan-agent, moqui-gql, database-darpan, netsuite-darpan, moqui-rules, moqui-jexl | unchanged | unchanged (shopify-darpan `v0.11.2`, app `v4.4.10`) |

## User-visible changes

- **Order lines are a run side's option** (DAR-BE-368). The run editor offers "Order lines" on a Shopify orders side and
  "Order items" on a HotWax orders side. Ticked, the side reads each order's lines and an order finding lists them under
  Order. Off (the default, never stored), every read is unchanged.

## Operator-visible changes

- Upgrade data loads on boot (the SHOPIFY connector row; darpan-hotwax's connector seed loads on every boot). The image needs darpan-hotwax `v1.3.4`.
- `extract#HotWaxOmsGqlOrders` takes `withOrderItems` (Boolean, default false); on, it adds `orderItems` to the fields read.
- A side with either option on keys its own dataset options, so it reads live rather than from a kept copy.

## Upgrade data

- Current upgrade file: `data/upgrade-data.xml` (1 record), mirrored in this pack. The OMS_GQL row reaches prod through
  darpan-hotwax's own seed file, loaded on every boot.
- Previous upgrade data archived: `data/releases/3.4.7/upgrade-data.xml` (equals the `v3.4.7` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- Ticking the options on RAILS R5's five runs (RS_100039 to RS_100043) and syncing their automations (100412 to 100416)
  after the deploy: DAR-BE-368 prod-owed.

## Rollback or fallback notes

- Roll back by redeploying `v3.4.7` (docker-config `v3.4.7`). The connector rows' extra option is ignored there; a side
  that stored the option keeps it and a `v3.4.7` run drops it at run time (options the connector does not declare are
  dropped, never a failed run).
