# Darpan 1.5.0 Release Notes

Release date: `2026-08-26`

## Scope note

Release `1.5.0` is the largest cut since the history reset: 191 backend commits and 58 app commits
against `v1.4.0` — 249 in total across five repos. Four themes carry it.

**Sources stop being a fixed list.** Databases become a first-class source alongside files, SFTP and
APIs — MySQL, PostgreSQL and IBM Db2, each a saved connection plus a `SELECT` that may take the run's
window. A run can compare two instances of the same system, so staging against production, or one
database against another, is a normal reconciliation rather than a shape the product refused. The
rules board's field pills moved out of Groovy constants into `SourceSystemConnectorField` rows, so
adding an endpoint is now one Enumeration, one connector row and N field rows — no code. A test
(`EndpointExpandabilityTests`) holds that claim.

**Notifications grow a second product and a voice.** Run-completion alerts reach Slack as well as
Google Chat, connected once per tenant either through a one-click install or by pasting a bot token
where Slack administrators do not permit installing apps, with each chat space picking its channel.
The message itself leads with a verdict instead of a table of counts, names value mismatches on
their own line, and varies its wording with the time of day in the tenant's own timezone.

**Configuration and operations get shared and governed.** A source or auth config can be shared
across tenants behind a two-sided tenant-admin gate (DAR-BE-005), and each config now carries
explicit per-endpoint access rather than a single legacy `canReadOrders` flag. One-time data
migrations move from a runbook into a registry with prerequisites, a ledger written in a forced-new
transaction, a supervisor and a `Migrations` screen — so an environment behind on 1.2.0's or 1.3.0's
one-time services no longer has to chase them individually.

**Scheduled runs start agreeing with interactive ones.** The missing-diff verification pass was
lifted into a seam both run paths share, and scheduled runs now verify by default. Until this
release the scheduled path never verified anything, so the same window could report wildly different
numbers depending on how it was started. **This changes reported difference counts, often by orders
of magnitude, and downward** — see "Difference counts will change" below before you deploy.

Returns reconciliation also stops reporting three classes of difference that cannot exist by
definition — whole-order cancellations, superseded return drafts, and refunds for items that were
never shipped — and re-checks reported-missing records by point lookup before reporting them.

**Deliberately not in this release:** `moqui-gql` (the public GraphQL tier and its per-client API-key
realm) is not tagged, not pinned and not announced. It remains a floating `main` clone in the
production image — see "Known gaps" below. `netsuite-darpan` has no commits in this range and stays
at `v0.2.0`. State-based automation mode (`AUT_WIN_STATE`), deferred in 1.4.0, is still filtered out
of the source dropdown and is still not announced.

## Repo targets

| Repo | Remote | Branch | Version | Tag |
| --- | --- | --- | --- | --- |
| Backend component | `drpn-ai/darpan` | `main` | 1.4.0 → 1.5.0 | `v1.5.0` |
| HotWax connector | `drpn-ai/darpan-hotwax` | `main` | 0.7.0 → 0.8.0 | `v0.8.0` |
| Shopify connector | `drpn-ai/shopify-darpan` | `main` | 0.2.1 → 0.6.0 | `v0.6.0` |
| Database connector | `drpn-ai/database-darpan` | `main` | 0.1.0 → 0.2.0 | `v0.2.0` |
| UI | `drpn-ai/darpan-ui` | `main` | 2.5.0 → 2.6.0 | `v2.6.0` |
| Production image | `drpn-ai/darpan-docker-config` | `main` | — | `v1.5.0` |

- Backend compare range: `v1.4.0..HEAD` (136 commits)
- HotWax connector compare range: `v0.7.0..HEAD` (20 commits)
- Shopify connector compare range: `v0.5.0..HEAD` (28 commits)
- Database connector compare range: `v0.1.0..HEAD` (7 commits)
- UI compare range: `v2.5.0..HEAD` (58 commits)
- `netsuite-darpan` stays at `v0.2.0` — no commits in range.

Note on `shopify-darpan`: its `component.xml` had drifted, still reading `0.2.1` at tags `v0.3.0`,
`v0.4.0` and `v0.5.0`. This release corrects it to `0.6.0` rather than continuing the drift, so the
declared component version and the tag agree again. `netsuite-darpan` carries the same drift
(`0.1.0` at tag `v0.2.0`) and is deliberately left alone, since re-tagging a component with no
changes to publish a metadata-only fix is not worth an operator's attention.

## User-visible changes

**Slack as a notification destination**

- Run-completion alerts post to Slack as well as Google Chat. A workspace is connected once per
  tenant, then each chat space picks the channel it posts to.
- Two connection routes, because Slack administrators do not always permit installing apps: a
  one-click install where the deployment is configured for it, or pasting a bot token.
- Google Chat spaces are unaffected. `chatProviderEnumId` is nullable and a null provider resolves to
  Google Chat, so every space that exists today keeps delivering whether or not the new provider
  catalog is loaded.
- The Google Chat webhook URL is now shown and stored in clear text rather than masked. This is the
  one breaking API change in the release — see "API" below.

**What an alert says**

- Messages lead with a verdict — what the run means — rather than opening with a table of counts.
- Value mismatches get their own line. They were previously invisible in the alert, because the
  headline count excludes them by construction: `differenceCount` is `onlyIn1 + onlyIn2`, so a run
  with mismatches and nothing missing reported all zeros and read as clean.
- Wording varies with the time of day, in the tenant's timezone, and a run of clean results is
  acknowledged as a streak rather than repeated verbatim.
- Deep links in an alert name the tenant, so following one from a shared channel lands in the right
  place.

**Databases as a source**

- MySQL, PostgreSQL and IBM Db2. A saved connection plus a `SELECT`, which may take the run's window
  as parameters, and a connection test from the dashboard.
- An extraction that would exceed its max-rows cap fails with a message naming the limit rather than
  silently returning a truncated result.

**Comparing two instances of the same system**

- A run can put the same system on both sides — staging against production, or one database against
  another. Source options are resolved per endpoint rather than per shared remote id, so the two
  sides stay distinguishable throughout the workflow and on the run result.

**Building a rule set**

- A schema can be inferred from a CSV sample's header row instead of typing fields one at a time.
- CSV sides pick columns from a list rather than typing field paths, with likely primary-key columns
  ranked first.
- The source picker asks for the system, then the endpoint — two steps — and skips the endpoint step
  when only one option exists.
- Blank CSV cells are read as empty values rather than as absent fields. This changes what a
  comparison reports for files containing blank cells; see "Operator-visible changes".

**Automations**

- An automation reports when it has drifted from the saved run it was derived from, and can be
  re-synced from that run — merging the run's authoritative config over the stored sources while
  preserving operator settings such as schedule and notification target.
- An automation cannot be re-pointed at a different run; syncing re-derives from its own.
- Schedules are interpreted in the tenant's timezone rather than UTC.
- Automations can be activated and deactivated from the dashboard.
- A paused automation no longer advertises a next run.

**Cross-tenant configuration sharing**

- A HotWax OMS source config, a Shopify auth config, or a NetSuite auth or Restlet config can be
  shared with another tenant, managed from a "Shared with" panel inside the config edit form.
- The grant is gated on both sides: the acting user must hold `DARPAN_TENANT_ADMIN` in the target
  tenant and in a tenant already using the config.
- An owner cannot delete a config while a grant on it is still active.
- Both tenants must live in the same deployment.

**Per-endpoint access on source configs**

- Each source config carries explicit per-endpoint access rows in place of the single legacy
  `canReadOrders` flag. Available endpoints are read from the connector registry, so an endpoint
  added to the registry later appears without a code change — and is disabled until enabled.

**Signing in**

- An account locked out because its password expired can change its own password inline instead of
  reaching a dead end, with the password rules stated and checked as it is typed.

**Ask Darpan**

- Typing `/` enters command mode — running something rather than navigating to it. The first command
  is `/switch-tenant`. A tenant switch reached by deep link announces itself on the notice pill.

**Scheduled runs verify their differences**

- A scheduled run and an interactive run over the same window now report the same differences.
  Previously only the interactive path re-checked reported-missing records by point lookup; the
  scheduled path never did, so the number an automation put in front of you was not the number the
  same comparison produced by hand.
- A scheduled run that verifies now shows its VERIFY step in the timeline, so a verified run is
  distinguishable from one that never verified.

**Difference counts will change**

- Because scheduled runs now verify, **the difference counts your automations report will drop, and
  the drop can be large** — on gorjana automation 100616 the count went from 532 to 2. Nothing about
  your data changed. The earlier number counted records that a direct lookup shows are present; the
  new number is the one an interactive run of the same rule set would already have given you.
- Tell the people who read these counts before the deploy, not after. A count falling by two orders
  of magnitude overnight reads as a broken reconciliation unless it is expected.
- Verification adds real time to a scheduled run — roughly 46 seconds on the run above.

**Run alerts and the run page**

- Alerts name the **system** a count belongs to rather than the endpoint the extract came through:
  "Missing from HotWax", not "Missing from HotWax Returns (Reconciliation API)". Where two endpoints
  of the same system appear in one run, they keep their endpoint names — collapsing both to one
  system name would leave two different counts wearing one label.
- Sources with no system identity, such as CSV and SFTP uploads, keep the label they were given.
- The run-result page shows the run's time from the stored instant rather than a zone-less string,
  so it no longer disagrees with the runs list for the same run.
- A tenant's timezone now takes precedence over a viewer's personal one, matching what the backend
  already decided: a reconciliation timestamp describes the tenant's business day, not the viewer's.
  Changing a tenant's timezone in-app now takes effect immediately rather than after a full session
  refetch.

**Returns reconciliation**

- A transient gateway failure no longer discards a whole returns extraction. Page requests retry
  with backoff on 429, 502, 503 and 504 — a blip now costs one request instead of re-fetching every
  page that had already succeeded. HTTP 500 is deliberately not retried: it signals a deterministic
  fault, so retrying only triples load before the same failure.
- When a page does fail for good, the message names the failing page index, the attempts spent and
  the server's own detail. The page number is the diagnostic: page 0 means the window is too wide for
  the endpoint, a deep page means offset decay, and those want opposite remedies.
- Three classes of false difference are suppressed: refunds belonging to a whole-order cancellation,
  superseded return drafts, and refunds whose lines were never shipped.
- Records reported as missing are re-checked by point lookup before they reach the result, per
  connector and subject to a per-connector cap.
- OMS returns are looked up by both `externalId` and `shopifyReturnId`; `externalId` alone missed
  every pending return.

**Naming**

- Source chips, run-result files and the System card name the system rather than the extract
  endpoint or the raw enum token, and resolve every system label by description rather than only
  OMS. Stored `OMS` and `SHOPIFY` rows are corrected to "HotWax" and "Shopify" by this release's
  upgrade data — see "Upgrade".

**Timestamps in run output**

- A run's output metadata now records an absolute instant with a UTC offset. It previously wrote a
  zone-less wall clock in whatever zone the JVM happened to run in, which the app re-parsed as the
  browser's zone and then rendered in the tenant's zone — two stacked guesses. A 07:00 UTC run
  stamped on a UTC-4 host surfaced as "Aug 25, 4:30 PM" for an IST viewer on an America/Chicago
  tenant: a day early and 9h30m off. This corrects newly written runs only; runs already stored keep
  the zone-less value they were stamped with.

## Operator-visible changes

- **Slack configuration.** The bot-token route needs no deployment configuration. The one-click
  install additionally needs `darpan.slack.clientId`, `darpan.slack.clientSecret` and a resolvable
  redirect URI.
- **One-time migrations are now a registry.** After loading upgrade data, run
  `admin.MigrationAdminServices.run#PendingMigrations` once, or use the `Migrations` screen. It is
  idempotent, walks six registered migrations in sequence, skips any already applied, honours
  declared prerequisites, and records each attempt in a ledger written in a forced-new transaction so
  a failure survives the rollback. This subsumes 1.2.0's `migrate#TenantNotificationSettings` and
  1.3.0's `migrate#AutomationExcludeFilters`.
- **Blank CSV cells change comparison results.** A blank cell is now an empty value, not an absent
  field. Files with blank cells will compare differently than on 1.4.0. This is the intended
  behaviour — the previous reading made a blank indistinguishable from a missing column — but it is
  a behaviour change on existing data, not an addition.
- **Endpoints are disabled until enabled.** Source configs carry explicit per-endpoint access.
  Endpoints not enabled are disabled, including endpoints added to the registry after a config was
  created. The `ENDPOINT_ACCESS` migration translates the legacy `canReadOrders` flag into explicit
  rows.
- **Withdrawn field pills need an explicit deletion.** Seed loads are create-or-update and never
  delete, so pill rows withdrawn from seed data are removed by the `RETIRED_FIELDS` migration.
- **Scheduled-run verification is ON by default, and it moves your numbers.** No configuration is
  needed to get it. The kill switch is
  `-Ddarpan.reconciliation.automation.verifyMissingDiffs=false` — only an exact `false`
  (case-insensitive) disables it; anything unrecognised or blank leaves verification on, because on
  is the safe state and the pass already fails closed internally (a failed, capped or throwing
  lookup reclassifies nothing). It is a system property rather than tenant configuration on purpose:
  it is a rollout switch that should eventually be deleted, not permanent config surface.
- **Budget the extra time.** Verification took about 46 seconds on the reference run. It does not
  risk the old 60-second transaction ceiling — `execute#Automation` is `transaction="ignore"` and
  `executeAutomation` suspends any caller transaction — but a scheduled window that was already
  tight gets tighter.
- **`returnsPageSize` on the HotWax OMS config** (1–1000) lets you shrink the returns page when the
  OMS gateway struggles at the default. It rides on the existing save and list methods.
- **The production image gains a component.** `docker/prod/Dockerfile` did not clone
  `database-darpan`; only the UAT Dockerfile did. This release adds it, pinned to `v0.2.0`, so the
  two images now carry identical component sets. Without it, prod would seed an enabled `DATABASE`
  connector row naming `extract#DatabaseRecords` — a service the image does not contain — and offer
  a database source that fails at extraction. Expect the production image to grow by the connector
  and its JDBC drivers.
- **Deploy the backend before the app.** App `v2.6.0` calls facade methods that exist only in
  `v1.5.0`.
- **Component pins move together.** `DARPAN_REF=v1.5.0` with `DARPAN_HOTWAX_REF=v0.8.0`,
  `SHOPIFY_DARPAN_REF=v0.6.0` and `DATABASE_DARPAN_REF=v0.2.0` — this release's connector registry
  rows name extract and lookup services that exist only in those component versions.

## Upgrade data

Existing environments load **54 records** via `./gradlew loadDarpanUpgradeData`.

Fifty-one are additive. Three are deliberate restatements of rows that already exist in every
deployed environment — create-or-update by primary key, so they change only the named column:

- `Enumeration OMS` and `Enumeration SHOPIFY` are restated with the readable descriptions "HotWax"
  and "Shopify". Deployed databases hold the shouty `SHOPIFY`, which is what an operator sees
  wherever a system is named. This fixes future runs only: a run's file labels are stamped into its
  output document when it executes, so historical runs keep the name they were stamped with.
- `Enumeration OMS_TRANSFER_ORDERS`, which shipped in 1.4.0, gains `parentEnumId="OMS"` so it groups
  under HotWax in the two-step source picker.

Until the load runs on an upgraded environment: no config can be shared (every grant fails on
`config_tenant_access_ibfk_2`, for all four config types, not only the one attempted); no Slack chat
space can be saved; databases and the three new endpoints do not appear as selectable sources and
their extract services are not dispatchable; the rules board offers no field pills; and the
`Migrations` screen lists nothing.

**Then run `run#PendingMigrations` once.** See "One-time migrations are now a registry" above.

**Prerequisite: 1.4.0's upgrade data must be loaded first.** This file is release-scoped and does not
re-carry 1.4.0's three records.

**Prerequisite for production images built before 2026-08-20.** `docker/prod/entrypoint.sh` pinned
`DARPAN_UPGRADE_DATA_LOCATION` to the single file `component://darpan/data/upgrade-data.xml` and
passed `-PupgradeDataLocation` unconditionally, so such an image could only ever receive whatever sat
in that one file — anything living only in `data/releases/<ver>/` never landed. The fix is in
`darpan-docker-config` `fc2bcd0` and ships in this release's image. Setting the environment variable
to empty does **not** override the old pin (`${VAR:-default}` substitutes on unset *or* empty); on an
un-rebuilt image, run the gradle task directly with no `-PupgradeDataLocation`.

No schema migration is required; new entity groups and fields are created on startup.

## API

The published contract moves from **90 to 110 methods** and the contract version moves from **2 to
3**.

`returnsPageSize` is added to `save#HotWaxOmsRestSourceConfig` and `list#HotWaxOmsRestSourceConfig`
as an additional field on existing methods, so integrations need no change to keep working.

Twenty methods are added: seven administrative migration methods, five Slack methods, three
config-sharing methods, two source-endpoint methods, `infer#JsonSchemaFromCsvText`, `sync#Automation`
and `change#ExpiredPassword`. `get#Automation` gains a `syncStatus` response field.

**One breaking change**, which is why the contract version moves:
`facade.SettingsFacadeServices.list#TenantChatSpaces` no longer returns
`chatSpaces[].googleChatWebhookUrlMasked`. It was removed when chat webhooks moved to clear text;
`chatSpaces[].googleChatWebhookUrl` carries the value, and the provider-agnostic
`chatSpaces[].webhookUrl` and `chatSpaces[].webhookConfigured` are the fields to prefer going
forward. An integration reading the masked field must move to one of those. No method was removed.

## Rollback or fallback notes

Schema and seed additions are additive and the restatements only correct display descriptions and a
picker grouping, so rolling the backend to `v1.4.0` is structurally safe and the loaded records do
not need reverting — `v1.4.0` code ignores what it does not know.

Two caveats. Keep the app on `v2.5.0` if the backend goes back: `v2.6.0` depends on Slack,
config-sharing, source-endpoint and CSV-inference methods that `v1.4.0` does not expose.

And the contract rolls back cleanly only for integrations that moved to `googleChatWebhookUrl`:
`v1.4.0` returns both that field and the masked one, so such an integration keeps working, and
rollback restores the masked spelling for anything still reading it. An integration that moved to the
provider-agnostic `webhookUrl` / `webhookConfigured` will break on rollback — those two fields are
new in `v1.5.0` and do not exist in `v1.4.0`.

Roll all component pins back together.

## Deferred items

Called out rather than left implied:

- **`moqui-gql`** — the public GraphQL tier and its per-client API-key realm are not tagged, not
  pinned and not announced. Decision for this release; see the gap below.
- **State-based automation mode (`AUT_WIN_STATE`)** — deferred in 1.4.0 and still deferred. It ships
  underneath but `list#AutomationSourceOptions` filters it out of the dropdown, because the
  per-source status-list write path it depends on still does not exist. Do not announce it.
- **`netsuite-darpan`** — no commits in this range; stays at `v0.2.0`.
- **Fulfillments reconciliation** and **multi-file-source stitching** remain on the roadmap, unstarted.

### Known gaps

- **`moqui-gql` is pinned to a moving target.** The production Dockerfile clones it at
  `MOQUI_GQL_REF=main`, so two builds of the same release tag can contain different code, and the
  public GraphQL tier and its API-key realm ship without a tag, release note or roadmap entry. Left
  as-is by decision for this release; it should be tagged and pinned before the next one.
- **`netsuite-darpan` component version drift.** Its `component.xml` reads `0.1.0` at tag `v0.2.0`.
  Not corrected, because it has no changes to publish in this range.

## Verification

| Component | Classes | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| `darpan` unitTest | 79 | 896 | 0 | 0 | 0 |
| `darpan` smokeTest | 48 | 377 | 0 | 0 | 0 |
| `darpan-hotwax` | 11 | 169 | 0 | 0 | 0 |
| `shopify-darpan` | 15 | 135 | 0 | 0 | 0 |
| `database-darpan` | 13 | 100 | 0 | 0 | 8 |
| **Backend total** | **166** | **1677** | **0** | **0** | **8** |
| `darpan-ui` (`npm run check`) | 113 files | 1175 | 0 | 0 | 0 |

Run under JDK 21 with `--rerun-tasks`, each component in its own gradle invocation. Counts are read
from the JUnit XML, not from "BUILD SUCCESSFUL"; all tasks reported as executed rather than
`UP-TO-DATE`. The UI check executed all four sub-checks — ESLint, the stylelint design-system gate, a
forced `vue-tsc` type-check, and vitest. `release-checklist.md` records the two count discrepancies
that were chased down rather than accepted.

### Not verified

- **No live reconciliation run** against a real OMS, Shopify or database source on the release
  candidate.
- **The database connector's live paths were skipped, not run.** `database-darpan`'s 8 integration
  tests across Postgres, MySQL and Db2 are gated on a reachable server and were skipped in this run
  (100 tests, 92 executed, 8 skipped). End-to-end extraction, the read-only fence and the
  write-blocking assertions therefore have no evidence from this cut. This matters more than usual
  because the same cut adds the component to the production image for the first time.
- **No browser pass** over the candidate build; UI evidence is `npm run check` only.
- **No deployed-environment smoke.** No image was built or deployed from these tags.
- **Slack delivery was never proven end to end.** The OAuth install path, channel picker and message
  rendering are covered by unit tests and a configured channel picker, not by an observed message
  arriving in a real Slack workspace.
- **The `run#PendingMigrations` supervisor has not been run against a real upgraded database.**
- **The upgrade-data file has not been loaded** into any environment; FK ordering is verified
  structurally (parent-before-child, programmatically) rather than by a successful load.
