# Technical Changelog For Darpan 3.4.2

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- Patch: two defect fixes on the 3.4 line, no contract, schema, seed or migration change (facade contract stays 5).

## Source ranges

- Backend: `v3.4.1..v3.4.2` (`4e3e914f`, `684f7f2c`, then the release commit)
- UI: not included

## Backend

### Fixed

- **DAR-BE-341** (`684f7f2c`) `AutomationExecutionSupport.startParallelSourceExtract`: the `automation-side2-extract`
  worker re-logs the caller and carries the system tenant and authz state, but not the tenant the request resolved
  (`TenantAccessSupport.REQUESTED_TENANT_USER_GROUP_ID` ThreadLocal or the `X-Darpan-Tenant` header — neither crosses
  threads). With a user and no request, `resolveEffectiveActiveTenant` falls back to the saved preference, so
  `ReconciliationSavedRunSupport.extractReadFields`' tenant-scoped compare-scope lookup read as none for another tenant's
  run and side 2 was read with no `readFields`. The run thread now captures `currentActiveTenantUserGroupId(ec)` when the
  run has a user, and the worker runs inside `TenantAccessSupport.withCallerTenant` (re-checks membership; the
  flowchart background walk's pattern). A run with no user (scheduled) captures nothing and keeps the system tenant.
- **DAR-BE-339** (`4e3e914f`) `ExchangePairVerificationSupport.verifyExchangePairs`: `DEFAULT_MAX_LOOKUPS = 200` removed
  with its "confirming the first N … deferred" warning, the audit-note "deferred" clause and the `STAGE_VERIFY_EXCHANGE`
  `deferredLookupCount` metric (`RunVerificationSupport.exchangePairMetrics`). Lookups stay chunked 100 ids per call.
  `TenantNotificationSupport`: comment only.

### Security

- DAR-BE-341 narrows, never widens: the worker's tenant is re-validated against the caller's memberships
  (`withCallerTenant` throws `ArtifactAuthorizationException` otherwise), and a no-user run never takes a user tenant.

## UI

- Not included.

## Data and configuration

- No seed, schema, job, setting, remote, auth or runtime-config change. `upgrade-data.xml` is empty for 3.4.2.
- `docs/api-contract/openapi.json`: version line only (3.4.1 → 3.4.2). A full `generateApiContract` in the shared
  checkout dropped `facade.AgentTurnFacadeServices.send#AgentQueryTurn` because the local `darpan-agent` is `v0.4.0+2`
  while prod pins `v0.5.0`; that regeneration was discarded.

## Validation and rollout notes

- Deploy with the scheduler on: nothing to migrate. Rollback to 3.4.1 needs no data step.
- Compare: https://github.com/drpn-ai/darpan/compare/v3.4.1...v3.4.2
