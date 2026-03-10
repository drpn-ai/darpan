# RSCUT-004: Generic Reconciliation Backend Contract Cutover

## Metadata
- Ticket ID: RSCUT-004
- Title: Generic Reconciliation Backend Contract Cutover
- Depends On: RSCUT-003
- Wave: Wave 3
- Owner: Agent

## Goal
Switch Generic reconciliation backend contract from mapping-based routing to RuleSet-based routing.

## In Scope
- Replace required input `reconciliationMappingId` with `ruleSetId` in Generic service contract.
- Update Generic reconciliation script to call `reconcile#FilesByRuleSet`.
- Remove backend mapping terminology in messages and validations.

## Out of Scope
- Generic UI form field changes.
- SFTP service/screen changes.
- Mapping service path removal.

## Dependencies
- RSCUT-003 completed.

## Files to Touch
- `service/reconciliation/ReconciliationGenericServices.xml`
- `src/main/groovy/darpan/reconciliation/generic/reconcileGenericFiles.groovy`

## Contract/API Changes
- Service changed: `reconciliation.ReconciliationGenericServices.reconcile#GenericFiles`
- Required in-parameter rename:
  - before: `reconciliationMappingId`
  - after: `ruleSetId`
- Delegation changed:
  - before: `reconcile#FilesByMapping`
  - after: `reconcile#FilesByRuleSet`

## Implementation Steps
1. Update Generic service XML parameter definition and descriptions to RuleSet terminology.
2. Update script input validation to require `ruleSetId`.
3. Update service call target to `reconcile#FilesByRuleSet` and pass `ruleSetId`.
4. Update service messages/logging strings to remove mapping references.
5. Keep output contract unchanged for downstream consumers.

## Acceptance Criteria
- Generic backend no longer references `reconciliationMappingId`.
- Generic backend calls `reconcile#FilesByRuleSet`.
- Existing output fields (`diffFileName`, counts, warnings) are preserved.

## Validation Commands and Expected Results
1. Command: `rg -n "reconciliationMappingId|reconcile#FilesByMapping" service/reconciliation/ReconciliationGenericServices.xml src/main/groovy/darpan/reconciliation/generic/reconcileGenericFiles.groovy`
   Expected: No matches.
2. Command: `rg -n "ruleSetId|reconcile#FilesByRuleSet" service/reconciliation/ReconciliationGenericServices.xml src/main/groovy/darpan/reconciliation/generic/reconcileGenericFiles.groovy`
   Expected: Required matches in both files.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.

## Rollback Plan
- Revert the two touched files to restore mapping-based input and router call.
- Re-run the grep commands to confirm mapping contract is restored.

## Risks and Mitigations
- Risk: Caller breakage if UI still posts old parameter name.
  Mitigation: Pair with `RSCUT-005` immediately in same wave.

## Handoff Inputs for Next Ticket
- Confirmed backend parameter name and required field for Generic UI update (`ruleSetId`).

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
