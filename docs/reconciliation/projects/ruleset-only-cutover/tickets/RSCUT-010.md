# RSCUT-010: Final Verification and Cutover Evidence

## Metadata
- Ticket ID: RSCUT-010
- Title: Final Verification and Cutover Evidence
- Depends On: RSCUT-009
- Wave: Wave 4
- Owner: Agent

## Goal
Execute and document final end-to-end verification for RuleSet-only cutover readiness, including rollback confidence checks.

## In Scope
- Run compile/organization checks across the component.
- Execute representative Generic and SFTP reconciliation scenario validations.
- Validate migration idempotency one final time.
- Produce a final evidence report with pass/fail and residual risks.

## Out of Scope
- New feature or contract changes.
- Additional refactoring.

## Dependencies
- RSCUT-009 completed.

## Files to Touch
- `docs/reconciliation/projects/ruleset-only-cutover/validation-report-template.md` (reference only)
- `docs/reconciliation/projects/ruleset-only-cutover/validation-report-final.md` (new evidence artifact)

## Contract/API Changes
- None. This ticket validates final behavior only.

## Implementation Steps
1. Run component-level compile and organization validation commands.
2. Validate migration service behavior:
   - dry-run summary
   - apply-mode idempotency
3. Validate Generic reconciliation flow with `ruleSetId`.
4. Validate SFTP automation flow with `ruleSetId` and migrated job params.
5. Validate mapping runtime decommission:
   - no active `reconcile#FilesByMapping`
   - mapping menu hidden/disabled
6. Create `validation-report-final.md` using report template with command evidence.

## Acceptance Criteria
- All validation commands pass.
- Final report includes command summaries, functional checks, and rollback readiness.
- No unresolved critical blockers remain.

## Validation Commands and Expected Results
1. Command: `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: BUILD SUCCESSFUL and verification pass.
2. Command: `rg -n "reconcile#FilesByMapping|reconciliationMappingId" service/reconciliation src/main/groovy/darpan/reconciliation screen/Reconciliation`
   Expected: No active runtime references.
3. Command: `rg -n "reconcile#FilesByRuleSet|ruleSetId|RuleSetSourceConfig" service/reconciliation src/main/groovy/darpan/reconciliation screen/Reconciliation docs/reconciliation`
   Expected: Active flow references present.
4. Command: `echo "Execute migration service dryRun and apply twice; record counts"`
   Expected: second apply run yields no duplicate creations.
5. Command: `echo "Execute one Generic and one SFTP reconciliation scenario with ruleSetId"`
   Expected: both flows produce diff outputs and counts without mapping-parameter errors.

## Rollback Plan
- If any critical validation fails, revert to pre-cutover commit range for this project series and rerun baseline checks.
- Restore mapping route/menu only if blocker prevents production operation and rollback decision is approved.

## Risks and Mitigations
- Risk: latent runtime path still expects mapping parameters.
  Mitigation: grep-based contract sweep plus functional run for both entry points.
- Risk: incomplete evidence makes review ambiguous.
  Mitigation: require report file with explicit pass/fail per command.

## Handoff Inputs for Next Ticket
- None. This is the terminal verification ticket for this project.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
