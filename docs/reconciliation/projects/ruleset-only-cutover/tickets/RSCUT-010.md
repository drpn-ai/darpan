# RSCUT-010: Final Verification and Cutover Evidence

## Metadata
- Ticket ID: RSCUT-010
- Title: Final Verification and Cutover Evidence
- Depends On: RSCUT-009
- Wave: Wave 4
- Owner: Agent

## Goal
Execute and document final end-to-end verification for RuleSet compare-scope readiness, Mapping migration, and Mapping deprecation.

## In Scope
- Run compile/organization checks across the component.
- Execute representative Generic and SFTP reconciliation scenario validations.
- Validate Mapping migration idempotency one final time.
- Validate missing-object Diffs before DRL execution.
- Validate matched-pair DRL rules for SKU and price mismatch examples.
- Produce a final evidence report with pass/fail and residual risks.

## Out of Scope
- New feature or contract changes.
- Additional refactoring.
- Deleting Mapping tables.

## Dependencies
- RSCUT-009 completed.

## Files to Touch
- `docs/reconciliation/projects/ruleset-only-cutover/validation-report-template.md` (reference only)
- `docs/reconciliation/projects/ruleset-only-cutover/validation-report-final.md` (new evidence artifact)

## Contract/API Changes
- None. This ticket validates final behavior only.

## Implementation Steps
1. Run component-level compile and organization validation commands.
2. Validate migration behavior:
   - dry-run summary
   - apply-mode idempotency
3. Validate Generic reconciliation flow with `ruleSetId` and `compareScopeId`.
4. Validate SFTP automation flow with `ruleSetId` and `compareScopeId`.
5. Validate base missing-object Diffs:
   - ID present in file 2 but missing in file 1 emits `MISSING_IN_FILE_1`.
   - ID present in file 1 but missing in file 2 emits `MISSING_IN_FILE_2`.
6. Validate matched-pair DRL Diffs:
   - same product ID with different SKU emits SKU field mismatch.
   - same product ID with different price emits price field mismatch.
7. Confirm existing Diff output shape and counts remain stable or compatibly extended.
8. Create `validation-report-final.md` using report template with command evidence.

## Acceptance Criteria
- All validation commands pass.
- Final report includes command summaries, functional checks, and rollback readiness.
- Generic and SFTP callers work with RuleSet compare-scope config.
- Mapping migration is idempotent.
- Mapping entities/screens are deprecated after migration evidence.
- DRL-backed results match direct compare parity for representative inputs.
- No unresolved critical blockers remain.

## Validation Commands and Expected Results
1. Command: `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: BUILD SUCCESSFUL and verification pass.
2. Command: `rg -n "ruleSetId|compareScopeId|RuleSetCompareScope|RuleSetCompareSource" service/reconciliation src/main/groovy/darpan/reconciliation screen/Reconciliation docs/reconciliation`
   Expected: Active RuleSet compare-scope references are present.
3. Command: `rg -n "MISSING_IN_FILE_1|MISSING_IN_FILE_2|FIELD_MISMATCH|sku|price" src/main/groovy/darpan/reconciliation docs/reconciliation`
   Expected: Missing-object and matched-pair rule validations are represented.
4. Command: `echo "Execute migration service dryRun and apply twice; record counts"`
   Expected: second apply run yields no duplicate creates/rewrites.
5. Command: `echo "Execute one Generic and one SFTP reconciliation scenario with ruleSetId and compareScopeId"`
   Expected: both flows produce diff outputs and counts without Mapping-parameter errors.

## Rollback Plan
- If any critical validation fails, revert to the pre-RuleSet-compare-scope commit range for this project series and rerun baseline Mapping checks.
- Restore Mapping selector/routes and job parameters from migration evidence if production rollback is needed.

## Risks and Mitigations
- Risk: RuleSet compare-scope output differs from the Mapping baseline.
  Mitigation: final evidence must compare both missing-record directions and matched-pair field mismatches against known fixtures.
- Risk: incomplete evidence makes review ambiguous.
  Mitigation: require report file with explicit pass/fail per command and per functional scenario.

## Handoff Inputs for Next Ticket
- None. This is the terminal verification ticket for this project.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
