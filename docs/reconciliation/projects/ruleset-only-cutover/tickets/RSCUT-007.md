# RSCUT-007: SFTP Automation Screen and Job Editor Cutover

## Metadata
- Ticket ID: RSCUT-007
- Title: SFTP Automation Screen and Job Editor Cutover
- Depends On: RSCUT-006
- Wave: Wave 3
- Owner: Agent

## Goal
Update SFTP Automation UI and schedule parameter handling to use RuleSet IDs in create/edit/list flows.

## In Scope
- Replace mapping dropdown with RuleSet dropdown.
- Save job parameter as `ruleSetId`.
- Display RuleSet labels in existing schedules list.
- Keep schedule cadence and advanced settings behavior unchanged.

## Out of Scope
- Backend service contract changes (RSCUT-006).
- Migration script behavior (RSCUT-002).
- Mapping screen decommission.

## Dependencies
- RSCUT-006 completed.

## Files to Touch
- `screen/Reconciliation/Automation/SftpAutomation.xml`

## Contract/API Changes
- UI and job-parameter binding rename:
  - before: `reconciliationMappingId`
  - after: `ruleSetId`
- Pre-action dropdown source changes from mapping entity to RuleSet entity.

## Implementation Steps
1. Update save transition validation to require `ruleSetId`.
2. Update schedule parameter map to write `ruleSetId` key.
3. Replace mapping preload query with RuleSet preload query.
4. Replace mapping label map with RuleSet label map.
5. Update form field name/title and existing jobs table column text to RuleSet terminology.
6. Ensure edit prefill reads `ruleSetId` from job parameters.

## Acceptance Criteria
- SFTP schedule form stores and loads `ruleSetId`.
- Existing schedules table shows RuleSet label column.
- No mapping entity lookup or mapping field remains in this screen.

## Validation Commands and Expected Results
1. Command: `rg -n "reconciliationMappingId|mappingList|mappingLabel" screen/Reconciliation/Automation/SftpAutomation.xml`
   Expected: No matches.
2. Command: `rg -n "ruleSetId|ruleSetList|ruleSetLabel|darpan.rule.RuleSet" screen/Reconciliation/Automation/SftpAutomation.xml`
   Expected: Matches in pre-actions, form fields, and list rendering.
3. Command: `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Screen organization check passes.

## Rollback Plan
- Revert `SftpAutomation.xml` to mapping-based parameter and dropdowns.
- Confirm form and list render correctly with previous schema.

## Risks and Mitigations
- Risk: Existing jobs created before migration appear blank in edit mode.
  Mitigation: pair deployment with `RSCUT-002` apply-mode migration before using updated screen.

## Handoff Inputs for Next Ticket
- Confirmed UI no longer references mapping IDs and is ready for decommission ticket `RSCUT-008`.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
