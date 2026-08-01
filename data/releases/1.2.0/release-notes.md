# Darpan 1.2.0 Release Notes

Release date: `2026-08-01`

## Scope note

Release `1.2.0` delivers the platform-admin backend, a Google Chat notification model built on
a per-tenant chat-space registry, live run control (watch a run's files as they land, and stop
it mid-flight), connector connection diagnostics, and preferred-timezone support across the app.
It also reworks Shopify exchange reconciliation onto a presence contract: every exchange created
in Shopify must be imported into OMS.

Deferred to a later release:

- Darpan admin app observability (Plan 4) — the admin backend ships here, the observability layer does not.
- Reconciliation run watchdog and alerting (recon observability P3/P5).
- Out-of-process rule-engine sandbox (residual non-FIELD_COMPARISON DoS surface; P2, not a release blocker).
- Per-tenant `read_returns` / `apiVersion` configuration for exchange reconciliation.

## Repo targets

| Repo | Remote | Branch | Version | Tag |
| --- | --- | --- | --- | --- |
| Backend component | `drpn-ai/darpan` | `main` | `1.2.0` (`component.xml`) | `v1.2.0` |
| UI | `drpn-ai/darpan-ui` | `main` | `2.3.0` (`package.json`) | `v2.3.0` |
| HotWax connector | `drpn-ai/darpan-hotwax` | `main` | `0.2.0` (component.xml is intentionally not tag-tracked) | `v0.5.0` |
| Shopify connector | `drpn-ai/shopify-darpan` | `main` | `0.2.1` (component.xml is intentionally not tag-tracked) | `v0.5.0` |

Compare ranges: backend `v1.1.0..HEAD` (53 commits), UI `v2.2.0..HEAD` (34 commits),
`darpan-hotwax` `v0.4.0..HEAD` (8 commits), `shopify-darpan` `v0.4.0..HEAD` (7 commits).

`netsuite-darpan` and `database-darpan` have no commits since their last tags and are not re-cut.

## User-visible changes

**Reconciliation runs**

- Watch a run while it is still going: steps, files, and results appear live instead of only at the end.
- Stop a running reconciliation from the UI; it cancels cooperatively rather than being killed.
- Failed runs surface as "Needs Attention" instead of silently appearing to run forever.
- Runs that failed before producing any file now appear in run history at all.
- Run history no longer lets an older hydrated row displace the newest run.
- Subscribe to a specific run ("notify me") and get a message when it reaches a terminal state.

**Notifications**

- Google Chat destinations are now a per-tenant registry of named chat spaces, replacing the single
  tenant-wide webhook. Automations pick a space; users pick a personal default.
- Notifications now fire on every terminal state, including failures and reaper-killed runs — not
  just successes.

**Connector settings**

- Test a saved Shopify or OMS connection directly from its dashboard. Diagnostics run as staged
  checks that stream in as each one lands, and the result leads with a plain verdict.

**Timezones**

- Set a preferred timezone. The app resolves an effective display timezone (user preference,
  then tenant, then browser) and uses it consistently for timestamps, day windows, and
  reconciliation date ranges.

**Shopify exchange reconciliation**

- Reworked onto a presence contract: every exchange created in Shopify within the return-date
  window must be imported into OMS. Unmatched candidates are point-confirmed and reported as
  `EXCHANGE_MISSING_IN_OMS`.
- Returns that are not yet closed report as in transit rather than missing.
- Amount checking and reverse-direction checks were removed from this check.

**Platform administration**

- New admin service surface for tenant lifecycle, user management, and membership, fenced to
  super admins. This is the backend the separate Darpan admin app requires.

## Operator-visible changes

- **Data load required.** This release adds a new security group, a new run status, and new
  connector registry fields. See "Upgrade data" below. The backend will not serve the admin app,
  and runs cannot be cancelled, until this loads.
- **One-time notification migration.** After deploying and loading upgrade data, run the internal
  service `reconciliation.ReconciliationNotificationServices.migrate#TenantNotificationSettings`
  once. It copies each tenant's existing webhook into a chat space named "Default space" and links
  that tenant's automations to it. It is idempotent — tenants that already have a chat space are
  skipped — and it is internal-only (not remote-callable), so it must be invoked server-side.
  Without it, tenants keep their webhook row but see an empty chat-space registry.
- **API contract is a breaking change.** `x-darpan-contract-version` moves `1 -> 2`.
  `facade.SettingsFacadeServices.get#TenantNotificationSettings` and `save#TenantNotificationSettings`
  are removed, replaced by the `TenantChatSpace` methods. Any integration calling the removed
  methods must migrate. 26 methods were added. Backend and UI pins must move together.
- **MySQL Connector/J is now 9.7.0** (was 8.3.0), which is what supports the MySQL 8.4 server
  upgrade. Do not upgrade the MySQL server ahead of this release.
- **Scheduled automations now resolve a tenant correctly.** Cron-triggered runs previously failed
  at scope resolution, and the failure was silent. Both are fixed; expect previously-dead
  schedules to start producing runs and notifications again.

## Upgrade data

- Current upgrade file: `runtime/component/darpan/data/upgrade-data.xml` (12 records)
- Release-pack mirror: `upgrade-data.xml`
- Candidate review report: `upgrade-data-review.md`
- Previous current upgrade file is archived at `data/releases/1.1.0/upgrade-data.xml`, verified
  byte-identical to the file published at tag `v1.1.0`.
- **Operators must load it:** `./gradlew loadDarpanUpgradeData`

Records grouped by what they enable:

| Records | Enables |
| --- | --- |
| `DARPAN_ADMIN_API` group + member + 2 `ArtifactAuthz` + `ADMIN_PASSWORD` permission | Admin app backend access, super-admin only |
| `TenantChatSpace` / `ReconciliationRunNotifySubscription` group members, `DARPAN_SCOPE_TENANT_CHAT_SPACE` filter, `darpan.notification.defaultChatSpaceId` preference key | Chat-space registry and per-user default |
| `AUT_STAT_CANCELLED` enumeration | Cancelling a run (FK-checked on `ReconciliationRunResult.statusEnumId`) |
| 2 `SourceSystemConnector` rows | Connection-diagnostics probes and exchange-reconciliation lookups |

The file is ordered parent-before-child, not alphabetically: `ArtifactGroupMember` and
`ArtifactAuthz` both carry a foreign key to `ArtifactGroup`, so `DARPAN_ADMIN_API` must be
inserted first. Do not re-sort it.

## Verification

Passed:

- UI `npm run check` (lint + type-check + vitest with coverage): 98 test files, 814 tests, all green.
- Backend `./gradlew :runtime:component:darpan:test` under JDK 21 — see `release-checklist.md`
  for the recorded count.
- API contract compatibility gate `scripts/check_contract_compat.py v1.1.0 -> HEAD`: exit 0,
  breaking changes accepted against the `1 -> 2` contract-version bump.
- `upgrade-data.xml` XML well-formedness, and all 12 records traced back to a generic seed file
  under `data/`.
- Archived `data/releases/1.1.0/upgrade-data.xml` confirmed identical to tag `v1.1.0`.

Not verified in this cut:

- No live/deployed smoke test against UAT or production. Nothing here exercised a real Shopify or
  OMS tenant.
- The one-time `migrate#TenantNotificationSettings` service has unit coverage but was not run
  against a real multi-tenant dataset.
- The exchange-reconciliation presence rework was not validated against live Shopify exchange data.
- MySQL 8.4 server compatibility was verified previously against 8.0 and 8.4 during the
  Connector/J upgrade, not re-verified as part of this cut.

## Deferred items

See "Scope note" above. None of the deferred items block this release.

## Rollback or fallback notes

- The upgrade data is additive; loading it does not remove or rewrite tenant data.
- Rolling the backend back to `v1.1.0` after the notification migration has run leaves the
  migrated `TenantChatSpace` rows in place and unused. The original `TenantNotificationSetting`
  webhook rows are copied, not deleted, so `v1.1.0` notification behavior still works on rollback.
- Because the API contract removed two methods, rolling the backend back without also rolling the
  UI back to `v2.2.0` is supported, but rolling the UI forward to `v2.3.0` against a `v1.1.0`
  backend is not — the chat-space methods will not exist.
- Go/no-go: if `loadDarpanUpgradeData` fails on the `DARPAN_ADMIN_API` FK, the file was re-sorted;
  restore parent-before-child ordering and reload.
