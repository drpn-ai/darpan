# RSCUT-006: SFTP Automation Backend Contract Cutover

## Metadata
- Ticket ID: RSCUT-006
- Title: SFTP Automation Backend Contract Cutover
- Depends On: RSCUT-003
- Wave: Wave 3
- Owner: Agent

## Goal
Switch SFTP automation backend from mapping-based input to RuleSet-based input while preserving existing runtime output behavior.

## In Scope
- Rename required input from `reconciliationMappingId` to `ruleSetId` in automation service contract.
- Update poll script to require `ruleSetId` and call `reconcile#FilesByRuleSet`.
- Update runtime log/status strings to RuleSet terminology.

## Out of Scope
- SFTP schedule screen form/list changes.
- Migration job parameter rewrite logic.
- Mapping route/menu decommission.

## Dependencies
- RSCUT-003 completed.

## Files to Touch
- `service/reconciliation/ReconciliationAutomationServices.xml`
- `src/main/groovy/darpan/reconciliation/automation/pollSftpAndRunReconciliation.groovy`

## Contract/API Changes
- Service changed: `reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile`
- Required in-parameter rename:
  - before: `reconciliationMappingId`
  - after: `ruleSetId`
- Delegation changed:
  - before: `reconcile#FilesByMapping`
  - after: `reconcile#FilesByRuleSet`

## Implementation Steps
1. Update service XML in-parameters and descriptions to RuleSet terminology.
2. Update script validation to require `ruleSetId`.
3. Replace reconciliation service call target and parameter map to pass `ruleSetId`.
4. Replace log/message text containing "mapping" with "ruleSet" where it describes routing identity.
5. Preserve existing output fields and behavior (`dataAvailable`, diff info, counts, warnings).

## Acceptance Criteria
- SFTP automation backend no longer requires or references `reconciliationMappingId`.
- Script delegates to `reconcile#FilesByRuleSet`.
- Status outputs and diff generation behavior remain intact.

## Validation Commands and Expected Results
1. Command: `rg -n "reconciliationMappingId|reconcile#FilesByMapping" service/reconciliation/ReconciliationAutomationServices.xml src/main/groovy/darpan/reconciliation/automation/pollSftpAndRunReconciliation.groovy`
   Expected: No matches.
2. Command: `rg -n "ruleSetId|reconcile#FilesByRuleSet" service/reconciliation/ReconciliationAutomationServices.xml src/main/groovy/darpan/reconciliation/automation/pollSftpAndRunReconciliation.groovy`
   Expected: Required matches in both files.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.

## Rollback Plan
- Revert both files to restore mapping-based parameter and router call.
- Re-run grep validations to confirm rollback state.

## Risks and Mitigations
- Risk: Existing scheduled jobs still pass old parameter name.
  Mitigation: ensure `RSCUT-002` migration step is executed before production cutover.

## Handoff Inputs for Next Ticket
- Stable backend contract for SFTP screen update in `RSCUT-007`.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
