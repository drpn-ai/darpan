# RSCUT-003: Base Missing-Object Diff Stage

## Metadata
- Ticket ID: RSCUT-003
- Title: Base Missing-Object Diff Stage
- Depends On: RSCUT-002
- Wave: Wave 2
- Owner: Agent

## Goal
Use the compare-scope extraction adapter to emit missing-object Diffs before any DRL rules run.

## In Scope
- Run Spark anti-joins for primary IDs extracted from file 1 and file 2.
- Emit `MISSING_IN_FILE_1` and `MISSING_IN_FILE_2` Diff rows.
- Produce matched object pairs for IDs present in both files.
- Preserve current Diff output structure and count semantics.

## Out of Scope
- DRL execution on matched pairs.
- Generic/SFTP caller changes.
- Mapping deprecation.

## Dependencies
- RSCUT-002 completed.

## Files to Touch
- `src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy`
- New helper under `src/main/groovy/darpan/reconciliation/core/` if needed.
- `service/reconciliation/ReconciliationCoreServices.xml`
- `docs/reconciliation/json-reconciliation.md`

## Contract/API Changes
- New or updated internal service may expose a preparation step such as `prepare#RuleSetCompareScope`.
- Output includes:
  - `missingInFile1Count`
  - `missingInFile2Count`
  - `matchedPairCount`
  - missing-object Diff rows or output location
  - matched-pair dataset/fact source for RSCUT-004

## Implementation Steps
1. Call the compare-scope extraction adapter for both file sides.
2. Use Spark anti-joins to find IDs only in file 1 and only in file 2.
3. Emit missing-object Diff rows using the existing Diff writer/output conventions.
4. Join records with IDs present in both files.
5. Return a matched-pair dataset or bounded fact source for DRL execution.
6. Preserve output filenames, locations, and warnings conventions.

## Acceptance Criteria
- Missing object Diffs are produced without DRL.
- `MISSING_IN_FILE_1` means ID exists in file 2 but not file 1.
- `MISSING_IN_FILE_2` means ID exists in file 1 but not file 2.
- Matched pairs exclude all missing IDs.
- Counts match current Mapping-backed direct compare behavior for representative inputs.

## Validation Commands and Expected Results
1. Command: `rg -n "MISSING_IN_FILE_1|MISSING_IN_FILE_2|left_anti|antiJoin|matchedPair" src/main/groovy/darpan/reconciliation service/reconciliation`
   Expected: Base missing-object and matched-pair logic is present.
2. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.
3. Command: `echo "Run compare-scope base diff against known two-file fixture"`
   Expected: Missing counts match baseline Mapping direct compare.

## Rollback Plan
- Revert core compare-stage changes.
- Confirm previous Mapping compare output still works.

## Risks and Mitigations
- Risk: missing direction labels are inverted.
  Mitigation: use fixtures with one ID missing from each side and assert both labels.
- Risk: matched pair generation materializes too much data.
  Mitigation: keep Spark datasets through the join stage and only materialize controlled batches for DRL.

## Handoff Inputs for Next Ticket
- Stable matched-pair fact source and missing-object Diff output contract.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
