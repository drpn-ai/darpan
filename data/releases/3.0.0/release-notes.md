# Darpan 3.0.0

Release date: 2026-11-12 (tagged; not deployed)

## Scope note

- **A major release, cut from `main` in every repo.** It carries everything on `main` since the 2.1 line split at
  `v2.1.5`; the fixes cut as 2.1.6-2.1.12 were already carried onto `main` and are not new here.
- **Major because the facade contract breaks (3 -> 4).** The HotWax OMS source config drops `ordersPath` and
  `returnsPageSize`: OMS reads are GraphQL only (DAR-BE-052). Every other contract change is additive (66 new methods).
- **Every component moves with it.** darpan-hotwax, shopify-darpan, netsuite-darpan, database-darpan and moqui-gql are
  re-cut, darpan-agent gets its first tag, and the prod image pins all of them (`darpan-docker-config` `v3.0.0`).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.12` | `v3.0.0` |
| `drpn-ai/darpan-ui` | `v3.1.4` | `v4.0.0` |
| `drpn-ai/darpan-hotwax` | `v0.10.4` | `v1.0.0` |
| `drpn-ai/shopify-darpan` | `v0.8.5` | `v0.9.0` |
| `drpn-ai/netsuite-darpan` | `v0.4.3` | `v0.5.0` |
| `drpn-ai/database-darpan` | `v0.2.2` | `v0.3.0` |
| `drpn-ai/moqui-gql` | `v0.1.1` | `v1.0.0` |
| `drpn-ai/darpan-agent` | none | `v0.1.0` (first tag) |
| `drpn-ai/darpan-docker-config` | `v2.1.12` | `v3.0.0` (every prod pin moves) |

## User-visible changes

### Audits

- **An update after every audit run.** A short chat message and an email with a CSV per rule (DAR-BE-207).
- **Findings are open or closed.** No pending state; a grace period means a finding is not reported yet (DAR-BE-206).
- **An audit owns one schedule.** Its checks never carry their own (DAR-BE-199).
- **A running audit shows what it is reading** and how many records so far, system by system (DAR-BE-173, DAR-UI-096).
- **Verify one finding.** Darpan re-checks that record now and closes the finding or says why not (DAR-BE-123).
- **The finding panel shows the record first**, and a held chain in lifecycle order (DAR-UI-092). An order finding lists
  every line of the order, NetSuite's store-credit line included (DAR-UI-099).
- **Records excused past a stage that then complete** get their own place on the lifecycle board, dimmed and not a
  finding (DAR-BE-238, DAR-UI-107).
- **The morning run reads month-to-date**, so a fixed finding older than the recent read closes (DAR-BE-156).
- **Rule-linked audit checks record their findings** (DAR-BE-208); statement links no longer list cause codes (DAR-BE-196).

### Reconciliation

- **NetSuite <-> HotWax products**: every active product in both, two-way, whole catalogue (DAR-BE-157).
- **NetSuite -> OMS fulfillment sync**: every order NetSuite fulfills is fulfilled in HotWax (DAR-BE-190).
- **Run notifications name each rule** with one mismatch row per rule (DAR-BE-210); a field-mismatch result carries
  both records (DAR-BE-155); the transaction-id rule reads HotWax payment refs on scheduled runs (DAR-BE-239).
- **A compare that hits a per-tenant-unavailable field says so** instead of failing on a raw engine error (DAR-BE-031).
- **The Shopify connection check proves bulk reads work**, and a run whose Shopify token lacks a scope fails at once,
  naming the access it needs, instead of retrying for about 95 minutes (DAR-BE-243; shopify-darpan v0.9.0).

### Agents, CLI and scripts

- **Embedded audit agent** on the audit page, as a tab on the board's deepest column: it reads the open finding, drafts
  changes (schedule, statements, checks, statement wording and on/off) and waits for a person's approval; approve,
  reject and revert are written back into the conversation (DAR-BE-159, DAR-BE-214..230, DAR-BE-242, DAR-UI-104,
  DAR-UI-108). **Off by default**: see Operator-visible changes.
- **Tenant AI agents use Darpan through the `darpan` CLI and MCP**: every read they need, every action their
  permissions allow; the CLI shows an audit as fully as the audit page (DAR-CLI-003, DAR-CLI-004).
- **CLI access per user**: off by default, granted by a Darpan admin, requested from the app (DAR-BE-195, DAR-BE-209).
- **Tenant scripts**: a tenant writes and runs a JEXL script with no code commit, and a saved script can run on a
  schedule and post to a chat space (DAR-BE-164, DAR-BE-167).
- **Data-defined systems**: a REST JSON system can be read from a data definition, checked, saved and retired through
  services (DAR-BE-176, DAR-BE-231, DAR-BE-234, DAR-BE-235).
- **Tenant config versions** track audits, scripts and deployments; config transfer carries scripts and deployments
  (DAR-BE-160, DAR-BE-236, DAR-BE-237).

### App

- Slack is a peer provider when adding a chat space, and the list tells bot delivery from webhook delivery
  (DAR-UI-023, DAR-UI-024).
- Data labels are one to three plain words (DAR-UI-101); systems and endpoints match by what the server sends
  (DAR-UI-106); every button has a mascot answer (DAR-UI-103); a cleanup pass across the app (DAR-UI-102).
- Milestone GIFs for First Light, Full Moon and Twin Moons (DAR-BE-211).

### Security

- Repointing a connector's host no longer keeps its stored secret (DAR-BE-080).
- A saved-run id can no longer collide with another tenant's run history (DAR-BE-081).
- SFTP and database connections verify the server's identity, AWS RDS included (DAR-BE-082).
- A tenant script cannot exhaust server memory with one huge number (DAR-BE-183).
- A `~` path segment no longer slips past the `/rpc/json` and reset-password fences (DAR-BE-189).
- darpan-ui: Vue 3.5.44 clears the high-severity `@vue/server-renderer` advisory GHSA-g2v6-rqmx-r4w6.

## Operator-visible changes

- **Contract 4.** `list#`/`save#HotWaxOmsRestSourceConfig` no longer take or return `ordersPath` and `returnsPageSize`.
  No Darpan client sends them.
- **Before deploying, set `DARPAN_APP_BASE_URL` on prod and confirm SMTP on EmailServer `SYSTEM`.** DAR-BE-207 turns the
  audit run email on for every audit of every tenant; without the base URL its links are relative.
- **The embedded agent stays off** unless `DARPAN_AGENT_ENABLED=true`, `ANTHROPIC_API_KEY` is in the container env, the
  tenant has `agentEnabled=Y`, and the user holds a live AI access grant with a spend limit (DAR-BE-194).
- **Two new scheduled jobs run unpaused**: `scan_TenantScriptDeployments_5m` (due script deployments) and
  `snapshot_PendingTenantConfigs_1m` (a config version per tenant whose audit config changed and went quiet).
- **Four data migrations** (ledgered, idempotent, dry run supported): `OMS_REST_RETIRED` (80, darpan-hotwax),
  `STATEMENT_LINK_EXPECTED` (90), `AUDIT_SCHEDULE_OWNED` (100), `AUDIT_FINDINGS_NO_PENDING` (110). Run
  `run#PendingMigrations` with dry run, read the counts, then run it for real.
- **`OMS_REST_RETIRED` moves every stored run, automation, endpoint grant, schema and mapping on a deleted HotWax REST
  endpoint to its GraphQL endpoint, then deletes the REST connector rows.** Transfer-order and line-unit sources have no
  GraphQL endpoint; the dry run names them, so read it before the real run (expected: none on prod, the 3 Canada runs
  and their automations on SM). Its registry row loads from darpan-hotwax's seed with the type load.
- **A database source that requires TLS now checks the server's identity**, not only encryption (database-darpan
  v0.3.0, DAR-BE-082). AWS RDS verifies with the shipped CA bundle; a server whose certificate cannot be verified stops
  connecting until its CA is stored.
- **Engines**: Spark 4.2.1 (Scala 2.13, ANSI mode) and Drools 10.2.0 (DAR-BE-185, DAR-BE-184). The JVM flags in
  `docker/prod/entrypoint.sh` already carry what they need.
- **Deploy backend and UI together**: darpan-ui 4.0.0 reads the agent status, CLI access and new audit fields.

## Upgrade data

- `data/upgrade-data.xml`: 150 `darpan-seed` records, extracted verbatim from `data/*.xml` (`v2.1.12..HEAD`), parent
  before child. 114 changed in the range; 36 restate the audit vocabulary and the HotWax products endpoint enum so an
  existing database gets them (the load is an upsert). Load with `./gradlew loadDarpanUpgradeData`.
- Holds: 16 tenant-scope entity filters for the new agent, CLI, config and script entities; the run-update email
  template; the three migrations and two jobs above; shared conclusion codes; connector rows and fields (OMS products,
  relabelled fields); the HotWax orders and Shopify remotes relabelled.
- No tenant config: audits, runs and queries move by the admin app's `/transfer`.
- The 2.1.12 load target stays archived, byte-identical to `v2.1.12`'s, at `data/releases/2.1.12/upgrade-data.xml`;
  the 2.1.6-2.1.12 packs are now on `main` too.

## Verification

- Focused checks only (no full suite); counts in `release-checklist.md`.
- Deploy pins: `verify_release_pins.py` exit 0 against the pushed tags.

## Deferred items

- DAR-OPS-015: the OWASP dependency scan (CVSS >= 7) on darpan `main` is unread; today's run died on the NVD API
  (HTTP 522), not on a finding. Triage the list once a run completes.
- Browser pass and paid eval for the agent tab and drafts (DAR-UI-108, DAR-UI-109, DAR-BE-242).
- darpan-ui still declares a dead `ordersPath` on the OMS source config type; nothing reads it.
- Still in progress on `main`, not announced: DAR-BE-052 (REST connector deletion), DAR-BE-160, DAR-BE-167, DAR-BE-174,
  DAR-BE-175, DAR-BE-176, DAR-BE-186, DAR-BE-187, DAR-BE-188, DAR-BE-192, DAR-BE-198, DAR-BE-205, DAR-UI-098.
- New Era: its automation stays paused until its Shopify token and HotWax API access are fixed (DAR-BE-240).

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.12` (pins every component at its 2.1 tag).
- Migrations do not reverse: findings stay open or fixed, and each audit keeps the one schedule `AUDIT_SCHEDULE_OWNED`
  gave it. How 2.1.12 schedules migrated audits is not verified; check before rolling back past a migration run.
- Upgrade-data rows stay; 2.1.12 has none of the new entities and does not read them.
