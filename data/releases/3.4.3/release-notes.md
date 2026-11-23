# Darpan 3.4.3

Release date: 2026-11-23

## Scope note

- **A hotfix on the 3.4 line** (Aditi, 2026-11-23 19:34-19:41 IST: "when you are done, deploy a hot fix", "include all
  changes from today in the hotfix", "include the brain edits"). It carries everything on `main` after 3.4.2: Queries
  get their own page (DAR-UI-138, app `v4.4.3`), NetSuite sales-order lines carry their order's backorder
  (DAR-BE-344, `netsuite-darpan` `v0.7.1`), and three test-only reset hooks go (DAR-OPS-026). Publicly it folds into
  the 3.4 notes (one `updates.mdx` section for the line).
- Facade contract stays at **5**: one method added (`facade.TenantScriptFacadeServices.save#TenantQueryPins`) and one
  output field (`pinnedQueryIds` on `list#TenantQueries`); nothing removed or changed, so not a breaking change.
  Public `facade.*` methods: 225 -> 226. GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.2` | `v3.4.3` |
| `drpn-ai/netsuite-darpan` | `v0.7.0` | `v0.7.1` |
| `drpn-ai/darpan-ui` | `v4.4.2` | `v4.4.3` |
| `drpn-ai/darpan-docker-config` | `v3.4.2` | `v3.4.3` (the darpan and netsuite-darpan pins move) |
| darpan-hotwax (`201ba34` is test + doc only, no tag), darpan-cli, shopify-darpan, database-darpan, darpan-agent, moqui-rules, moqui-jexl, moqui-gql | unchanged | 3.4.2 pins |

## User-visible changes

- **Queries have their own page.** Queries move out of Tenant Settings to **Queries** (`/queries`), opened from the
  Dashboard's **Queries** action beside Create Run or from Cmd+K. Old `/settings/tenant/scripts` links land on the same
  page or query. The page is laid out like the Dashboard: **Pinned Queries** at the top (drag a query there to pin it;
  pins are your own and kept per tenant), then the other queries as tiles grouped by what they read (**Audit findings**,
  **Run results**, **Kept records**, **Script only**), each heading with its count. Tenant Settings no longer has a
  Queries row. (DAR-UI-138)
- **A query reads top to bottom: 1 Data, 2 Script, 3 Result.** **Data** says what the query reads as a sentence
  ("Open findings of ...", "The latest result of ...", "Records of ..."). Selecting it, or the plus icon, opens the
  **Data** popup: pick audit findings, a run's result or kept records, then the audit, saved run or dataset by name, a
  window of the last N days, conditions under **Only**, the fields to **Keep**, and the name the script calls it. The
  read's JSON is one icon away (the code icon) for anything the popup does not open (or-conditions, groups, measures,
  an order). **Result** holds the run button, **Preview send** and every run. (DAR-UI-138)
- **NetSuite sales-order lines can carry their order's backorder.** A SuiteQL sales-order lines query
  (`SALES_LINES`) may now ask, on every line, for a sum or the item list over all of the order's lines
  (`LINE_SUM` / `LINE_ITEMS`), for example the order's total backordered quantity and the SKUs on backorder. A
  conclusion can then say "another line of this order is backordered", so one store backorder no longer shows as three
  causes. It takes effect for a tenant only when its query and conclusions use the new columns (Malbon and Gorjana
  packs, loaded after the deploy). (DAR-BE-344)

## Operator-visible changes

- None to configuration, jobs, migrations or settings. Pins are a user preference
  (`darpan.queries.pinnedIds.<tenant>`), created on first pin; nothing to load.
- `save#TenantQueryPins` is not on the CLI allowlist (a browser preference, like the dashboard pins).

## Upgrade data

- None. `data/upgrade-data.xml` carries no records for 3.4.3 (no seed file changed in `v3.4.2..v3.4.3`); 3.4.2's file
  is archived under `data/releases/3.4.2/`, byte-identical. No data-load action.

## Verification

- `ApiContractGeneratorTests` 8/8 and `FacadeContractSnapshotTests` 1/1 at 3.4.3 (JDK 21, `--rerun`, JUnit XML read):
  the committed contract and snapshot already carry `save#TenantQueryPins` and `pinnedQueryIds` (`cfe684cc`); only the
  version line moved here. DAR-UI-138's own backend tests (`TenantQueryFacadeSmokeTests`: pins kept in order per
  tenant, a deleted query drops out) and netsuite-darpan's (`NsSuiteQlQueryTemplateTest` 112/112,
  `NsSuiteQlOrderSupportTest` 36/36) were run by their implementing sessions. See `release-checklist.md`.
- After deploy: app.drpn.ai Dashboard -> Queries -> pin a query -> open it -> change its data in the Data popup -> run
  -> save (DAR-UI-138 prod-owed).

## Deferred items

- DAR-BE-344's tenant config: the Malbon pack (`darpan-config-malbon` 669bafe, query NETSUITE_SUITEQL 100012 +
  RS_100515 node 30) and the Gorjana pack (`darpan-config-gorjana` 2695215, the SALES_LINES query + RS_100010 node 145;
  needs a GORJANA export and a new gorjana code) are prod tenant config writes for Aditi after the deploy, then the next
  R4 / R1 run read.
- DAR-BE-340 (a MySQL deadlock leaves a pooled connection in ROLLBACK ONLY) stays out: its fix shape is Aditi's call.
- `run#AutomationNow` answering 500 past its 60-second request transaction: noted, not filed.

## Rollback or fallback notes

- No data or schema change. Rolling back to 3.4.2 (image built from docker-config `v3.4.2`) needs nothing else: a pin
  preference left behind is ignored by 3.4.2, and app 4.4.2 keeps Queries under Tenant Settings. Roll the app back with
  the backend (4.4.3 calls `save#TenantQueryPins`, which 3.4.2 lacks; pinning would fail, everything else works).
- A Malbon or Gorjana pack that reads `LINE_SUM` / `LINE_ITEMS` on `SALES_LINES` needs netsuite-darpan `v0.7.1`: on a
  rollback, reload the packs' previous trees first.
