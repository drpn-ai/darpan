# RSCUT-002: Mapping to RuleSet Migration Service

## Metadata
- Ticket ID: RSCUT-002
- Title: Mapping to RuleSet Migration Service
- Depends On: RSCUT-001
- Wave: Wave 1
- Owner: Agent

## Goal
Provide an idempotent migration service that converts legacy mapping rows into RuleSet and RuleSetSourceConfig data, including default presence rules and SFTP job parameter rewrites.

## In Scope
- Add migration service contract and Groovy implementation.
- Support `dryRun` and `apply` behavior.
- Convert mapping definitions and members into RuleSet + RuleSetSourceConfig rows.
- Add default presence rules for migrated RuleSets.
- Rewrite `ServiceJobParameter` rows from `reconciliationMappingId` to `ruleSetId` for SFTP reconciliation jobs.

## Out of Scope
- Switching active runtime reconciliation callers to `ruleSetId`.
- Removing mapping runtime services/screens.
- UI changes.

## Dependencies
- RSCUT-001 completed.

## Files to Touch
- `service/reconciliation/ReconciliationMigrationServices.xml` (new)
- `src/main/groovy/darpan/reconciliation/migration/migrateMappingsToRuleSets.groovy` (new)
- `docs/reconciliation/automation/sftp-reconciliation.md` (migration usage note)

## Contract/API Changes
- New service: `reconciliation.ReconciliationMigrationServices.migrate#MappingsToRuleSets`
- Inputs:
  - `dryRun` (Boolean, default `true`)
  - `mappingId` (optional)
  - `rewriteSftpJobParams` (Boolean, default `true`)
- Outputs:
  - `mappingsScanned`, `ruleSetsCreated`, `sourceConfigsCreated`, `rulesCreated`, `jobsUpdated`
  - `warnings` list
  - `applied` flag

## Implementation Steps
1. Define the new service contract in `ReconciliationMigrationServices.xml` with clear in/out parameters.
2. Implement migration script behavior:
   - Load `darpan.mapping.ReconciliationMapping` and `ReconciliationMappingMember` rows.
   - Create `RuleSet` rows if absent, reusing mapping ID as `ruleSetId` when valid.
   - Create `RuleSetSourceConfig` rows for each mapping member if absent.
   - Canonicalize normalizers to `SHOPIFY_GID_TAIL` or `TRAILING_DIGITS`.
3. Create two default presence rules per migrated RuleSet when missing:
   - `*_SRC_PRESENT` sets `routeSelected=true` when `sourcePresent=true`.
   - `*_SRC_MISSING` sets `routeSelected=false` and `routeError` when `sourcePresent=false`.
4. If `rewriteSftpJobParams=true`, update `moqui.service.job.ServiceJobParameter`:
   - For service `reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile`
   - Rename `reconciliationMappingId` parameter to `ruleSetId` while preserving value.
5. Ensure idempotency by checking for existing RuleSet, source config, and rule IDs before create/update.
6. In `dryRun=true`, return a full report but do not persist changes.

## Acceptance Criteria
- New migration service exists and is callable.
- `dryRun` produces a non-mutating migration summary.
- `apply` mode creates missing RuleSet and RuleSetSourceConfig records without duplicates.
- Default presence rules are created once and not duplicated on repeated runs.
- SFTP job parameter rewrite updates only targeted jobs and is idempotent.

## Validation Commands and Expected Results
1. Command: `rg -n "migrate#MappingsToRuleSets|dryRun|rewriteSftpJobParams" service/reconciliation/ReconciliationMigrationServices.xml`
   Expected: Service definition includes required contract fields.
2. Command: `rg -n "SRC_PRESENT|SRC_MISSING|ServiceJobParameter|reconciliationMappingId|ruleSetId" src/main/groovy/darpan/reconciliation/migration/migrateMappingsToRuleSets.groovy`
   Expected: Rule creation and job parameter rewrite logic is present.
3. Command: `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Expected: Build and organization checks pass.
4. Command: `echo "Run migration service in dryRun=true via service runner"`
   Expected: Report shows scanned counts with no persisted changes.
5. Command: `echo "Run migration service in dryRun=false twice"`
   Expected: Second run reports zero net new records for already-migrated mappings.

## Rollback Plan
- Revert service XML and migration script files.
- If apply-mode was executed and rollback is required, restore DB backup or remove created `RuleSet`, `RuleSetSourceConfig`, and default rules using controlled cleanup scripts.

## Risks and Mitigations
- Risk: Invalid mapping IDs conflict with RuleSet ID constraints.
  Mitigation: sanitize IDs and emit warning mapping list.
- Risk: accidental rewrite of unrelated job parameters.
  Mitigation: filter by exact service name and exact old parameter name.

## Handoff Inputs for Next Ticket
- Migration contract and output report format.
- Confirmed default presence rule IDs and payload shape for use in `RSCUT-003`.

## Closure Checklist
- [ ] All acceptance criteria met.
- [ ] Validation evidence captured.
- [ ] No unresolved blockers.
- [ ] Handoff notes complete.
