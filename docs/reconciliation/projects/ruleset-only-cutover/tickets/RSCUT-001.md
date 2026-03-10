# RSCUT-001: RuleSetSourceConfig Data Model Foundation

## Metadata
- Ticket ID: RSCUT-001
- Title: RuleSetSourceConfig Data Model Foundation
- Depends On: None
- Wave: Wave 1
- Owner: Agent

## Goal
Introduce a structured RuleSet-linked source configuration model that can replace mapping-member runtime metadata in later tickets.

## In Scope
- Add `darpan.rule.RuleSetSourceConfig` entity with source-specific reconciliation fields.
- Add relationships to `darpan.rule.RuleSet` and relevant `moqui.basic.Enumeration` entities.
- Add security exposure for the new entity in Darpan app auth seed data.
- Update reconciliation data model docs.

## Out of Scope
- Any migration logic from mapping entities.
- Any service/screen contract cutover.
- Removal of mapping entities.

## Dependencies
- None.

## Files to Touch
- `entity/RuleEntities.xml`
- `data/SecuritySeedData.xml`
- `docs/reconciliation/data-model/entity-model.md`

## Contract/API Changes
- New entity: `darpan.rule.RuleSetSourceConfig`
- Proposed keys and fields:
  - PK: `ruleSetId`, `systemEnumId`
  - fields: `fileTypeEnumId`, `schemaFileName`, `idFieldExpression`, `idValueNormalizer`, `createdDate`, `lastUpdatedDate`
- No service API changes in this ticket.

## Implementation Steps
1. Add `RuleSetSourceConfig` entity definition in `RuleEntities.xml` under package `darpan.rule` and use `configuration`.
2. Define relationships:
   - `ruleSetId` -> `darpan.rule.RuleSet`
   - `systemEnumId` -> `moqui.basic.Enumeration.enumId`
   - `fileTypeEnumId` -> `moqui.basic.Enumeration.enumId` (title `FileType`)
3. Add descriptive field documentation for `idFieldExpression` and `idValueNormalizer`.
4. Update `SecuritySeedData.xml` to include `darpan.rule.RuleSetSourceConfig` under `DARPAN_APP` artifact group.
5. Update `entity-model.md` with a dedicated section for the new entity and operational intent.

## Acceptance Criteria
- `RuleEntities.xml` contains `RuleSetSourceConfig` with the exact fields in this ticket.
- `SecuritySeedData.xml` references the new entity in `DARPAN_APP` artifact group.
- `entity-model.md` documents the new entity and its purpose.
- No existing service contracts are changed in this ticket.

## Validation Commands and Expected Results
1. Command: `rg -n "entity-name=\"RuleSetSourceConfig\"|idValueNormalizer|idFieldExpression" entity/RuleEntities.xml`
   Expected: Entity and required fields are present.
2. Command: `rg -n "RuleSetSourceConfig" data/SecuritySeedData.xml docs/reconciliation/data-model/entity-model.md`
   Expected: Matches in both security seed and data-model docs.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Build completes successfully without XML/service organization errors.

## Rollback Plan
- Revert all changes in the three files listed in "Files to Touch".
- Re-run validation commands to ensure no residual references to `RuleSetSourceConfig` remain.

## Risks and Mitigations
- Risk: Incorrect relationship mapping to enumerations.
  Mitigation: Validate field names and relationship key maps against existing `Enumeration` usage patterns.
- Risk: Security artifact omission blocks UI/service access later.
  Mitigation: Ensure security seed includes the new entity in this ticket itself.

## Handoff Inputs for Next Ticket
- Confirmed entity schema and relationship names.
- Verified canonical field names for migration script mapping in `RSCUT-002`.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
