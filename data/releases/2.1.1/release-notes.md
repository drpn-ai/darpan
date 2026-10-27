# Darpan 2.1.1

Release date: 2026-10-27 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line that pins what prod already runs.** Since 2.1.0, prod has been built from every
  component's `main` (docker-config `v0.0.3`), which cannot be rebuilt the same way twice. 2.1.1 tags those heads so
  every instance builds the same code from release tags again.
- **Fixes on the failing paths:** a HotWax orders page that answers 429/502/503/504 is retried instead of failing the
  whole read; a check that failed at compare re-runs from the files it already read instead of reading HotWax again;
  connector runs saved with bare record keys are repaired; ids are minted by the server and another tenant's record
  answers exactly like a missing one; error messages never print an encrypted field's value.
- **Also on `main` and so in this tag:** the audit page shows a play in progress; password reset says what happened;
  completed Shopify returns as a source; streak badges and side quests; tenant admins mint API keys for their own
  tenant; admin config transfer and the live operations board (admin app, deployed separately).
- **Deferred:** the darpan-admin-ui app is not versioned or tagged; `darpan-cli` is released separately.

## Repo targets

Tagged commits per repo: `release-checklist.md`, Cut.

| Repo | From | To | Content head |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.0` | `v2.1.1` | `0174765` |
| `drpn-ai/darpan-ui` | `v3.1.0` | `v3.1.1` | `b870632` |
| `drpn-ai/darpan-hotwax` | `v0.10.0` | `v0.10.1` | `e1e8e68` |
| `drpn-ai/netsuite-darpan` | `v0.4.0` | `v0.4.1` | `ef47428` |
| `drpn-ai/shopify-darpan` | `v0.8.0` | `v0.8.1` | `c922ce0` |
| `drpn-ai/database-darpan` | `v0.2.1` | `v0.2.2` | `8a68d8f` |
| `drpn-ai/moqui-gql` | `v0.1.0` | `v0.1.1` | `cfa8295` |
| `drpn-ai/darpan-docker-config` | `v2.1.0` | `v2.1.1` (prod pins move) | `2960a08` |
| `drpn-ai/moqui-framework` | `master` (unpinned) | `master` `6b616bbb` | — |

## User-visible changes

- **Audit page.** While a play is running, Run health shows it in progress, and the report reloads when the checks
  finish.
- **Forgot password.** "Forgot password?" is a text link under Sign In; the code goes in six boxes; the page says what
  happened (sent to a masked address, or why it could not be sent). Password emails greet people by first name.
- **Home** lists Audits first, after the pins. Page loaders say something lighter than "Loading...".
- **Create forms** no longer ask for an id: the server mints one.
- **Streaks.** The clean-run streak is stored and keeps counting past 20; badges at new milestones, named after the
  sky, with side quests and a space fact a tenant never hears twice.

## Operator-visible changes

- **Re-run a failed window from its extracts.** `retry#AutomationExecutionFromExtracts`: a failed or dead-lettered
  window whose reads all finished runs again over the same window from the files it already read. It refuses, in
  words, when a read did not finish, a file is gone, or more than one window would replay.
- **Repair connector keys.** `repair#ConnectorRecordKeys(savedRunId, recordRoot, dryRun)` folds the record root into
  the bare keys of a run's rootless connector sources (the cause of two checks failing at compare on 2026-10-27).
  `create#RuleSetRun` now folds a sent root into connector keys, as `save#RuleSetRun` already did.
- **HotWax orders reads retry a page** on 429/502/503/504 (backoff 10/30/60 s), the same as the returns read.
- **Shopify returns** declare a `completedReturns` extract option: one row per order, refunded, exchanged or refunded
  on the order.
- **Ids.** New connections, queries and configs get minted ids; a named id only updates a row this tenant owns or was
  granted; another tenant's id reads as missing.
- **API keys** (public GraphQL): tenant admins mint keys, always for the tenant they are signed into. Public API
  paths refuse cookies and accept developers.drpn.ai as an origin.
- **Logs.** Encrypted columns print as `***` in entity and condition strings (framework fork).
- **Admin app** (deployed on its own): tenant config transfer between instances, with credentials sealed to the
  target; a live operations board (running work, run health, system load).

## Upgrade data

- `./gradlew loadDarpanUpgradeData` on every instance (prod, UAT, sm-darpan): 5 records in `data/upgrade-data.xml`.
  - 4 tenant entity filters (streak badges, side quests, streak facts, tenant conclusions). Until they load, those
    entities are read unscoped by tenant on an existing database.
  - The Shopify returns connector's `completedReturns` option.
- No tenant config in this pack. RAILS's audit (`RailsAuditData.xml`, `darpan-tenant-config`) is already on prod
  through the admin transfer; do not load it over it.
- Then `run#PendingMigrations`, as on every deploy.

## Verification

- Focused tests on the tagged heads (JDK 21): `TenantOwnedEntityFilterContractTests` 5/5,
  `AuditCatalogContractTests` 5/5, `SourceFilterEntityContractTests` 4/4, `ApiContractGeneratorTests` 7/7,
  `FacadeContractSnapshotTests` 1/1; connector components compile (main and test) against darpan `0174765`.
- Each in-scope change was tested by its own commit (see `technical-changelog.md`). Full suites, a browser pass and
  an upgrade-data load were not run for this cut.

## Deferred items

- darpan-admin-ui versioning and tags.
- `darpan-cli` release.
- Live checks after deploy: a retry from extracts on a real failed window, a password reset, a HotWax orders read
  that hits a 503.

## Rollback or fallback notes

- Rolling back to 2.1.0 pins: rebuild from docker-config `v2.1.0`, with darpan-ui `v3.1.0`. Three of the four new
  `EntityFilter` rows (`DARPAN_SCOPE_AUTOMATION_STREAK_BADGE`, `DARPAN_SCOPE_TENANT_STREAK_QUEST`,
  `DARPAN_SCOPE_TENANT_STREAK_FACT`) name entities 2.1.0 does not define: delete them before running 2.1.0 on that
  database. `DARPAN_SCOPE_TENANT_CONCLUSION` and the connector option are inert under 2.1.0.
- Rolling back to 2.0.0: as above, plus the 2.1.0 notes (the 12 audit filter rows, and `DARPAN_SCOPE_TENANT_CONCLUSION`,
  whose entity 2.0.0 does not define either).
- Rows created under 2.1.1 carry minted ids, which 2.1.0 reads normally.
