# Technical Changelog For Darpan 3.4.11

## Versioning decision

- Patch on the 3.4 line: no method added or removed (contract stays 5), no schema or data change. The CLI fence widens
  and one service gains a role check.

## Source ranges

- Backend: `v3.4.10..v3.4.11` (https://github.com/drpn-ai/darpan/compare/v3.4.10...v3.4.11)
- UI: not included
- CLI: `drpn-ai/darpan-cli` `v0.2.5..v0.3.0`

## Backend

### Changed
- `darpan.facade.common.CliMethodAllowlist` (01d914a1): 80 tenant-app methods move to ALLOWED — every excluded
  `ReconciliationFacadeServices` write but `list#AutomationSourceOptions`; `AuditFacadeServices` create/add/save,
  acknowledgements, feedback, `verify#AuditFindings`, `get#AuditRecheckProgress`; all of `PeakPeriodFacadeServices` and
  `SyncPulseFacadeServices`; `AgentChangeFacadeServices` plan/apply/reject/revert and `list#BrainChangeTickets`;
  `TenantConfigFacadeServices` preview/restore/snapshot; `JsonSchemaFacadeServices`; `save#NsSuiteQlSourceQuery`;
  `save#TenantQueryPins`; `store#SourceConfigEndpointAccess`; four `SettingsFacadeServices` and `save#UserSettings`.
  Credentials, sign-in/identity, installs, `admin.*` and the embedded agent stay EXCLUDED.

### Fixed
- `SourceEndpointWriteSupport.storeAccess` (07ff9d06): requires active-tenant write access ("Your active tenant only has
  view access for connections."); before, any member of the owning tenant could store endpoint access.

### Tests
- `CliMethodAllowlistTests`: never-allowed list extended; sign-in/identity guard; credential-noun guard with named
  masked getters (bb9daacc); tenant-app edits pinned.
- `RpcMethodFenceTests`: a moved write passes the fence; a credential save is refused.
- `SourceEndpointAccessSupportTests.storeServiceRefusesAViewOnlyMemberOfTheOwningTenant`.
- `CliParityViewRefusalSmokeTests` (a6a472ee): a view-only member is refused by 20 moved writes.

## Upgrade data
- None.
