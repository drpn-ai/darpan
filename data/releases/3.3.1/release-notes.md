# Darpan 3.3.1

Release date: 2026-11-19

## Scope note

- **A hotfix on the 3.3 line** (Aditi, 2026-11-19: "push and cut a hotfix", then "1" = the current line): DAR-CLIENT-006
  only, `v3.3.0` plus one commit. The four commits on `main` since `v3.3.0` (DAR-BE-319, DAR-UI-132 x2, DAR-BE-320) are
  NOT in it: they carry an entity change, a contract change and seed changes, which are not hotfix-shaped. Publicly it
  folds into the 3.3 notes (one `updates.mdx` section and one GitHub release page for the line).
- Facade contract stays at **4**: no method added or removed, no service changed.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.3.0` | `v3.3.1` (tag off `main`: `v3.3.0` + the fix; the fix is also on `main` as b6b7fdaf) |
| `drpn-ai/darpan-docker-config` | `v3.3.0` | `v3.3.1` (only the darpan pin moves) |
| darpan-ui, darpan-hotwax, darpan-agent, shopify-darpan, netsuite-darpan, database-darpan, moqui-gql | unchanged | 3.3.0 pins |

## User-visible changes

- A conclusion that reads a field no compare rule reads now sees it on the scheduled run too. Before, a run whose read
  is narrowed (store or channel exclusions) never asked the source for that field, so the condition saw a blank and
  never matched. First use: Steve Madden's 1.5-hour sync grace, "HotWax completed under 1.5 h ago; Shopify not synced
  yet" (HotWax `lastUpdatedTxStamp`).

## Operator-visible changes

- The HotWax order read asks for the fields a run's conclusion tree reads, as it already did for its rules'. A tree
  that reads no new field reads exactly what it read before.

## Upgrade data

- None: no seed changed since `v3.3.0`. `data/upgrade-data.xml` holds no records for 3.3.1.

## Operator actions after deploy

1. Smoke: the backend healthy; boot data loaded without skipped files.
2. **sm-darpan (on darpan 2.1.12):** this is a jump across 3.0.0-3.3.1. Load every archived upgrade pack in order
   (`data/releases/<v>/upgrade-data.xml` for each release after 2.1.12), then the migrations each of those releases
   names (3.3.0: RETIRED_JOB_PARAMETERS, dry run first), as their own checklists say. Then save the HotWax-side grace
   node on RS_PROD_ORDERS and read back the first run.
3. Prod (on 3.3.0): optional. On a HotWax order read narrowed by filters, a tree condition on a field the run did not
   read has seen a blank until now; after this release it sees the value, so such a node can start to match.

## Verification

- Commands and results: `release-checklist.md`.

## Read cost

- Steve Madden's tree reads `lastUpdatedTxStamp` (one scalar) and, in its "Amazon order at facility 990" node,
  `shipGroups.facilityId` (the ship groups, 12.5 per order). SM's HotWax order read goes from about 44 to 25 orders a
  page, so about 75% more calls (a day of ~4,000 orders: ~91 -> ~160 calls). The 990 node matches for the first time:
  expect the Amazon orders to close as expected on SM.

## Deferred items

- None.

## Rollback or fallback notes

- Roll back with the 3.3.0 image: tree-only fields stop being asked for; nothing else changes.
