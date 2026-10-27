# Technical Changelog For Darpan 2.1.1

Release date: 2026-10-27

## Versioning decision

- Patch on the 2.1 line (Aditi, 2026-10-27: "Tag current main as 2.1.1"). The facade contract is additive only
  (3 services added, 2 outputs added to `request#PasswordReset`, none removed or changed), so `x-darpan-contract-version`
  stays; `component.xml` 2.1.0 -> 2.1.1. Every component with commits since its 2.1.0-set tag gets a patch tag.
- Per the hotfix rule, 2.1.1 is the 2.1 line's one public release: its notes carry the 2.1.0 story with these changes
  folded in, and the 2.1.0 pages are superseded.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.0..0174765` | 28 |
| darpan-ui | `v3.1.0..b870632` | 9 |
| darpan-hotwax | `v0.10.0..e1e8e68` | 2 |
| netsuite-darpan | `v0.4.0..ef47428` | 1 |
| shopify-darpan | `v0.8.0..c922ce0` | 4 |
| database-darpan | `v0.2.1..8a68d8f` | 1 |
| moqui-gql | `v0.1.0..cfa8295` | 3 |
| darpan-docker-config | `v2.1.0..2960a08` | 1 (plus the 2.1.1 pin commit) |

## Backend

### Added

- DAR-BE-142 (darpan `0174765`): `retry#AutomationExecutionFromExtracts`, `ExtractReplaySupport`;
  `repair#ConnectorRecordKeys`; the runner stamps each side's file name, DataManager path and record count as its read
  ends.
- DAR-UI-085 / DAR-ADMIN-008 (darpan `c36d332`): `get#AuditProgress`; `RunHealth` shared with
  `LiveOperationsRules` / `AdminLiveOperationsSupport` and `admin.ObserveAdminServices.get#LiveOperations`.
- DAR-ADMIN-006 / 007: admin transfer catalog, bundle export, dry run with plan and hash, apply in one transaction,
  `begin#SecretHandoff` with credentials sealed to the target (AES-GCM / HKDF).
- DAR-BE-137 (shopify-darpan `7c8ba8a`, `706ce49`; darpan `a3f7561`): completed-returns read, one row per order.
  DAR-CLIENT-005 (shopify-darpan `c922ce0`, darpan `58e7f67`): `orderRefunded`, RAILS audit R2-R6 tenant config.
- DAR-BE-107 / 108 / 138: stored streak (`AutomationStreak` without the 20 cap), `AutomationStreakBadge`,
  `TenantStreakQuest`, `TenantStreakFact`.
- DAR-BE-136 (moqui-gql `cfa8295`, darpan `119d2b7`): tenant-admin API keys pinned to the active tenant.

### Changed

- DAR-BE-139 (darpan `62775b5`): `request#PasswordReset` returns `outcome` and `emailAddressMasked`; emails greet by
  first name.
- DAR-BE-142 (darpan-hotwax `e1e8e68`): OMS orders page retry on 429/502/503/504, shared with the returns read.
- `create#RuleSetRun` folds a sent record root into connector keys.

### Fixed

- DAR-BE-135 (darpan `1583f80`, `5e63f1a`, `52a8d86`, `d95aa09`; darpan-hotwax `6d924b2`; netsuite-darpan `ef47428`;
  shopify-darpan `e2850ab`; database-darpan `8a68d8f`): server-minted ids; a named id only updates an owned or granted
  row; `resolveRuleSetRun`'s owner check restored; every `find...one()` seen by the tenant-pin guard; `TenantConclusion`
  gets its filter.
- DAR-ADMIN-006 (`e2d9945`): a null source column matches the target default, so re-preview converges.
- `AdminLiveOperationsSupport`: `EntityList.findAll` result is a Boolean in that call shape; the audit-name lookup no
  longer throws on a real list.

### Security

- DAR-BE-140: framework fork `6b616bbb` masks encrypted columns in `EntityValue` / condition strings; darpan `1946f07`
  pins it with tests. Framework is unpinned (`MOQUI_FRAMEWORK_REF=master`), so it ships with any image built after it.
- DAR-BE-135: cross-tenant id answers like a missing row (no existence leak).
- DAR-DOCS-005: public API paths refuse cookies, CORS without credentials (moqui-gql `406805b`, darpan `7eec048`,
  `438257d`; docker-config `2960a08`).

## UI

### Added

- DAR-UI-085 (`b870632`): audit play in progress on Run health, reload on finish.
- DAR-UI-083 (`62f0f22`): page loaders with rotating lines.
- DAR-BE-138 (`6c22dbc`, `593a069`): astronomy streak GIFs, old six removed.

### Changed

- DAR-UI-084 (`31ae4ad`, `a2901e8`, `bf72645`): forgot-password text link, six-box code, outcome page.
- DAR-UI-082 (`efe2472`): Home lists Audits first.

### Fixed

- DAR-BE-135 (`b6c8c05`): create forms stop asking for or deriving an id.

### Security

- None beyond DAR-BE-135's id handling.

## Data and configuration

- `data/upgrade-data.xml`: 5 `darpan-seed` records (4 `EntityFilter`, 1 `SourceSystemConnector` update). See
  `upgrade-data-review.md`.
- `data/RailsAuditData.xml` is `darpan-tenant-config`: not in the pack.
- New tables by add-missing on start: `AutomationStreakBadge`, `TenantStreakQuest`, `TenantStreakFact`, transfer and
  handoff entities; new execution columns for file stamps.
- Tests aligned to a delta pack: `TenantOwnedEntityFilterContractTests.PENDING_NEXT_PACK` emptied (the four filters
  ship here); `AuditCatalogContractTests` reads the audit vocabulary from the newest pack that carries it (2.1.0).
  `facade-contract.snapshot.txt` regenerated: 3 services and 2 outputs added, nothing else.

## Validation and rollout notes

- Deploy darpan with darpan-ui (`get#AuditProgress`, password-reset outcome) and with all connector components
  (DAR-BE-135 changed every config create path). The prod image pins all of them; build from docker-config `v2.1.1`.
- Order: start the image (add-missing), `loadDarpanUpgradeData`, `run#PendingMigrations`.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.0...v2.1.1
- https://github.com/drpn-ai/darpan-ui/compare/v3.1.0...v3.1.1
- https://github.com/drpn-ai/darpan-hotwax/compare/v0.10.0...v0.10.1
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.4.0...v0.4.1
- https://github.com/drpn-ai/shopify-darpan/compare/v0.8.0...v0.8.1
- https://github.com/drpn-ai/database-darpan/compare/v0.2.1...v0.2.2
- https://github.com/drpn-ai/moqui-gql/compare/v0.1.0...v0.1.1
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.0...v2.1.1
