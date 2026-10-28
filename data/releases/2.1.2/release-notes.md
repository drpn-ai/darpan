# Darpan 2.1.2

Release date: 2026-10-28 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line that pins every component's `main` head again** (Aditi, 2026-10-28: "we need a new hot
  fix... in order for this to reach SM prod"; scope "Tag current main"). api.drpn.ai already runs most of it from
  main builds; sm-darpan runs 2.1.1-era code and gets it from these tags.
- **New check:** a Shopify order's payment transaction ids must each sit on a HotWax payment preference
  (`paymentTransactionIds all in paymentRefNums`), and the rules board offers the list operators that say it.
- **Fixes on the failing paths:** several audits run at once under one admission queue without one tenant holding
  up another's runs; a played run starts in the audit's tenant; the run page opens its new run at once; search and
  run lists read a result's header lines, not the whole document; a replay of another tenant's execution answers
  "not found"; an audit never reads data from before the tenant's go-live; a retried window with a filter sidecar
  replays; the clean-run streak line reads the stored day count.
- **Deferred:** the darpan-admin-ui app is not versioned or tagged; `darpan-cli` is released separately; the
  payment transaction id rule is not yet on any tenant's runs.

## Repo targets

Tagged commits per repo: `release-checklist.md`, Cut.

| Repo | From | To | Content head |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.1` | `v2.1.2` | `f4ba9e1` |
| `drpn-ai/darpan-ui` | `v3.1.1` | `v3.1.2` | `727ef84` |
| `drpn-ai/darpan-hotwax` | `v0.10.1` | `v0.10.2` | `7252d38` |
| `drpn-ai/netsuite-darpan` | `v0.4.1` | `v0.4.2` | `4c92f22` |
| `drpn-ai/shopify-darpan` | `v0.8.1` | `v0.8.2` | `4d31bd2` |
| `drpn-ai/database-darpan` | `v0.2.2` | not re-cut (no commits) | `a53e97b` |
| `drpn-ai/moqui-gql` | `v0.1.1` | not re-cut (no commits) | `a8eddb5` |
| `drpn-ai/darpan-docker-config` | `v2.1.1` | `v2.1.2` (prod pins move) | `2d6530c` |
| `drpn-ai/moqui-framework` | `master` (unpinned) | `master` | — |

## User-visible changes

- **Audit page.** Run health is grouped by category with a one-line hero and its own "not checked" pill; a check
  that did not run this morning offers "Retry from last read"; each finding card names what its ids are (for example
  `NetSuite · RMA`, `HotWax · Order ID`); cause groups start folded and count every finding (no "N more"), and the
  page no longer leaves a blank stretch under the board. The not-found page says one thing.
- **Run page.** After Run, the live view opens at once.
- **Rules board.** Two list operators: `all in` (every comma-separated value on the left is on the right) and its
  mirror `contains all`.
- **Chat.** The clean-run streak line counts days from the first message, with no "at least 20" ceiling.

## Operator-visible changes

- **Run admission.** Every execution (scan, retry pass, play, retry from last read, finding re-check) goes through one
  queue: instance cap `maxConcurrentExecutions` 8 (was 4), per-tenant share `maxConcurrentExecutionsPerTenant` 5,
  tenants take turns, a memory hold at `holdAtHeapPercent` 85, and at most `maxWaitingPerTenant` 50 waiting runs per
  tenant. A played run executes in the audit's tenant, whatever tenant the player has switched to.
- **Retry from last read** for one audit check on one morning: `retry#AuditCheck`.
- **Go-live date.** `TenantSetting.goLiveDate`, set through the admin app's tenant edit: an audit never reads a window
  before it, and a play of days before it is refused.
- **Payment transaction ids.** Shopify orders carry `paymentTransactionIds` (every SUCCESS SALE and CAPTURE, and an
  AUTHORIZATION nothing captured); HotWax orders over GraphQL carry `paymentRefNums` (OPP `manualRefNum`, read from six
  payment slots when a rule asks for it). A run with that rule reads HotWax at about half the page size, so about
  twice the calls.
- **Lighter reads.** The run page asks `get#ActiveRunResult`; run history and search read only the page's rows and
  each result's header lines.
- **Tenant boundary.** A retry of another tenant's execution reads as not found.
- **Admin app** (deployed on its own): a failure that a later run recovered no longer raises a warning.

## Upgrade data

- `./gradlew loadDarpanUpgradeData` on every instance (prod, UAT, sm-darpan): 3 records in `data/upgrade-data.xml`.
  - The `AUTOMATION_STREAKS` migration row (DAR-BE-144).
  - Two connector fields for the rules board: `SHOPIFY` `paymentTransactionIds`, `OMS_GQL` `paymentRefNums`.
- Then `run#PendingMigrations`: it runs `migrate#AutomationStreaks` once, folding every automation's history into its
  stored streak.
- No tenant config in this pack. Gorjana R21 (`GorjanaMonthCloseCatalogData.xml`,
  `GorjanaMonthCloseReturnsChecksData.xml`) and RAILS R7 (`RailsAuditData.xml`) are `darpan-tenant-config`, loaded per
  tenant where wanted.
- New column by add-missing on start: `TenantSetting.goLiveDate`.

## Verification

- Focused tests on the tagged heads (JDK 21): `TenantOwnedEntityFilterContractTests`, `AuditCatalogContractTests`,
  `SourceFilterEntityContractTests`, `ApiContractGeneratorTests`, `FacadeContractSnapshotTests`,
  `RunFlowchartSchemaContractTests`, `AutomationEntityContractTests` (counts in `release-checklist.md`); connector
  components compile (main and test) against darpan `f4ba9e1`.
- Each in-scope change was tested by its own commit (see `technical-changelog.md`). Full suites, a browser pass and
  an upgrade-data load were not run for this cut.

## Deferred items

- darpan-admin-ui versioning and tags.
- `darpan-cli` release.
- The payment transaction id rule on tenant runs (DAR-BE-153), after a per-tenant check of uncaptured authorizations.
- Live checks after deploy: a play while another audit runs, a run page opening its new run, `run#PendingMigrations`
  reporting `AUTOMATION_STREAKS` done.

## Rollback or fallback notes

- Rolling back to 2.1.1 pins: rebuild from docker-config `v2.1.1`, with darpan-ui `v3.1.1`. The three 2.1.2 records
  are inert under 2.1.1 (2.1.1 has no `migrate#AutomationStreaks` service, so leave the migration row disabled or
  delete it before `run#PendingMigrations`; the two connector field rows only label fields). The `goLiveDate` column is
  ignored by 2.1.1.
- A saved run carrying an `all in` / `contains all` rule cannot compile its rules under 2.1.1; remove such rules before
  rolling back.
