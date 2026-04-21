# RSCUT-002: Compare Scope Extraction Adapter

## Metadata
- Ticket ID: RSCUT-002
- Title: Compare Scope Extraction Adapter
- Depends On: RSCUT-001
- Wave: Wave 1
- Owner: Agent

## Goal
Extract the existing file parsing, ID normalization, and source configuration logic into a reusable adapter driven by RuleSet compare-scope config instead of Mapping entities.

## In Scope
- Preserve existing CSV, JSON, and mixed-file extraction semantics.
- Resolve source configuration from `RuleSetCompareScope` and `RuleSetCompareSource`.
- Produce Spark datasets keyed by normalized primary ID.
- Preserve current normalizer behavior such as `SHOPIFY_GID_TAIL` and `TRAILING_DIGITS`.

## Out of Scope
- Anti-join Diff emission.
- DRL rule execution.
- Generic/SFTP caller changes.
- Mapping deprecation.

## Dependencies
- RSCUT-001 completed.

## Files to Touch
- `src/main/groovy/darpan/reconciliation/core/reconcileFilesByMapping.groovy` only to extract shared behavior if needed.
- New helper under `src/main/groovy/darpan/reconciliation/core/` for compare-scope extraction.
- `service/reconciliation/ReconciliationCoreServices.xml` if a narrow internal service wrapper is useful.
- `docs/reconciliation/json-reconciliation.md`

## Contract/API Changes
- Internal extraction API takes `ruleSetId`, `compareScopeId`, file locations, names, and optional overrides.
- Internal output includes:
  - file-side metadata
  - normalized primary ID dataset
  - raw or structured record payload needed for matched-pair rule facts
  - warnings and validation errors
- No external Generic/SFTP contract change in this ticket.

## Implementation Steps
1. Identify extraction/normalization code currently tied to Mapping members.
2. Extract or duplicate narrowly into a compare-scope adapter.
3. Resolve file-side config from RuleSet compare-scope sources.
4. Return normalized primary IDs as strings.
5. Return enough record payload to build matched-pair facts later.
6. Add clear validation errors for missing primary ID expression, unsupported file type, or unsupported normalizer.

## Acceptance Criteria
- Adapter can extract Product records by `productId` for both files.
- Existing normalizer behavior is preserved.
- Adapter does not require `darpan.mapping.ReconciliationMapping`.
- Implementation avoids unbounded `collect()` for large datasets.

## Validation Commands and Expected Results
1. Command: `rg -n "primaryIdExpression|RuleSetCompareSource|SHOPIFY_GID_TAIL|TRAILING_DIGITS" src/main/groovy/darpan/reconciliation/core service/reconciliation`
   Expected: Compare-scope extraction and normalizer handling are present.
2. Command: `rg -n "ReconciliationMappingMember|reconciliationMappingId" src/main/groovy/darpan/reconciliation/core/*Compare* src/main/groovy/darpan/reconciliation/core/*Scope*`
   Expected: New compare-scope adapter does not depend on Mapping entities.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy --console=plain`
   Expected: BUILD SUCCESSFUL.

## Rollback Plan
- Revert helper/service changes.
- Confirm existing Mapping router still runs through the previous implementation.

## Risks and Mitigations
- Risk: extraction adapter drifts from existing Mapping behavior.
  Mitigation: compare output against current Mapping extraction for representative CSV, JSON, and mixed inputs.
- Risk: raw record payload is too large for DRL.
  Mitigation: keep payload as structured rows and only materialize matched-pair facts in bounded batches.

## Handoff Inputs for Next Ticket
- Stable adapter output contract for missing-object Diff stage.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
