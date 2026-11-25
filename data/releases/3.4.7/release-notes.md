# Darpan 3.4.7

Release date: 2026-11-25

## Scope note

- **A backend and app hotfix on the 3.4 line** (Aditi, 2026-11-25 18:04 IST: "cut a hotfix and deploy backend and UI";
  scope "Ship all"). It carries everything on darpan `main` after `v3.4.6`, darpan-hotwax `main` after `v1.3.2`,
  shopify-darpan `main` after `v0.11.1`, and darpan-ui `main` after `v4.4.8`. Publicly it folds into the 3.4 notes (one
  `updates.mdx` section for the line).
- **Upgrade data**: 1 record (`data/upgrade-data.xml`; loaded on boot as `darpan-seed`): the `client.runfailed` copy
  block of the `DARPAN_ERROR_NOTICE` template (3.4.6 shipped the template). No entity change beyond a field description;
  no migration.
- Facade contract stays at **5**. GraphQL schema unchanged (moqui-gql stays `v1.1.1`).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.6` | `v3.4.7` |
| `drpn-ai/darpan-hotwax` | `v1.3.2` | `v1.3.3` (DAR-BE-368 orderItems) |
| `drpn-ai/shopify-darpan` | `v0.11.1` | `v0.11.2` (DAR-BE-368 withLineItems) |
| `drpn-ai/darpan-ui` | `v4.4.8` | `v4.4.9` (DAR-BE-368 Lines block) |
| `drpn-ai/darpan-docker-config` | `v3.4.6` | `v3.4.7` (DARPAN_REF, DARPAN_HOTWAX_REF, SHOPIFY_DARPAN_REF move) |
| darpan-cli, darpan-agent, moqui-gql, database-darpan, netsuite-darpan, moqui-rules, moqui-jexl | unchanged | unchanged |

## User-visible changes

- **An order finding lists its lines** (DAR-BE-368, darpan-hotwax `v1.3.3`, shopify-darpan `v0.11.2`, app `v4.4.9`).
  Under Order, each line shows its SKU, quantity and each system's word for it ("Shopify Unfulfilled · HotWax
  Completed"); a Shopify line and the HotWax item that keeps its id are one row. A check turns it on per side, as data:
  the Shopify orders source's `withLineItems: true` and the HotWax orders source's `readFields: ["orderItems"]`. Off
  (the default), every read is unchanged.
- **A failed interactive saved run tells its subscribers only the neutral line** (DAR-BE-362). The error goes to
  Darpan's error space as one card naming the saved run ("savedRun:<id>", a manual trigger), never to a notify-me
  subscriber; a run cancelled meanwhile is not told as a failure.
- **The client's audit digest shows the neutral not-run notice** (DAR-BE-363), never the read-failure text with system
  names.
- **`audit.unexplained.email.to` is validated on save** (DAR-BE-366), as `errors.email.to` is.

## Operator-visible changes

- Upgrade data loads on boot (1 record). The image needs darpan-hotwax `v1.3.3` and shopify-darpan `v0.11.2`.
- A check that asks HotWax `orderItems` leaves the shared orders read (as `itemQuantity` does) and reads its own
  window, plus one item call per page. A Shopify read with `withLineItems` selects the lines in the same bulk query and
  reports `withLineItems` and `orphanLines` in its request metadata; `orphanLines` above 0 means lines arrived away from
  their order and were left off.
- A source's stored `parameters.readFields` now ride beside the rules' read fields (before, the rules' list replaced
  them).

## Upgrade data

- Current upgrade file: `data/upgrade-data.xml` (1 record), mirrored in this pack. Loaded on boot
  (`DARPAN_UPGRADE_DATA_TYPES=darpan-seed`); `./gradlew loadDarpanUpgradeData` for a targeted load.
- Previous upgrade data archived: `data/releases/3.4.6/upgrade-data.xml` (equals the `v3.4.6` tag's).

## Verification

- `release-checklist.md` (focused backend tests in an isolated clone, UI specs, type-check, lint, deploy pins).

## Deferred items

- Turning on DAR-BE-368 for RAILS R5 (two source parameter writes on prod) waits for Aditi after the deploy; it is on
  DAR-BE-368's prod-owed line.

## Rollback or fallback notes

- Roll back by redeploying `v3.4.6` (docker-config `v3.4.6`). The one upgrade record is a copy block only `v3.4.7` reads;
  it is harmless under `v3.4.6`. The DAR-BE-368 options are off unless a source asks for them.
