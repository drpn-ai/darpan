# Darpan 2.1.11

Release date: 2026-10-31 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line: DAR-BE-172 and DAR-BE-174**, both for the Steve Madden order sync audit
  (DAR-CLIENT-006) on sm-darpan.
  - DAR-BE-172: an audit's first play answered "submitted" and nothing ran. The play made the automation inside its
    own request, and the run thread started before that request committed, so it could not see it.
  - DAR-BE-174: a reason can read every item of an order ("the items are complete but the order is not"; an Amazon
    order at facility 990). A conclusion lookup can now attach every record under a key as a list, and a run's lookups
    are set from the app with its reasons.
- Cut from `v2.1.10` with three cherry-picks. Nothing else from `main` ships.

## Repo targets

| Repo | From | To | Content |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.10` | `v2.1.11` | `923f6f8` (DAR-BE-172), `2bd2c18` + `3838cec` (DAR-BE-174) |
| `drpn-ai/darpan-docker-config` | `v2.1.10` | `v2.1.11` (prod pin moves) | |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **An audit's first play runs.** Playing a new audit starts its checks the first time.
- **Reasons can read an order's items.** A reason can ask whether all or any of an order's items are in a status or at
  a facility.

## Operator-visible changes

- `save#RunConclusions` takes `lookups` (replaced when given, an empty list clears them, absent keeps them). A lookup's
  `parameters` carry only `keepRecordFields`; anything else is refused when saved and never reaches the extract.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; `loadDarpanUpgradeData` is a no-op for this release.
- The 2.1.10 load target stays archived, byte-identical to `v2.1.10`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.10/upgrade-data.xml`.

## Verification

- Focused suites on the release line; counts in `release-checklist.md`.
- DAR-BE-172's new test failed first on the old code with "Automation 100000 not found", then passed with the fix.

## Deferred items

- On sm-darpan after deploy, on `RS_PROD_ORDERS`: the `items` lookup (OMS_ORDER_ITEMS_GQL by orderId, many), "Items
  completed, order not" under "Fulfilled in Shopify, open in HotWax", the Amazon 990 reason on `items.facilityId`; then
  replay October and compare counts.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.10`. A first play then runs nothing; "Run now" on the audit's
  automation works around it. Saved `many` lookups are then read as single-record lookups.
