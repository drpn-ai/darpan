# RSCUT-004: DRL Matched-Pair Rule Execution

## Metadata
- Ticket ID: RSCUT-004
- Title: DRL Matched-Pair Rule Execution
- Depends On: RSCUT-003
- Wave: Wave 2
- Owner: Agent

## Goal
Execute RuleSet DRL rules only against matched object pairs and append rule-generated Diffs to the base missing-object Diff output.

## In Scope
- Define matched-pair fact shape for DRL.
- Execute the RuleSet for pairs whose primary ID exists in both files.
- Allow rules such as same SKU and same price for each product ID.
- Append field/business-rule Diffs to existing Diff output.
- Preserve missing-object Diffs emitted before this stage.

## Out of Scope
- Running DRL on objects missing from either file.
- Generic/SFTP caller changes.
- Mapping deprecation.

## Dependencies
- RSCUT-003 completed.

## Files to Touch
- `service/reconciliation/ReconciliationRuleEngineServices.xml`
- `src/main/groovy/darpan/reconciliation/rule/RuleEngineSupport.groovy`
- `src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy`
- `docs/reconciliation/rule-engine-services.md`

## Contract/API Changes
- Matched-pair fact contract:
  - `objectType`
  - `compareScopeId`
  - `primaryId`
  - `file1` record map
  - `file2` record map
- Rule-generated Diff contract:
  - `diffType`
  - `objectType`
  - `primaryId`
  - `field` when applicable
  - `file1Value`
  - `file2Value`
  - `ruleId`
  - optional severity/message

## Implementation Steps
1. Define how matched pairs are converted into DRL facts.
2. Execute `execute#RuleSet` for matched pairs in bounded batches if needed.
3. Add DRL examples for SKU mismatch and price mismatch.
4. Append generated Diff rows to the same output artifact as missing-object Diffs.
5. Preserve counts for missing and field/business-rule Diffs.
6. Add diagnostics for rule failures that identify `ruleSetId`, `compareScopeId`, and `primaryId` where possible.

## Acceptance Criteria
- DRL is not executed for missing-object rows.
- A SKU mismatch for a matched product produces a field Diff with the product primary ID.
- A price mismatch for a matched product produces a field Diff with the product primary ID.
- Missing-object Diffs and rule-generated Diffs appear in one stable output contract.
- Rule execution failures are reported without losing the base missing-object Diff evidence.

## Validation Commands and Expected Results
1. Command: `rg -n "MatchedRecordPair|primaryId|file1Value|file2Value|ruleId|execute#RuleSet" service/reconciliation src/main/groovy/darpan/reconciliation docs/reconciliation`
   Expected: Matched-pair rule execution contract is present.
2. Command: `rg -n "sku|price|FIELD_MISMATCH|MISSING_IN_FILE_1|MISSING_IN_FILE_2" docs/reconciliation/rule-engine-services.md docs/reconciliation`
   Expected: Example field rules and missing-object Diff outcomes are documented.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.

## Rollback Plan
- Revert Rule Engine and compare-output changes.
- Keep RSCUT-003 base missing-object Diff stage intact if it is independently valid.

## Risks and Mitigations
- Risk: DRL rule authoring becomes too technical for operators.
  Mitigation: store examples and generated templates for common field equality/tolerance checks.
- Risk: matched-pair payload size creates memory pressure.
  Mitigation: execute in bounded batches and keep Spark as the high-volume data preparation layer.

## Handoff Inputs for Next Ticket
- Stable `ruleSetId`/`compareScopeId` service contract and Diff output for Generic backend cutover.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
