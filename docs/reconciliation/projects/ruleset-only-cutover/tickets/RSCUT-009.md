# RSCUT-009: Mapping Migration and Deprecation

## Metadata
- Ticket ID: RSCUT-009
- Title: Mapping Migration and Deprecation
- Depends On: RSCUT-006, RSCUT-008
- Wave: Wave 4
- Owner: Agent

## Goal
Migrate existing Mapping configuration and scheduled jobs to RuleSet compare-scope configuration, then mark Mapping entities and screens as deprecated historical configuration.

## In Scope
- Add an idempotent migration service from Mapping rows to RuleSet compare scopes.
- Convert Mapping members to RuleSet compare-source config.
- Convert scheduled SFTP job parameters from `reconciliationMappingId` to `ruleSetId` and `compareScopeId`.
- Update seed data strategy.
- Mark Mapping entities/screens/docs as deprecated after migration evidence exists.

## Out of Scope
- Deleting Mapping tables.
- Removing compatibility code before final validation.
- Additional runtime compare logic changes.

## Dependencies
- RSCUT-006 and RSCUT-008 completed.

## Files to Touch
- `service/reconciliation/ReconciliationMigrationServices.xml` (new or existing)
- `src/main/groovy/darpan/reconciliation/migration/migrateMappingsToRuleSetScopes.groovy` (new)
- `entity/MappingEntities.xml`
- `screen/Reconciliation/Mapping/MappingSetup.xml`
- `data/MappingSeedData.xml` and/or RuleSet compare-scope seed files.
- `docs/reconciliation/data-model/entity-model.md`
- `docs/reconciliation/json-reconciliation.md`
- `docs/reconciliation/automation/sftp-reconciliation.md`
- `docs/code-map.md`

## Contract/API Changes
- New migration service: `reconciliation.ReconciliationMigrationServices.migrate#MappingsToRuleSetScopes`
- Inputs:
  - `dryRun` (Boolean, default `true`)
  - `reconciliationMappingId` (optional)
  - `rewriteSftpJobParams` (Boolean, default `true`)
- Outputs:
  - `mappingsScanned`, `ruleSetsCreated`, `compareScopesCreated`, `sourcesCreated`, `jobsUpdated`
  - `warnings` list
  - `applied` flag
- Mapping entities become deprecated/historical config after successful migration.

## Implementation Steps
1. Implement dry-run and apply-mode migration service.
2. For each Mapping, create or reuse a RuleSet.
3. Create one compare scope using Mapping name/object inference or a safe default object type.
4. Create two source rows from Mapping members using file type, schema, ID expression, and normalizer.
5. Rewrite SFTP scheduled job parameters to `ruleSetId` and `compareScopeId`.
6. Mark Mapping entity descriptions and screens as deprecated/historical.
7. Update docs and seeds to use RuleSet compare-scope config for new setup.

## Acceptance Criteria
- Migration is idempotent.
- Existing Mapping rows can be translated into RuleSet compare-scope config without data loss.
- Existing SFTP jobs can be rewritten or explicitly reported if not migratable.
- New setup docs no longer instruct operators to create Mapping records.
- Mapping entities/screens are marked deprecated only after migration evidence exists.

## Validation Commands and Expected Results
1. Command: `rg -n "migrate#MappingsToRuleSetScopes|rewriteSftpJobParams|compareScopeId" service/reconciliation src/main/groovy/darpan/reconciliation/migration`
   Expected: Migration service and job rewrite logic are present.
2. Command: `rg -n "deprecated|historical|RuleSet compare scope|compareScopeId" entity/MappingEntities.xml screen/Reconciliation/Mapping docs/reconciliation docs/code-map.md`
   Expected: Mapping deprecation and new setup docs are aligned.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Build and organization checks pass.
4. Command: `echo "Run migration service in dryRun=false twice"`
   Expected: Second run reports zero duplicate RuleSets, compare scopes, source rows, or job rewrites.

## Rollback Plan
- Revert migration service and docs.
- If apply-mode was executed, restore DB backup or remove generated RuleSet compare-scope rows and restore job parameters from backup evidence.
- Revert Mapping deprecation wording if migration is rolled back.

## Risks and Mitigations
- Risk: Mapping rows do not contain enough object-type context.
  Mitigation: use a safe default and emit warnings requiring operator review.
- Risk: scheduled jobs are partially migrated.
  Mitigation: include exact job IDs and before/after parameter report in migration output.

## Handoff Inputs for Next Ticket
- Migration evidence, deprecated Mapping state, and final verification inputs.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
