# Darpan 2.0.0 Release Notes

Release date: `2026-10-19` (prepared; the tag date is the day it is cut)

## Scope note

`2.0.0` is a major release: 89 backend commits, 108 on the app and 61 across the four connectors,
against `v1.5.1`. The public facade contract is additive (contract version stays `3`), but the
release is breaking at three other seams, and every component in it must move together. It is cut
as a major for those reasons, not for its size.

**A run can be a chart of questions.** A run is no longer only "compare A with B". It is a
flowchart: each question is one reconciliation, the questions under it run on its yes or no, and
each answers only for the records that reached it. A scheduled reconciliation walks the whole chart
per window. Past executions keep their summary and every finding opens its records. Existing
NetSuite checks are imported as runs from Home in one step.

**Lifecycles.** A lifecycle follows NetSuite records from a start record type through their
stages and shows where each one stopped: every record lands in exactly one named place. It is built
from four dropdowns whose every option is read from the account itself. Rules leave records out, stage exceptions say when a stage is not expected (store pickup
needs no fulfillment), and findings name the places worth acting on. A lifecycle reads its window
once, however many stages it has.

**One system, one question.** The engine can run a check against a single system ("which NetSuite
orders were never billed?") and report the rows that answer it, without a second side. Such runs
are set up through the API (`scopeMode=EVALUATE`); the app shows their results like any other run.

**Every finding comes with a conclusion.** A run result names what each finding most likely means
and shows the evidence a rule tested, instead of leaving the raw difference to be worked out. A
finding no rule explains says so.

**More sources.** HotWax orders and returns over GraphQL (the returns endpoint sees pending
returns the REST endpoint does not), NetSuite over SuiteQL with status-aware checks (defined through the
API), order-line unit presence for Shopify and HotWax, and HotWax order items for NetSuite
line-level checks.

**Filters can include as well as exclude**, deleting an automation or a lifecycle archives it
rather than removing it, and names across the rules board are short and plain.

**The app has a new shell.** A red panda in the corner replaces the Ask Darpan pill: it is the
launcher, and it explains whatever you rest on. Create Run asks one question first: Sync or
Lifecycle. Pages open from your last visit and refresh behind it.

**Security.** `/rpc/json` dispatches only the product's own methods, every tenant-owned table is
under the tenant filter, and two cross-tenant write paths are closed.

## Repo targets

| Repo | Tag | Branch | Content commits since previous tag |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.0.0` | `main` | 89, `v1.5.1..b473ecf` |
| `drpn-ai/darpan-ui` | `v3.0.0` | `main` | 108, `v2.6.1..46539b4` |
| `drpn-ai/darpan-hotwax` | `v0.9.0` | `main` | 26, `v0.8.0..a9aac48` |
| `drpn-ai/shopify-darpan` | `v0.7.0` | `main` | 19, `v0.6.1..822b155` |
| `drpn-ai/netsuite-darpan` | `v0.3.0` | `main` | 14, `v0.2.0..586bffe` |
| `drpn-ai/database-darpan` | `v0.2.1` | `main` | 2, `v0.2.0..7908b9e` |
| `drpn-ai/darpan-docker-config` | `v2.0.0` | `main` | 1, `v1.5.1..92c496c`, plus the prod pins |
| `drpn-ai/darpan-docs` | — | `main` | release pages, API changelog, catalog, public contract |

Each repo also carries one release commit (version metadata; in darpan the release pack and the
pack's test fix; in darpan-ui the re-synced contract copy).

**All five backend components and the app ship together.** darpan `2.0.0` seeds connector rows
whose extract services exist only in `darpan-hotwax v0.9.0`, `shopify-darpan v0.7.0` and
`netsuite-darpan v0.3.0`. It also removes the filter call the older hotwax and Shopify builds still
make, and the new connectors call the filter verdict darpan `1.5.1` does not have. No mixed pairing
is valid in either direction. `database-darpan v0.2.1` is a patch that would also run on `1.5.1`,
but ships with the set. The app calls 25 methods that exist only in this backend.

## User-visible changes

- **Create Run asks Sync or Lifecycle first** on Home. The separate flowchart runs list is folded
  into Home.
- **Run flowcharts.** Build a chart of yes/no questions, run it, watch each question finish, open a
  finding's records, and browse past executions with their summary.
- **Import NetSuite Checks** on Home turns existing NetSuite checks into runs, one per population.
  It is safe to repeat, and says why any check was skipped.
- **Lifecycles.** Build one from four dropdowns, watch records on a Where Records Stopped board, and
  tune it with leave-out rules, stage exceptions, written findings and extra record details.
  A lifecycle's page shows how it works; a result shows results alone.
- **Conclusions on run results.** Each finding shows a conclusion tile and expands in place to the
  evidence the deciding rule tested. "Stop flagging this" pre-fills the rules board.
- **Filters: "only these values"** beside "exclude these values". A record with no value for the
  field is left out by an include rule.
- **Archiving automations and lifecycles.** Deleting an automation or a lifecycle archives it: it
  leaves the list and stops running, and its results and history stay. Sync runs are still
  deleted from the Ruleset Manager. Restoring an archived item, and switching a run off and on,
  are available through the API only in this release.
- **Home groups runs by system family**, with a count on each heading and a Lifecycle section.
- **The mascot.** Rest on a label, heading, timestamp, button or icon link and the mascot explains
  it, in words drawn from the docs. It offers one idle hint per stretch, only for controls on the
  page. Warnings, such as an automation drifting from its saved run, raise through the mascot with
  their actions inside.
- **Sign out and theme are slash commands**: `/logout`, `/light`, `/dark`. The user menu and the
  floating Home and Account buttons are gone.
- **Timestamps.** A time no longer ends in a zone code; rest on it and the mascot names the zone and
  its offset. A run's date window is shown in the zone the run was anchored in, with the exact
  instants it covered.
- **Pages open at once** from your last visit, then refresh. Wizards and edit forms always load
  fresh.
- **Names.** Field and source names are short and plain ("Shopify order ID", "Order total",
  "HotWax returns"). The returns key card offers "Shopify return ID" and "Refund or return ID".
- **Run timelines name each verification pass** and a new conclusion step.
- **Smaller fixes.** Create new run always starts at the first card. A page full of failures still
  offers older results. Escape on a rules-board popover closes the popover, not the draft. Rules
  using `<` and `>` save. An automation's saved timezone can be corrected.
- **Shopify return status.** The **Return status** field now carries Shopify's own order return status.
  Excluding `IN_PROGRESS` drops an in-progress order's return and its refund rows. See Upgrade
  data for the one step this needs.

## Operator-visible changes

- **`/rpc/json` answers HTTP 403** for any method outside `facade.*`, `admin.*` and `gql.*`,
  including inside a batch, before dispatch and with or without a session. The framework's
  `/rest/login` is closed. darpan-ui, darpan-admin-ui and darpan-cli use nothing else. After
  deploy, an anonymous call to `org.moqui.impl.UserServices.update#Password` must answer 403.
- **Run step codes changed.** `VERIFY` splits into `VERIFY_MISSING`, `VERIFY_EXCHANGE` and
  `VERIFY_RETURNS`; `CONCLUDE` is new; `WRITE_OUTPUT` and `NOTIFY` move to 9 and 10. Old runs keep
  their codes. Anything that reads `stageCode` or `stageSequence` must expect the new values.
- **Run window boundaries are ISO instants.** `dateRange.start` and `end` in `get#GeneratedOutput`
  and `get#ReconciliationRunStatus` read `2026-09-01T11:30:00Z`, not `2026-09-01 17:00:00.0`, beside
  a nullable `timeZone`.
- **Tenant filter on 13 more tables.** Rows with no tenant stamp disappear from tenant reads. Count
  them before the load (see Upgrade data).
- **Saved source filters on Shopify orders start applying** after the load. Until now they were
  stored and ignored. Check for them first.
- **New endpoints, inert until configured:** HotWax orders and payments (GraphQL), HotWax returns
  (GraphQL), HotWax order items, HotWax and Shopify order lines, NetSuite (SuiteQL). The GraphQL
  returns endpoint needs the OMS GraphQL upgrade and is slower than REST: 258 s against 17 s for a
  6-hour window on one live tenant.
- **Returns verification degrades instead of failing.** A failed lookup chunk leaves only its own
  ids unchecked and names how many. The Shopify returns lookup cap rises to 50,000 ids. The OMS
  returns lookup retries a transient 429/502/503/504 after a minute, up to three times.
- **Instance-wide schedule board** for super-admins: every tenant's scheduled fires on one timeline.
- **Lifecycle snapshot reads** are on by default; switch them off with
  `-Ddarpan.reconciliation.runSnapshot=off`.
- **A weekly automation no longer fires twice** when its window crosses a month boundary, and a
  run-notification streak counts one automation's own history.
- **A chat space's product** (Google Chat or Slack) cannot change after it is created.
- **Tenant configuration is not upgrade data.** Client rule sets and conclusion catalogues ship as
  `darpan-tenant-config` files and load per tenant, catalogue first.

## Upgrade data

`data/upgrade-data.xml` is a **delta on 1.5.1: 80 records**. Load it with
`./gradlew loadDarpanUpgradeData` on an image built from all 2.0.0 tags. prod, UAT and sm-darpan
must have loaded `data/releases/1.5.1/upgrade-data.xml` first.

What the records do, in short (the file's header carries the full account):

- 13 tenant filter rows (security), 10 endpoint enums and 12 connector rows for the new sources,
  42 rules-board fields (new fields, renamed labels and the returns key choices), the
  `AUT_STAT_NOT_RUN` status flowchart runs need, and the conclusion catalogue root.
- `SHOPIFY` orders gains `sourceFilters` and its evidence map; `SHOPIFY_RETURN_REFS` swaps the
  return-status field and raises its lookup cap.

**Before the load, on each environment:**

1. Count rows with no tenant stamp on the 15 tables listed in the header (expect 0). A non-zero
   filter, key-field or scope count: run
   `reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps` and recount.
   Report any other non-zero count before loading.
2. List source-filter rows saved against a Shopify-orders source. They start applying after the
   load.

**After the load, once per environment:**

```
admin.MigrationAdminServices.run#Migration  migrationId=RETIRED_FIELDS  force=true
```

A seed load never deletes, so the superseded Shopify return-status field stays on the board until
this runs. The migration is already marked applied wherever 1.5.0's migrations ran, so it must be
forced. It is idempotent. Any rule saved on the old field matches nothing afterwards: re-add it on
**Return status** with `IN_PROGRESS`.

Then run `run#PendingMigrations` as on every deploy.

Not in this file, by design: the gorjana tenant configuration (`GorjanaConclusionRulesData`,
`ShopifyNsBilledRuleSetData`, `TriSystemOrderRunsRuleSetData`, `OrderLineUnitRuleSetData`). An
upgrade pack loads on every instance; client configuration loads on the instance and tenant it
belongs to.

## API

`docs/api-contract/openapi.json` regenerates to `version: 2.0.0`, contract version `3`. 30 methods
are added (29 public, 1 operator-only) and none is removed or changed; the compatibility gate
passes with all 110 `1.5.1` methods preserved. The breaking change for integrations is the
`/rpc/json` method fence above, not the contract.

## Rollback or fallback notes

- Roll back as a set. darpan `1.5.1` with any `2.0.0` connector does not run, and darpan `2.0.0`
  with any `1.5.1`-era hotwax, Shopify or NetSuite connector fails at extraction.
- Keep the app on `v2.6.1` if the backend returns to `1.5.1`: `v3.0.0` calls methods `1.5.1` lacks.
- The upgrade data is additive. Rolling back leaves the new rows inert, except the tenant filter
  rows, which a `1.5.1` build does not tolerate on a shared configuration. Delete the 13
  `EntityFilter` rows added for DAR-BE-076 (listed last in the file) before running `1.5.1` on that
  database.
- Lifecycle snapshot reads can be switched off without a redeploy:
  `-Ddarpan.reconciliation.runSnapshot=off`.

## Deferred items

- No live run on this cut. Owed: a scheduled flowchart run, a lifecycle on gorjana production
  (place counts and month-window timing), a gorjana returns run on HotWax returns (GraphQL), a
  live order-line unit run, a disabled run refusing on both run paths, and one execution of a
  weekly automation across a month boundary.
- No browser pass on this cut. Owed on the flowchart, lifecycle, conclusions, Home grouping,
  cached page loads, the mascot warning popup, the automation timezone picker, and Run Details
  back to Run Editor.
- Prod queries owed for the security fixes: automations pointing at another tenant's chat space,
  and SFTP servers re-owned across tenants.
- A NetSuite SuiteQL request still times out at 120 s, which blocks month-long NetSuite windows
  (DAR-BE-056).
- `delete#Automation` still answers over RPC, though the app now archives instead; Sync runs are
  still deleted through `delete#SavedRun` (DAR-BE-059).
- No app screen yet for single-system runs, NetSuite check definitions, restoring archived items,
  or switching a run off and on; each exists in the API.
- CI tests darpan against connector `main`, not the release tags.
- GitHub release pages for the 1.4 and 1.5 lines are outstanding.

## Verification

Recorded in `release-checklist.md`.
