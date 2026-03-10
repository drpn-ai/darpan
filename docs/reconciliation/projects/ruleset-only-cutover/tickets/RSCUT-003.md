# RSCUT-003: RuleSet Router Service (`reconcile#FilesByRuleSet`)

## Metadata
- Ticket ID: RSCUT-003
- Title: RuleSet Router Service (`reconcile#FilesByRuleSet`)
- Depends On: RSCUT-001
- Wave: Wave 2
- Owner: Agent

## Goal
Introduce a RuleSet-based reconciliation router that resolves file configuration through RuleSet source config plus rule execution, then delegates to unified reconciliation.

## In Scope
- Add `reconcile#FilesByRuleSet` service contract.
- Add router script implementation for RuleSet-based resolution.
- Enforce presence-rule output contract before delegation.
- Forward resolved values to `reconcile#UnifiedFiles`.

## Out of Scope
- Generic/SFTP caller contract switch.
- Mapping path removal.
- Screen updates.

## Dependencies
- RSCUT-001 completed.

## Files to Touch
- `service/reconciliation/ReconciliationCoreServices.xml`
- `src/main/groovy/darpan/reconciliation/core/reconcileFilesByRuleSet.groovy` (new)
- `src/main/groovy/darpan/reconciliation/core/ReconciliationServices.groovy` (optional naming context alignment)

## Contract/API Changes
- New service: `reconciliation.ReconciliationCoreServices.reconcile#FilesByRuleSet`
- Required inputs:
  - `ruleSetId`, `file1Location`, `file2Location`, `file1SystemEnumId`, `file2SystemEnumId`
- Optional overrides:
  - `file1FileTypeEnumId`, `file2FileTypeEnumId`
  - `file1SchemaFileName`, `file2SchemaFileName`
  - `file1Label`, `file2Label`, `hasHeader`, `outputLocation`, `sparkMaster`, `sparkAppName`
- Rule fact payload contract (per file side):
  - `role`, `systemEnumId`, `sourcePresent`, `fileTypeEnumId`, `schemaFileName`, `idFieldExpression`, `idValueNormalizer`, `fileName`
- Rule output expectations:
  - `routeSelected` Boolean
  - optional `routeError` String

## Implementation Steps
1. Add service definition in `ReconciliationCoreServices.xml` with in/out parameters mirroring existing router outputs.
2. Build router script:
   - Load `RuleSetSourceConfig` for the specified `ruleSetId` and file systems.
   - Build two routing facts (`FILE1`, `FILE2`) with presence flags and config values.
3. Execute rules via `reconciliation.ReconciliationRuleEngineServices.execute#RuleSet` with `returnAllFacts=true`.
4. Validate routed facts:
   - both sides must have `routeSelected=true`
   - if any side has `routeError`, return/throw clear error
5. Resolve final compare inputs:
   - id expressions with optional normalizer suffix (`|NORMALIZER`)
   - file type/schema/labels with override precedence
6. Call `reconcile#UnifiedFiles` and pass through counts, diff path/name, and warnings.

## Acceptance Criteria
- New router service exists and compiles.
- Router enforces presence-rule contract (`routeSelected`/`routeError`).
- Router successfully delegates to unified reconciliation with resolved config.
- Output contract matches existing router output fields.

## Validation Commands and Expected Results
1. Command: `rg -n "reconcile#FilesByRuleSet|ruleSetId" service/reconciliation/ReconciliationCoreServices.xml`
   Expected: New service contract and required params are present.
2. Command: `rg -n "routeSelected|routeError|execute#RuleSet|RuleSetSourceConfig" src/main/groovy/darpan/reconciliation/core/reconcileFilesByRuleSet.groovy`
   Expected: Rule execution and fact validation logic is present.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.
4. Command: `./gradlew :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Organization verification passes.

## Rollback Plan
- Revert `ReconciliationCoreServices.xml` and remove `reconcileFilesByRuleSet.groovy`.
- Confirm no references to `reconcile#FilesByRuleSet` remain.

## Risks and Mitigations
- Risk: Rule output contract mismatch causes false failures.
  Mitigation: Validate and emit explicit diagnostic messages listing offending fact role/system.
- Risk: Normalizer precedence ambiguity.
  Mitigation: Keep explicit precedence: override input > rule-mutated value > stored source config.

## Handoff Inputs for Next Ticket
- Stable service name and parameters for Generic backend switch in `RSCUT-004`.
- Confirmed error behavior for missing routing selection.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
