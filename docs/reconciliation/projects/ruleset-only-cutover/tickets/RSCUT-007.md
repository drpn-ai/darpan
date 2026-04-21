# RSCUT-007: SFTP Automation RuleSet Compare Contract

## Metadata
- Ticket ID: RSCUT-007
- Title: SFTP Automation RuleSet Compare Contract
- Depends On: RSCUT-004
- Wave: Wave 3
- Owner: Agent

## Goal
Cut SFTP automation backend execution from Mapping job parameters to RuleSet compare-scope job parameters while preserving scheduling and output behavior.

## In Scope
- Add `ruleSetId` and optional `compareScopeId` to SFTP automation service contract.
- Route SFTP reconciliation through the RuleSet compare pipeline from RSCUT-004.
- Preserve current data availability, diff output, count, warning, and status behavior.
- Optionally keep temporary compatibility for existing `reconciliationMappingId` jobs until migration.

## Out of Scope
- SFTP schedule screen changes.
- Generic service/screen changes.
- Mapping entity deprecation.

## Dependencies
- RSCUT-004 completed.

## Files to Touch
- `service/reconciliation/ReconciliationAutomationServices.xml`
- `src/main/groovy/darpan/reconciliation/automation/pollSftpAndRunReconciliation.groovy`
- `docs/reconciliation/automation/sftp-reconciliation.md`

## Contract/API Changes
- Service changed: `reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile`
- New required input:
  - `ruleSetId`
- New optional input:
  - `compareScopeId` (required when RuleSet has multiple active scopes)
- Compatibility input:
  - `reconciliationMappingId` may remain temporarily for existing scheduled jobs.

## Implementation Steps
1. Add `ruleSetId` and optional `compareScopeId` to automation service XML.
2. Update poll script to resolve compare scope.
3. Route execution through the RuleSet compare pipeline.
4. Preserve existing schedule status and diff output behavior.
5. Add clear errors for ambiguous/missing compare scope.
6. Document temporary Mapping job compatibility if retained.

## Acceptance Criteria
- SFTP automation can run without `reconciliationMappingId`.
- Existing scheduled Mapping jobs remain supported until RSCUT-009 migration, if compatibility is retained.
- SFTP flow produces missing-object Diffs before rule Diffs.
- SFTP flow runs DRL only on matched object pairs.

## Validation Commands and Expected Results
1. Command: `rg -n "ruleSetId|compareScopeId|reconciliationMappingId" service/reconciliation/ReconciliationAutomationServices.xml src/main/groovy/darpan/reconciliation/automation/pollSftpAndRunReconciliation.groovy`
   Expected: New RuleSet compare contract is present; Mapping compatibility is explicit if retained.
2. Command: `rg -n "MISSING_IN_FILE_1|MISSING_IN_FILE_2|MatchedRecordPair|FIELD_MISMATCH" src/main/groovy/darpan/reconciliation docs/reconciliation/automation/sftp-reconciliation.md`
   Expected: SFTP flow reaches base and DRL Diff stages.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.

## Rollback Plan
- Revert automation service/script changes.
- Restore Mapping-only scheduled job execution while keeping lower-level compare-scope services if valid.

## Risks and Mitigations
- Risk: existing scheduled jobs fail if job parameter migration is not complete.
  Mitigation: retain compatibility until RSCUT-009 migration has evidence.
- Risk: multiple scopes create ambiguous scheduled execution.
  Mitigation: require `compareScopeId` whenever more than one active scope exists.

## Handoff Inputs for Next Ticket
- Final SFTP request/job parameter fields and error behavior for screen changes.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
