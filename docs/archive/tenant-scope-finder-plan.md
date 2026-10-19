# P0 #4 — Authoritative tenant-scoped finder for `disableAuthz` reads

**Goal:** make tenant scoping the DEFAULT for every `disableAuthz` read so a call site cannot silently skip it, closing the cross-tenant leak/write gaps. Scope chosen: **full sweep** (all 96 blocks routed through the finder + CI enforcement).

**Inventory (src/main):** 96 `disableAuthz` blocks — **15 DANGEROUS**, 46 SCOPED-OK, 35 GLOBAL-OK.

## Canonical scoping (TenantAccessSupport.groovy)
- Tenant filter = `companyUserGroupId == TenantAccessSupport.currentActiveTenantUserGroupId(ec)` (== `customerScopeId` == `activeTenant?.userGroupId`). For non-super-admins, `currentActiveTenantUserGroupId` only ever resolves a tenant the user belongs to.
- `buildAccessScope(ec)` (:67) publishes the scope Map into `ec.user.context` — it does NOT constrain a query by itself.
- Post-read gates: `canAccessTenantRecord(ec, record, companyField="companyUserGroupId")` (:371, default-deny on null tenant/mismatch); `requireTenantRecordAccess(ec, record, ...)` (:379, null→not-found, mismatch→forbidden).
- **Transitively-owned** entities have no `companyUserGroupId` and must be gated through their parent: `Rule`→`RuleSet` (ruleSetId); `RuleSetCompareScope`→`RuleSet` (ruleSetId); `RuleSetCompareSource`→`RuleSetCompareScope` (compareScopeId); `ReconciliationMappingMember`→`ReconciliationMapping` (reconciliationMappingId).

## Finder API (new `TenantScopedFinder.groovy` in `facade/common`, delegating to TenantAccessSupport)
Tenant scoping is the DEFAULT; opting out is loud and named.
- `EntityFind findTenantScoped(ec, entityName)` — EntityFind with `disableAuthz()` AND `condition("companyUserGroupId", currentActiveTenantUserGroupId(ec))` pre-applied. Null active tenant ⇒ impossible condition / empty-result (default-deny, never global fall-open).
- `EntityValue findTenantScopedById(ec, entityName, pkField, pkValue)` — load by PK under disableAuthz, then `requireTenantRecordAccess(ec, rec)` before returning.
- `findTenantScopedChildren(ec, childEntity, parentEntity, parentPkField, parentPkValue, childFkField)` — resolves + gates the parent via `canAccessTenantRecord`/`requireTenantRecordAccess` (mandatory), THEN returns children.
- `EntityFind findGlobalUnscoped(ec, entityName, String reason)` — deliberately verbose, separately-named opt-out for legitimate tenant-neutral reads (framework reference data, enums, self-scoped auth keyed by current userId / token hash, system-cron sweeps). Requires a justification string + audit-logs the read.
- **Enforcement:** a build-time test bans bare `.disableAuthz()` in `src/main/groovy/darpan/**` (allowlist = the finder file). Report-only first (current 96 sites allowlisted), then fail-mode after migration.

## The 15 DANGEROUS reads (must-fix)
Confirmed-reachable (head the order): `RuleEngineSupport.groovy:237` (RuleSet by PK → rule-logic disclosure via compileRuleSet/testRules screen transitions — note: screens are super-admin-gated post-Task-9, but harden + add a tenant gate on the transitions) · `:307` (Rule list by ruleSetId) · `reconcileGenericFiles.groovy:83` (ReconciliationRunResult read+UPDATE by caller PK → cross-tenant WRITE).
Defense-in-depth (safe today via gated callers / allow-remote=false): `AutomationRuntimeSupport.groovy:16,26` (ReconciliationAutomation/Source by automationId) · `reconcileFilesByMapping.groovy:90` (ReconciliationMappingMember) `:172` (ReconciliationMapping) · `ReconciliationServices.groovy:508,518` (RuleSetCompareScope) `:533` (RuleSetCompareSource) · `reconcileGenericFiles.groovy:59` (ReconciliationMapping name) `:69` (RuleSet) · `RuleSetCompareScopeAdapter.groovy:47` (RuleSetCompareScope + findRelated sources) · `ReconciliationSavedRunSupport.groovy:542` (Rule via ruleSetId) `:740` (NsRestletConfig label).

## Migration steps
1. **Finder + report-only lint** (no behavior change). Unit tests: default-deny on null tenant; parent-gate raises on foreign parent; findGlobalUnscoped requires a reason.
2. **Fix the 2 live-risk sites** first: RuleEngineSupport:237/:307 (route RuleSet via findTenantScopedById, Rule via findTenantScopedChildren; add tenant gate on RuleSetDetail.xml:24,60 transitions); reconcileGenericFiles:83 (findTenantScopedById so a foreign runResultId can't be read/overwritten). Cross-tenant-denial tests.
3. **Migrate remaining 13 DANGEROUS reads** through the matching finder (directly-owned → findTenantScoped(ById); transitively-owned → findTenantScopedChildren). Add denial tests.
4. **Route the 46 SCOPED-OK reads** through the finder for consistency (prioritise AutomationFacade :218 result path / :949 sftp label, and ReconciliationOutputSupport:308 conditional fall-open → make unconditional/empty on null tenant). Existing suites stay green.
5. **Mark the 35 GLOBAL-OK reads** via findGlobalUnscoped(reason) (framework data / self-scoped auth / system-cron); harden listAllTenantRecords against non-super-admin enumeration; **flip the lint to fail-mode**.

## Decisions resolved
- Official P0 number: count BLOCKS = 96 (15/46/35); ~10 are writes/service calls, not pure reads (the audit's "93" was reads only). Use 96 going forward.
- The 2 confirmed-reachable head the fix order (step 2); the screen path is already super-admin-gated (Task 9) so no separate hotfix needed — fold into the sweep.
- System-cron reads (StuckRunReaper:71, loadActiveAutomations:795) → `findGlobalUnscoped(reason="system-cron cross-tenant sweep")`; assert their `allow-remote=false` invariant.
- ReconciliationOutputSupport:308 fall-open → make the tenant condition unconditional (empty on null tenant). listAllTenantRecords → add an internal super-admin gate.
