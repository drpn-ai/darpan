# RSCUT-001: RuleSet Compare Scope Model Foundation

## Metadata
- Ticket ID: RSCUT-001
- Title: RuleSet Compare Scope Model Foundation
- Depends On: None
- Wave: Wave 1
- Owner: Agent

## Goal
Introduce RuleSet-owned compare-scope configuration that can represent the object identity and file-side extraction details currently carried by Mapping.

## In Scope
- Add or define compare-scope entities/config under the RuleSet model.
- Represent a compare object such as Product, Order, OrderLine, or InventoryItem.
- Represent source config for each file side.
- Capture primary ID expression and normalizer per file side.
- Update data-model docs with the new contract.

## Out of Scope
- Runtime extraction changes.
- Generic/SFTP contract changes.
- Mapping deprecation.
- Migration from existing Mapping rows.

## Dependencies
- None.

## Files to Touch
- `entity/RuleEntities.xml`
- `data/SecuritySeedData.xml`
- `docs/reconciliation/data-model/entity-model.md`

## Contract/API Changes
- New configuration concept: RuleSet compare scope.
- Suggested entities:
  - `darpan.rule.RuleSetCompareScope`
  - `darpan.rule.RuleSetCompareSource`
- Suggested key fields:
  - `compareScopeId`
  - `ruleSetId`
  - `objectType`
  - `fileSide` (`FILE_1`, `FILE_2`)
  - `systemEnumId`
  - `fileTypeEnumId`
  - `schemaFileName`
  - `recordRootExpression`
  - `primaryIdExpression`
  - `idValueNormalizer`
- No active service parameters change in this ticket.

## Implementation Steps
1. Add compare-scope entity definitions with descriptions and relationships.
2. Add source-side config entity with one row per file side.
3. Link compare scopes to `darpan.rule.RuleSet`.
4. Add security seed entries for any new entities.
5. Update data-model docs with the object identity and source extraction contract.

## Acceptance Criteria
- RuleSet compare-scope config can define product-level comparison by `productId`.
- Each file side can define a different primary ID expression if systems differ.
- Mapping entities remain unchanged in this ticket.
- No runtime service contract changes are made.

## Validation Commands and Expected Results
1. Command: `rg -n "RuleSetCompareScope|RuleSetCompareSource|primaryIdExpression|recordRootExpression" entity/RuleEntities.xml docs/reconciliation/data-model/entity-model.md`
   Expected: Entity/config fields and docs are present.
2. Command: `rg -n "RuleSetCompareScope|RuleSetCompareSource" data/SecuritySeedData.xml`
   Expected: Security seed references are present if entities are added.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Build and organization checks pass.

## Rollback Plan
- Revert entity, seed, and data-model docs touched by this ticket.
- Re-run validation commands to ensure no partial compare-scope references remain.

## Risks and Mitigations
- Risk: compare-scope model duplicates RuleSet fields already used elsewhere.
  Mitigation: keep identity/source details in compare-scope config and keep RuleSet as the rule container.
- Risk: future multi-object RuleSets need multiple scopes.
  Mitigation: model `compareScopeId` explicitly instead of assuming one scope forever.

## Handoff Inputs for Next Ticket
- Confirmed entity names, primary ID fields, file-side enum values, and relationship names.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
