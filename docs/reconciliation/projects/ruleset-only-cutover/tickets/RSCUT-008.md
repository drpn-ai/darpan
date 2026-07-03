# RSCUT-008: SFTP Screen RuleSet Compare Scope Selector

## Metadata
- Ticket ID: RSCUT-008
- Title: SFTP Screen RuleSet Compare Scope Selector
- Depends On: RSCUT-007
- Wave: Wave 3
- Owner: Agent

## Goal
Update SFTP schedule create/edit/list flows to use RuleSet compare scopes instead of Mapping records.

## In Scope
- Replace Mapping dropdown with RuleSet and compare-scope selection.
- Save job parameters as `ruleSetId` and `compareScopeId`.
- Display RuleSet, compare scope, object type, and primary ID expression in existing schedules list.
- Keep schedule cadence and advanced settings behavior unchanged.

## Out of Scope
- Backend service contract changes.
- Generic screen changes.
- Mapping entity deprecation.

## Dependencies
- RSCUT-007 completed.

## Files to Touch
- `screen/Reconciliation/Automation/SftpAutomation.xml`
- Related docs only if operator copy changes need explanation.

## Contract/API Changes
- UI and job-parameter binding changes:
  - before: `reconciliationMappingId`
  - after: `ruleSetId`, `compareScopeId`
- Pre-action dropdown source changes from Mapping entity to RuleSet compare-scope config.

## Implementation Steps
1. Replace Mapping preload query with RuleSet compare-scope preload query.
2. Update save transition validation for `ruleSetId` and `compareScopeId`.
3. Update schedule parameter map to write `ruleSetId` and `compareScopeId`.
4. Update edit prefill to read new job parameters.
5. Display RuleSet compare scope labels in the schedules table.
6. Add temporary compatibility display for old Mapping jobs if backend compatibility remains active.

## Acceptance Criteria
- SFTP schedule form stores and loads `ruleSetId` and `compareScopeId`.
- New schedules no longer require Mapping records.
- Existing schedules table shows RuleSet compare-scope context.
- Existing Mapping-based jobs remain readable until RSCUT-009 migration if compatibility is retained.

## Validation Commands and Expected Results
1. Command: `rg -n "reconciliationMappingId|mappingList|mappingLabel" screen/Reconciliation/Automation/SftpAutomation.xml`
   Expected: No active new-schedule dependency remains, except explicit migration compatibility if retained.
2. Command: `rg -n "ruleSetId|compareScopeId|objectType|primaryIdExpression" screen/Reconciliation/Automation/SftpAutomation.xml`
   Expected: RuleSet compare-scope bindings are present.
3. Command: `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Screen organization check passes.

## Rollback Plan
- Revert `SftpAutomation.xml` to Mapping selector and job parameters.
- Confirm backend compatibility mode remains available if rollback is needed.

## Risks and Mitigations
- Risk: old scheduled jobs are hidden or become uneditable before migration.
  Mitigation: keep explicit compatibility display until RSCUT-009 closes.

## Handoff Inputs for Next Ticket
- Confirmed UI no longer creates new Mapping-dependent schedules.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
