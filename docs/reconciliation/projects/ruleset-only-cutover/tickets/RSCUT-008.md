# RSCUT-008: Mapping Runtime Decommission and Screen Disablement

## Metadata
- Ticket ID: RSCUT-008
- Title: Mapping Runtime Decommission and Screen Disablement
- Depends On: RSCUT-005, RSCUT-007
- Wave: Wave 4
- Owner: Agent

## Goal
Complete hard-break runtime decommission of mapping execution path and hide/disable mapping UI routes.

## In Scope
- Remove `reconcile#FilesByMapping` service contract.
- Remove/deprecate `reconcileFilesByMapping.groovy` runtime path.
- Remove Mapping menu entry from Reconciliation navigation.
- Disable direct Mapping screen access with explicit deprecation message.

## Out of Scope
- Deleting mapping entities/tables.
- Data migration logic changes.
- Final documentation sweep.

## Dependencies
- RSCUT-005 and RSCUT-007 completed.

## Files to Touch
- `service/reconciliation/ReconciliationCoreServices.xml`
- `src/main/groovy/darpan/reconciliation/core/reconcileFilesByMapping.groovy` (remove or archive as inactive)
- `screen/Reconciliation.xml`
- `screen/Reconciliation/Mapping/MappingSetup.xml`

## Contract/API Changes
- Removed service: `reconciliation.ReconciliationCoreServices.reconcile#FilesByMapping`
- Mapping UI route no longer reachable from active Reconciliation navigation.

## Implementation Steps
1. Remove `reconcile#FilesByMapping` service definition from `ReconciliationCoreServices.xml`.
2. Delete the mapping router script file or move to an explicitly inactive/deprecated location not referenced by services.
3. Remove `MappingSetup` subscreen item from `screen/Reconciliation.xml`.
4. Update `MappingSetup.xml` to return an explicit deprecation/disabled message if directly accessed.
5. Verify no active runtime service/screen references remain to mapping execution path.

## Acceptance Criteria
- `reconcile#FilesByMapping` is absent from active service definitions.
- Reconciliation menu no longer shows Mapping.
- Direct Mapping route is blocked with a deprecation response.
- Active runtime scripts/screens no longer call mapping router path.

## Validation Commands and Expected Results
1. Command: `rg -n "reconcile#FilesByMapping" service/reconciliation src/main/groovy/darpan/reconciliation screen/Reconciliation`
   Expected: No active runtime references.
2. Command: `rg -n "MappingSetup" screen/Reconciliation.xml`
   Expected: No menu/subscreen entry remains.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Build and organization checks pass.

## Rollback Plan
- Restore removed service definition and mapping router script.
- Re-add Mapping subscreen item in `screen/Reconciliation.xml`.
- Restore previous `MappingSetup.xml` behavior.

## Risks and Mitigations
- Risk: Hidden dependency still calls removed service.
  Mitigation: run repository-wide grep and verify before merge.
- Risk: Operators need temporary mapping access for diagnostics.
  Mitigation: keep mapping entities as historical data; document direct DB/query approach in docs ticket.

## Handoff Inputs for Next Ticket
- Confirmed hard-break runtime state for docs/seed alignment in `RSCUT-009`.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
