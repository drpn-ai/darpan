# RSCUT-005: Generic Reconciliation UI Cutover to RuleSet Selector

## Metadata
- Ticket ID: RSCUT-005
- Title: Generic Reconciliation UI Cutover to RuleSet Selector
- Depends On: RSCUT-004
- Wave: Wave 3
- Owner: Agent

## Goal
Update Generic Reconciliation screen to use RuleSet selection instead of Mapping selection, aligned with the backend cutover.

## In Scope
- Replace mapping preload query with RuleSet query.
- Replace form field from `reconciliationMappingId` to `ruleSetId`.
- Update form labels/help text to RuleSet terminology.
- Keep page-display behavior and navigation unchanged.

## Out of Scope
- Service contract changes (handled in RSCUT-004).
- SFTP screen changes.
- Mapping menu removal.

## Dependencies
- RSCUT-004 completed.

## Files to Touch
- `screen/Reconciliation/GenericReconciliation/Main.xml`

## Contract/API Changes
- UI request payload for transition `runGenericReconciliation` changes:
  - before: `reconciliationMappingId`
  - after: `ruleSetId`
- Dropdown source changes:
  - before: `darpan.mapping.ReconciliationMapping`
  - after: `darpan.rule.RuleSet`

## Implementation Steps
1. Update `runGenericReconciliation` transition `in-map` to send `ruleSetId`.
2. Replace pre-action entity-find list from mapping entity to RuleSet entity.
3. Replace form field name and dropdown key/text mappings for RuleSet (`ruleSetId`, `ruleSetName`).
4. Update user-facing labels and empty-state message to RuleSet-first wording.
5. Preserve existing file list, download/view/delete widgets unchanged.

## Acceptance Criteria
- Generic Reconciliation form displays RuleSet dropdown and submits `ruleSetId`.
- No mapping entity lookup remains in this screen.
- Existing output list interactions remain unchanged.

## Validation Commands and Expected Results
1. Command: `rg -n "darpan.mapping.ReconciliationMapping|reconciliationMappingId" screen/Reconciliation/GenericReconciliation/Main.xml`
   Expected: No matches.
2. Command: `rg -n "darpan.rule.RuleSet|ruleSetId" screen/Reconciliation/GenericReconciliation/Main.xml`
   Expected: Matches in pre-actions and form/transition bindings.
3. Command: `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Organization verification passes for updated screen route.

## Rollback Plan
- Revert `Main.xml` to mapping-based dropdown and parameter names.
- Confirm route and form references match backend expectation before retry.

## Risks and Mitigations
- Risk: User confusion due terminology switch.
  Mitigation: update field title/help text to explicitly state "Rule Set" and expected purpose.

## Handoff Inputs for Next Ticket
- Confirmed RuleSet field names and screen bindings for SFTP parity in `RSCUT-007`.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
