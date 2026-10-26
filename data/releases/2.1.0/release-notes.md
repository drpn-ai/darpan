# Darpan 2.1.0

Release date: 2026-10-26 (prepared; not tagged, not deployed)

## Scope note

- **Audits.** A new run type: an audit checks a tenant's systems against a catalogue of plain-English
  statements every morning, keeps a ledger of findings (found, still open, fixed), explains each one
  with its cause and its fix, and reports it in the app, by digest (chat or email) and over the
  read-only GraphQL API. Gorjana's month-close audit (R1, R8, R12, R19, R20) is the first.
- **Forgot password.** Sign-in offers a reset by email code, and settings keep a recovery email.
- **HotWax reads.** Every HotWax read is GraphQL, shared between checks that read the same days, and
  paced against HotWax's cost limit; a full month audit is about 39 minutes instead of 50, with no
  throttled calls.
- **Deferred:** `moqui-gql` is not yet tagged (the image builds it from `main`); `darpan-cli` is
  released separately; the live and browser checks under Verification.

## Repo targets

Content is on `main` and pushed in every repo. Version metadata is bumped at the cut (see
`release-checklist.md`).

| Repo | From | To | Content head |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.0.0` | `v2.1.0` | `99e2620` |
| `drpn-ai/darpan-ui` | `v3.0.0` | `v3.1.0` | `origin/main` `2520855` (+ `626b8b1` once pushed) |
| `drpn-ai/darpan-hotwax` | `v0.9.0` | `v0.10.0` | `3445bb1` |
| `drpn-ai/netsuite-darpan` | `v0.3.0` | `v0.4.0` | `dfdfca2` |
| `drpn-ai/shopify-darpan` | `v0.7.0` | `v0.8.0` | `e8b462a` |
| `drpn-ai/database-darpan` | `v0.2.1` | not re-cut (no commits; compiles against darpan `99e2620`) | `v0.2.1` |
| `drpn-ai/moqui-gql` | untagged | `v0.1.0` recommended (DAR-BE-101) | `1e0776c` |
| `drpn-ai/darpan-docker-config` | `v2.0.0` | `v2.1.0` (prod pins move) | — |
| `drpn-ai/darpan-docs` | — | release pages | — |

## User-visible changes

- **Audit page.** An audit opens on its findings, grouped by order: each card reads verdict first
  (what is stuck, what to do), names the records a person searches by (order name and status, Shopify
  order, NetSuite numbers with their internal ids, SKU, location, customer, dates, totals, deposits,
  warranty id), and offers Resolve, which re-checks that record now and closes or re-explains it.
  Counts follow the state pill; toolbar search, cause and age filters; JSON exports; full-width.
- **Running an audit from the app.** Play runs every check over chosen days; settings change the
  schedule and statements; an audit and its checks (including SuiteQL queries, run sides and
  conclusions) can be created in the app.
- **Digests.** Daily and weekly audit digests to a chat space or by email.
- **Forgot password and recovery email** on sign-in and in settings.
- **Rules board.** "requires" / "required by" operators for one-way presence rules; "is more than"
  in the conclusions editor; a starts-with source filter.
- **NetSuite settings.** The auth tile opens a details page (endpoints, diagnose, delete).
- **Edit run** offers every endpoint of a side's system family.
- **Smaller:** a run result opened under another tenant goes home; a finished run's page loads from
  storage; error lines read as Darpan's own voice; the red panda is Tara.

## Operator-visible changes

- **Data loads.** `loadDarpanUpgradeData` (98 new seed records, see below), then the tenant
  configuration for gorjana and RAILS on the instance that hosts them, then NetSuite query uploads.
  Order and commands: `release-checklist.md`.
- **Audits run as automations.** The first play of an audit creates its automations (paused);
  schedule them 06:00 in the tenant's time zone and enable them. A scheduled audit reads the days
  since its last complete read and re-checks older open findings by id.
- **Extracts report progress** on every automation run, and Cancel run stops an automation mid-extract.
- **A failed extract fails its run** (retried, then dead-lettered) instead of closing it as no data.
- **Logs.** One INFO line per outbound request to HotWax (GraphQL and REST), NetSuite and Shopify
  (`oms gql call`, `oms rest call`, `netsuite call`, `shopify gql call`): about 10,000 lines per
  full-month audit.
- **HotWax.** All OMS reads are GraphQL; reads of the same days are shared across checks for two
  minutes; the cost governor paces every call to the bucket, so a run no longer draws THROTTLED.
- **Email.** Password reset and audit digests send from `support@drpn.ai` (override with
  `DARPAN_MAIL_FROM`); the SYSTEM EmailServer needs an `smtpHost`.

## Upgrade data

`data/upgrade-data.xml` is the **98-record delta on 2.0.0**, from the seven `darpan-seed` files that
changed: 10 enum types and 28 enums (the audit vocabulary, the OMS order-items GraphQL source), 12
tenant filters (the 11 audit entities and `AutomationStreak`), 4 email templates and the SYSTEM
email server row (password reset, audit digest), the `send_AuditDigests_15m` job, 11 connector rows
(the new `OMS_ORDER_ITEMS_GQL`, NetSuite diagnostics, the OMS family's labels and lookups) and 30
connector fields.
`./gradlew loadDarpanUpgradeData` on an image built from all 2.1.0 tags, on prod, UAT and sm-darpan.
`data/releases/2.0.0/upgrade-data.xml` is byte-identical to the `v2.0.0` file and is untouched.

Not in it, by design: the 12 `darpan-tenant-config` files (gorjana month-close catalogue and checks,
conclusion rules, RAILS audit, tri-system runs) and the `client-config` NetSuite queries. They load
on the instance and tenant they belong to.

## API

`docs/api-contract/openapi.json`: contract version 3, 26 methods added (audits, password reset,
lifecycle plan and conclusions, SuiteQL query options and preview, NetSuite auth delete), none
removed or changed; `check_contract_compat.py v2.0.0..HEAD` passes with all 140 methods preserved.
GraphQL (`/rest/s1/graphql/public`, moqui-gql): read-only `auditReport`, `auditFinding`, `findings`
with search, sort and paging; agent keys with label, expiry, list, disable and rotate.

## Rollback or fallback notes

- Roll back as a set: darpan 2.1.0 with 2.0.0-era connectors fails at extraction (shared reads,
  sidecar spill and lookup contracts changed), and the 2.0.0 connectors cannot run on darpan 2.1.0.
- Keep the app on `v3.0.0` if the backend returns to 2.0.0: `v3.1.0` calls methods 2.0.0 lacks.
- The upgrade data and tenant configuration are additive; rolling back leaves them inert. The 12
  audit `EntityFilter` rows name entities 2.0.0 does not define; delete them before running 2.0.0
  on that database.

## Verification

Focused tests on the `main` heads (contract compatibility, the audit and automation suites, the
OMS GraphQL suite, the NetSuite and Shopify client suites) and a live full-month gorjana audit on a
local stack: all checks ran, 39.3 minutes, no throttled calls. Not run: full suites, a load on a
2.0.0 database, the 2.1.0 app in a browser. Detail: `release-checklist.md`.

## Deferred items

- `moqui-gql` `v0.1.0` tag and its prod pin (DAR-BE-101): until then the prod image builds `main`.
- `darpan-cli` release (DAR-CLI-001, DAR-CLI-002): separate from this cut.
- Live: the gorjana and RAILS audits' first scheduled morning, browser smoke of the audit page,
  digests delivered, a password reset end to end on prod.
