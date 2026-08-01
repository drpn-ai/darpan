# Technical Changelog For Darpan 1.2.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

`1.2.0` is cut as a **minor** release, deliberately, despite containing a breaking API change.

The facade contract removes two methods (`facade.SettingsFacadeServices.get#TenantNotificationSettings`
and `save#TenantNotificationSettings`), which a strict SemVer reading would make a major bump. It is
released as minor because:

- The backend component version and the API contract version are separate identifiers. The breakage
  is carried by `x-darpan-contract-version`, which bumps `1 -> 2` and is the value the CI
  compatibility gate (`scripts/check_contract_compat.py`) actually enforces.
- `drpn-ai/darpan` and `drpn-ai/darpan-ui` production pins move together, and the only consumer of
  the removed methods was `darpan-ui`, migrated in this same release (`a361fae`).

Any external integration calling the removed methods must migrate to the `TenantChatSpace` methods.

## Source ranges

- Backend: `v1.1.0..HEAD` (53 commits)
- UI: `v2.2.0..HEAD` (34 commits)
- `darpan-hotwax`: `v0.4.0..HEAD` (8 commits)
- `shopify-darpan`: `v0.4.0..HEAD` (7 commits)

## Backend

### Platform admin (new surface)

- `ad0ca4b` `DARPAN_ADMIN_API` security fence for `admin.*` services; never name an admin service
  `facade.*` (auto-grant trap).
- `59523c2` `AdminAccessSupport` guard + `get#AdminSessionInfo` fence probe.
- `784362b` `AdminAuditLog` entity + `AdminAuditSupport` writer.
- `436a41d` tenant lifecycle services; `f06fb21` `TenantSetting.disabled` hides deactivated tenants.
- `a89b3a7` user management services; `083aad0` membership services with atomic dual-entity writes.
- `95c3175` `admin.*` services exposed in the API contract via per-dir prefixes.
- Fixes: `6f13dc3` / `5cd9c54` `disableAuthz` on `listDisabledTenantIds` (this had broken **every**
  non-ADMIN session); `42a1b88` tenant-type guard on `remove#TenantMember`, no-null-overwrite on
  `updateUser`; `eb1c43e` gate follow-on writes on framework service success.

### Notifications — chat-space registry

- `57f8b5f` `TenantChatSpace` + `RunNotifySubscription` entities, `ReconciliationAutomation.chatSpaceId`.
- `0060491` registry facade (list/save/delete); `f5eb798` `save#TenantChatSpace` returns computed `inUse`.
- `b5544c4` per-user default chat space preference + facade.
- `b091a49` notify-me run subscriptions + run-status flag.
- `a4ed765` registry + subscription fan-out with `notifiedDate` dedupe; `6581788` atomic
  claim-then-deliver dedupe.
- `a2f88f2` notify on all terminal states including failures and reaper kills; `7e6fd5b` best-effort
  wrap for SFTP notify.
- `c0929f3` link automations to a tenant chat space; `fc86c84` route chat-space reads through
  `TenantScopedFinder`.
- `b59e83b` purge run subscriptions on terminal + tenant-pin space-name read.
- **`276bc82` retires the tenant-wide webhook facade and adds the one-time v1.2.0 migration service**
  `reconciliation.ReconciliationNotificationServices.migrate#TenantNotificationSettings`
  (`authenticate="false"`, internal-only, idempotent, skips tenants that already have a space).

### Run control and observability

- `4a3f3c6` cancel a running reconciliation + live file/step visibility.
- `edd0e55` clear stale run `progressPercent` when a new stage begins.
- `b2baa70` list file-less FAILED runs so failures are visible in run history.
- `0dd604b` never record `SUCCEEDED` without compare output on the API path.
- `6422e20` scheduled runs resolve a tenant (cron automations were failing at
  `prepare#RuleSetCompareScope` because the anonymous `_NA_` user has no tenant) and failures stop
  being silent.

### Connection diagnostics

- `ee6cad0` test a saved connector connection from its dashboard, dispatched through the connector
  registry (`healthCheckServiceName`).
- `515d121` walk diagnostics one stage at a time so checks stream to the client.

### Shopify exchange reconciliation

- `9070368` exchange pair verification (V1-V3, grace, streaming append); `388843f` registry
  pair/exchange-state lookup service fields; `b613f4b` wire pair verify into saved-run diff.
- `c2a768c` 3-hour sync grace + per-run pair cap with deferred reporting.
- **`864dde4` (breaking behavior)** presence semantics — enumerate Shopify exchanges in the
  return-date window via the new `exchangeSweepServiceName` slot, match against the OMS manifest,
  point-confirm unmatched candidates, append `EXCHANGE_MISSING_IN_OMS`. Amount checking (V2) and
  reverse-direction checks removed; manifest becomes optional context.
- `98eeccf` V2 compares the original order alone, not the pair sum; `36923b4` per-exchange presence
  counting + lookup cap 200; `4d9d58f` non-closed returns report as in transit; `ec5599e` chunk OMS
  pair lookups to the 100-id service cap.

### Auth and preferences

- `f720f5f` `save#UserSettings` persists preferred timezone; `sessionInfo` exposes raw user/tenant
  timezones. `affab65` fixes `displayName` clobbering on a timezone-only save.

### Build and infrastructure

- `792848a` MySQL Connector/J `8.3.0 -> 9.7.0` (prerequisite for the MySQL 8.4 server upgrade).
- `c973af2` give each test JVM its own Bitronix journal (test isolation).

### API contract

- `x-darpan-contract-version` `1 -> 2` (`ApiContractGenerator.CONTRACT_VERSION`), `info.version`
  `1.2.0` (read from `component.xml`). 90 methods total: 26 added, 2 removed.
- Regenerate with `./gradlew :runtime:component:darpan:generateApiContract`.

## UI

### Added

- `a361fae` tenant chat-space registry replaces single webhook; `c845c94` per-user default chat space.
- `c810268` chat-space wizard step (default/existing/new/none); `7017229` edit surface uses a dropdown.
- `511ebf8` notify-me subscription on running runs.
- `12e82da` watch a run live and stop it; drops dead API surface.
- `2348d90` run connection diagnostics from a connector dashboard.
- `24333be` preferred timezone card + searchable picker popup.
- `3beae18` app-wide display-timezone default + display-day helpers; `a64428f` effective display
  timezone (user pref > tenant > browser) synced to date utils.
- `0903b40` / `d054ccd` reconciliation day windows and run-source ranges resolve in display timezone.

### Changed

- `42465cc` settings wizards driven by a shared step machine.
- `0fde518` Preferences holds only what a user can change; `a79da3d` drop redundant list action.
- Diagnostics popup iterations: `e38fce1` no self-close, `6df061d` drop per-check timings,
  `eb8fd90` lead with verdict + checks as a rail, `d60b097` use the app blur backdrop not a scrim.

### Fixed

- `dbe908e` failed runs surface as Needs Attention instead of ghosting as Running.
- `9020053` merge-and-sort run history so hydrated older rows no longer displace the newest run.
- `98573c9` ignore Intl-invalid display timezone ids so bad stored values cannot break rendering.
- `cf12881` route `handleExternalAuthChange`'s no-token branch through `_applyAuthState`.
- `6157ce6` run result date range resolves UTC instants to local day; `c9282cd` schedule summary AM/PM.
- `a6e4fca` live-refresh runs after run-now + poll active executions.
- `ad9d104` stop the diagnostics badge escaping its row.
- `2b57578` transport-faithful error paths + wizard retry.

## Connector components

`darpan-hotwax` (`v0.4.0..HEAD`): `9730767` / `e8a7514` OMS connection-diagnostics probe, split into
two chained stages; `ccf2c41` `lookup#HotWaxOmsOrdersByExternalId` pair lookup, with `f7e0908`
externalId echo validation, `3e26271` plain-config-map fix (the secret-redacting map broke lookups),
`580b758` 10-year default pair window; `7103a14` excluded-exchange manifest sidecar.

`shopify-darpan` (`v0.4.0..HEAD`): `97788de` / `d499627` Shopify connection-diagnostics probe, split
into three chained stages; `99a7608` `lookup#ShopifyOrderExchangeState`; `5d6980f`
`lookup#ShopifyExchangeSweep` (in-window exchanges by return date); `46c2d50` sweep entries carry the
order's total exchange-return count; `cb99ea7` canceled exchange returns neither include nor count.

## Data and configuration

Seed/config records shipped in `data/upgrade-data.xml` (12 records, all traced to generic seed files):

| Source seed file | Records | Purpose |
| --- | --- | --- |
| `data/SecuritySeedData.xml` | `DARPAN_ADMIN_API` group, `admin\..*` member, 2 `ArtifactAuthz`, `ADMIN_PASSWORD` `UserGroupPermission` | Fence the `admin.*` namespace to super admins |
| `data/SecuritySeedData.xml` | `TenantChatSpace` + `ReconciliationRunNotifySubscription` group members, `DARPAN_SCOPE_TENANT_CHAT_SPACE` filter, `darpan.notification.defaultChatSpaceId` preference key | Chat-space registry entity exposure and per-user default |
| `data/AutomationSeedData.xml` | `AUT_STAT_CANCELLED` enumeration | FK target for `ReconciliationRunResult.statusEnumId` on cancel |
| `data/SourceSystemConnectorSeedData.xml` | OMS + SHOPIFY connector rows | `healthCheckServiceName` probe slots, `pairLookupServiceName`, `exchangeStateLookupServiceName`, `exchangeSweepServiceName` |

Ordering constraint: `moqui.security.ArtifactGroupMember` and `moqui.security.ArtifactAuthz` each
declare `relationship type="one" related="moqui.security.ArtifactGroup"`, which Moqui materializes
as a foreign key. The `entity-facade-xml` loader inserts in document order, so `DARPAN_ADMIN_API`
must precede its members and authz rows. The file is grouped parent-before-child deliberately —
re-sorting it alphabetically (as the preflight generator does) breaks the load.

Entity definitions changed: `entity/AdminEntities.xml`, `entity/AuthEntities.xml`,
`entity/ReconciliationEntities.xml`. Additions are additive; Moqui creates them on startup.

Configuration: `ApiContractGenerator.CONTRACT_VERSION` `1 -> 2`. MySQL Connector/J `8.3.0 -> 9.7.0`.

## Validation and rollout notes

Validation run for this cut:

- Backend `:runtime:component:darpan:cleanTest :test` under JDK 21 (Temurin 21.0.11):
  84 suites, 640 tests, 0 failures, 0 errors, 0 skipped, 10m 9s. Verified the `test` task executed
  rather than resolving `UP-TO-DATE`.
- UI `npm run check` at `darpan-ui@2.3.0`: eslint `--max-warnings=0`, `vue-tsc --build --force`,
  `vitest run --coverage` — 98 files, 814 tests, all passed.
- `scripts/check_contract_compat.py` `v1.1.0 -> HEAD`: exit 0.
- `upgrade-data.xml` parsed; all 12 records traced to a generic seed file.
- `data/releases/1.1.0/upgrade-data.xml` confirmed byte-identical to tag `v1.1.0`.

Not validated: no live/deployed smoke, no real `loadDarpanUpgradeData` execution, no live Shopify
or OMS tenant exercised, no browser verification, `migrate#TenantNotificationSettings` not run
against real multi-tenant data.

Rollout order: load upgrade data, deploy backend, run the notification migration once, then deploy
the app. Backend and UI pins move together — app `v2.3.0` requires the chat-space methods, which do
not exist on backend `v1.1.0`.

## References

- User release notes: `release-notes.md`
- Release checklist: `release-checklist.md`
- Upgrade data review: `upgrade-data-review.md`
- Current load target: `runtime/component/darpan/data/upgrade-data.xml`
- Previous release pack: `data/releases/1.1.0/`
- Public release page: `darpan-docs/releases/updates.mdx`
- Deploy pins: `darpan-docker-config/docker/prod/Dockerfile`

## Compare URLs

Fill in once tags are pushed:

- `https://github.com/drpn-ai/darpan/compare/v1.1.0...v1.2.0`
- `https://github.com/drpn-ai/darpan-ui/compare/v2.2.0...v2.3.0`
- `https://github.com/drpn-ai/darpan-hotwax/compare/v0.4.0...v0.5.0`
- `https://github.com/drpn-ai/shopify-darpan/compare/v0.4.0...v0.5.0`
