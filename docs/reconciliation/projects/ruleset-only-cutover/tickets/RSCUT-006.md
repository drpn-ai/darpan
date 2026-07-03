# RSCUT-006: Generic UI RuleSet Compare Scope Selector

## Metadata
- Ticket ID: RSCUT-006
- Title: Generic UI RuleSet Compare Scope Selector
- Depends On: RSCUT-005
- Wave: Wave 3
- Owner: Agent

## Goal
Update the Generic Reconciliation screen to select RuleSet compare scopes instead of Mapping records.

## In Scope
- Replace Mapping dropdown with RuleSet and compare-scope selection.
- Submit `ruleSetId` and `compareScopeId` to the Generic backend.
- Show object type and primary ID expression context.
- Preserve existing file upload/text behavior and output list behavior.

## Out of Scope
- Backend service contract changes.
- SFTP screen changes.
- Mapping entity deprecation.

## Dependencies
- RSCUT-005 completed.

## Files to Touch
- `screen/Reconciliation/GenericReconciliation/Main.xml`
- Related docs only if operator copy changes need explanation.

## Contract/API Changes
- UI request payload changes:
  - before: `reconciliationMappingId`
  - after: `ruleSetId`, `compareScopeId`
- Dropdown source changes:
  - before: `darpan.mapping.ReconciliationMapping`
  - after: RuleSet compare-scope config.

## Implementation Steps
1. Replace Mapping preload query with RuleSet compare-scope preload query.
2. Update `runGenericReconciliation` transition input map.
3. Display RuleSet, compare scope, object type, and primary ID expression.
4. Preserve existing file and result UI sections.
5. Add empty-state guidance when no active compare scopes exist.

## Acceptance Criteria
- Generic UI no longer requires Mapping selection.
- Generic UI submits `ruleSetId` and `compareScopeId`.
- Operator can see what object and primary ID are being compared.
- Existing output list interactions remain unchanged.

## Validation Commands and Expected Results
1. Command: `rg -n "darpan.mapping.ReconciliationMapping|reconciliationMappingId" screen/Reconciliation/GenericReconciliation/Main.xml`
   Expected: No active UI dependency remains, except explicit migration copy if retained.
2. Command: `rg -n "ruleSetId|compareScopeId|objectType|primaryIdExpression" screen/Reconciliation/GenericReconciliation/Main.xml`
   Expected: RuleSet compare-scope bindings are present.
3. Command: `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Organization verification passes for updated screen route.

## Rollback Plan
- Revert `Main.xml` to Mapping selector.
- Confirm backend compatibility mode remains available if rollback is needed.

## Risks and Mitigations
- Risk: operators cannot identify the correct compare scope.
  Mitigation: display object type, RuleSet name, and primary ID expressions in the selector text.

## Handoff Inputs for Next Ticket
- UI selector pattern for SFTP screen update.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
