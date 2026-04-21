# RSCUT-005: Generic Backend RuleSet Compare Contract

## Metadata
- Ticket ID: RSCUT-005
- Title: Generic Backend RuleSet Compare Contract
- Depends On: RSCUT-004
- Wave: Wave 3
- Owner: Agent

## Goal
Cut Generic reconciliation backend execution from Mapping-backed input to RuleSet compare-scope input while preserving output compatibility.

## In Scope
- Add `ruleSetId` and optional `compareScopeId` to Generic service contract.
- Route Generic reconciliation through the compare-scope extraction, base missing-object Diff, and matched-pair DRL stages.
- Preserve current output fields and file staging behavior.
- Optionally keep temporary compatibility for `reconciliationMappingId` only as a migration bridge.

## Out of Scope
- Generic UI selector changes.
- SFTP contract changes.
- Mapping entity deprecation.

## Dependencies
- RSCUT-004 completed.

## Files to Touch
- `service/reconciliation/ReconciliationGenericServices.xml`
- `src/main/groovy/darpan/reconciliation/generic/reconcileGenericFiles.groovy`
- `docs/reconciliation/json-reconciliation.md`

## Contract/API Changes
- Service changed: `reconciliation.ReconciliationGenericServices.reconcile#GenericFiles`
- New required input:
  - `ruleSetId`
- New optional input:
  - `compareScopeId` (required when RuleSet has multiple active scopes)
- Compatibility input:
  - `reconciliationMappingId` may remain temporarily but must be documented as migration-only.

## Implementation Steps
1. Add `ruleSetId` and optional `compareScopeId` to Generic service XML.
2. Resolve a default compare scope when exactly one active scope exists for the RuleSet.
3. Route execution through the RuleSet compare pipeline from RSCUT-004.
4. Preserve output fields: `diffFileName`, counts, warnings, validation errors.
5. Add a clear error for ambiguous/missing compare scope.
6. Document temporary Mapping compatibility if retained.

## Acceptance Criteria
- Generic backend can run without `reconciliationMappingId`.
- Generic backend produces missing-object Diffs before rule Diffs.
- Generic backend runs DRL only on matched object pairs.
- Existing consumers receive compatible output fields.

## Validation Commands and Expected Results
1. Command: `rg -n "ruleSetId|compareScopeId|reconciliationMappingId" service/reconciliation/ReconciliationGenericServices.xml src/main/groovy/darpan/reconciliation/generic/reconcileGenericFiles.groovy`
   Expected: New RuleSet compare contract is present; Mapping compatibility is explicit if retained.
2. Command: `rg -n "MISSING_IN_FILE_1|MISSING_IN_FILE_2|MatchedRecordPair|FIELD_MISMATCH" src/main/groovy/darpan/reconciliation docs/reconciliation/json-reconciliation.md`
   Expected: Generic flow reaches base and DRL Diff stages.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.

## Rollback Plan
- Revert Generic service/script changes.
- Restore Mapping-only Generic execution while keeping lower-level compare-scope services if they remain valid.

## Risks and Mitigations
- Risk: UI still submits Mapping while backend expects RuleSet.
  Mitigation: retain compatibility until RSCUT-006 is deployed or release both tickets together.
- Risk: multiple scopes create ambiguous execution.
  Mitigation: require `compareScopeId` whenever more than one active scope exists.

## Handoff Inputs for Next Ticket
- Final Generic request fields and error behavior for UI selector changes.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
