# RSCUT-009: Seeds and Documentation Alignment

## Metadata
- Ticket ID: RSCUT-009
- Title: Seeds and Documentation Alignment
- Depends On: RSCUT-002, RSCUT-008
- Wave: Wave 4
- Owner: Agent

## Goal
Align seeds and documentation with RuleSet-first reconciliation contracts and remove active mapping-era guidance from user-facing docs.

## In Scope
- Update seed data strategy to include RuleSet-first examples where applicable.
- Update reconciliation docs to reference `ruleSetId` and RuleSet source config.
- Update code-map/domain references from mapping router to RuleSet router.
- Keep legacy mapping entities documented as historical/decommissioned runtime path.

## Out of Scope
- Additional runtime logic changes.
- New UI/UX behavior.
- Final validation report execution.

## Dependencies
- RSCUT-002 and RSCUT-008 completed.

## Files to Touch
- `data/MappingSeedData.xml` and/or add/adjust equivalent RuleSet seed records
- `docs/reconciliation/json-reconciliation.md`
- `docs/reconciliation/automation/sftp-reconciliation.md`
- `docs/reconciliation/data-model/entity-model.md`
- `docs/code-map.md`
- `docs/domains/reconciliation/README.md`
- `docs/reconciliation/platform/overview.md`

## Contract/API Changes
- Documentation-level contract updates:
  - `reconciliationMappingId` references replaced with `ruleSetId` for active flows.
  - router references updated to `reconcile#FilesByRuleSet`.
- Seed examples represent RuleSet-first configuration model.

## Implementation Steps
1. Update seed examples to include RuleSet + RuleSetSourceConfig references for file reconciliation defaults.
2. Replace active mapping-contract language in JSON and SFTP reconciliation docs.
3. Update data-model docs with RuleSetSourceConfig details and mapping runtime decommission note.
4. Update code-map and domain README to list the new router service/script path.
5. Add migration precondition note in docs: execute mapping migration service before cutover.

## Acceptance Criteria
- Active reconciliation docs consistently reference `ruleSetId` and RuleSet source config.
- Code map and domain docs point to RuleSet router path.
- Seed examples no longer instruct new setup via mapping entities.
- Legacy mapping references remain only in historical/deprecation context.

## Validation Commands and Expected Results
1. Command: `rg -n "reconciliationMappingId|reconcile#FilesByMapping" docs/reconciliation docs/code-map.md docs/domains/reconciliation/README.md`
   Expected: No active-contract references; only explicit deprecation context if present.
2. Command: `rg -n "ruleSetId|RuleSetSourceConfig|reconcile#FilesByRuleSet" docs/reconciliation docs/code-map.md docs/domains/reconciliation/README.md`
   Expected: Matches in all updated active-flow docs.
3. Command: `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Passes after docs/seed changes.

## Rollback Plan
- Revert touched docs and seed files.
- Re-run grep commands to confirm previous terminology and references restored.

## Risks and Mitigations
- Risk: stale docs cause operators to configure old mapping path.
  Mitigation: explicit migration prerequisite and hard-break notes in top-level reconciliation docs.

## Handoff Inputs for Next Ticket
- Consolidated docs and seed references ready for final cutover verification in `RSCUT-010`.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
