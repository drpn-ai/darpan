# Final Validation Report

## Ticket
- Ticket ID: RSCUT-010
- Title: Final Verification and Cutover Evidence
- Date: 2026-04-22
- Executor: Codex

## Scope Validated
- RuleSet compare-scope reconciliation is the active Generic and SFTP execution path.
- Mapping migration, deprecated Mapping surfaces, missing-object diff directionality, and matched-pair DRL diffs are verified with backend smoke evidence.

## Commands Executed
1. Command:
   `./gradlew :runtime:component:darpan:compileGroovy :runtime:component:darpan:verifyOrganization --console=plain`
   Output summary:
   Executed from the synthetic clean backend root. `compileGroovy` completed and `verifyOrganization` passed with all component and docs paths resolved.
   Result: PASS

2. Command:
   `rg -n "ruleSetId|compareScopeId|RuleSetCompareScope|RuleSetCompareSource" service/reconciliation src/main/groovy/darpan/reconciliation screen/Reconciliation docs/reconciliation`
   Output summary:
   Active RuleSet compare-scope references were present across `ReconciliationCoreServices.xml`, `ReconciliationGenericServices.xml`, `ReconciliationAutomationServices.xml`, migration service/script, `SftpAutomation.xml`, and reconciliation docs.
   Result: PASS

3. Command:
   `rg -n "MISSING_IN_FILE_1|MISSING_IN_FILE_2|FIELD_MISMATCH|sku|price" src/main/groovy/darpan/reconciliation docs/reconciliation`
   Output summary:
   Found explicit missing-object diff types in `RuleSetCompareScopeDiffStage.groovy`, `FIELD_MISMATCH` emission in `RuleDiffSupport.groovy`, and SKU/price rule evidence in rule-engine and reconciliation docs.
   Result: PASS

4. Command:
   `export DARPAN_BACKEND_ROOT=/tmp/dar41-backend-root && ./gradlew -I /tmp/dar41-component-override.init.gradle -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home :runtime:component:darpan:test --tests darpan.reconciliation.core.RuleSetCompareScopeServiceSmokeTests --console=plain`
   Output summary:
   Passed. Verified compare-scope dataset preparation, missing-object diff directionality, empty `missingDiffDf` handling, duplicate primary ID rejection, matched-pair DRL rule execution, and preservation of base diffs when rule execution fails.
   Result: PASS

5. Command:
   `export DARPAN_BACKEND_ROOT=/tmp/dar41-backend-root && ./gradlew -I /tmp/dar41-component-override.init.gradle -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home :runtime:component:darpan:test --tests darpan.reconciliation.generic.GenericReconciliationServiceSmokeTests --console=plain`
   Output summary:
   Passed. Verified Generic reconciliation runs through `ruleSetId` + `compareScopeId`, writes one unified diff artifact, and still supports the temporary mapping bridge path.
   Result: PASS

6. Command:
   `export DARPAN_BACKEND_ROOT=/tmp/dar41-backend-root && ./gradlew -I /tmp/dar41-component-override.init.gradle -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home :runtime:component:darpan:test --tests darpan.reconciliation.automation.SftpAutomationServiceSmokeTests --console=plain`
   Output summary:
   Passed. Verified SFTP automation stages files, runs the RuleSet compare-scope path, uploads a unified diff artifact, and retains the temporary mapping bridge for legacy jobs.
   Result: PASS

7. Command:
   `export DARPAN_BACKEND_ROOT=/tmp/dar41-backend-root && ./gradlew -I /tmp/dar41-component-override.init.gradle -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home :runtime:component:darpan:test --tests darpan.reconciliation.migration.ReconciliationMigrationServiceSmokeTests --console=plain`
   Output summary:
   Passed. Verified one full migration lifecycle: dry-run summary, apply-mode creation, legacy SFTP job rewrite from `reconciliationMappingId` to `ruleSetId` + `compareScopeId`, migrated SFTP execution, and idempotent rerun with zero duplicate creates/rewrites.
   Result: PASS

## Functional Checks
- Positive path:
  Generic and SFTP flows both run with RuleSet compare-scope configuration and produce unified diff outputs. Matched-pair product rules emit SKU and price `FIELD_MISMATCH` rows.
  Result: PASS
- Negative path:
  Base compare emits `MISSING_IN_FILE_1` when an ID is present only in file 2 and `MISSING_IN_FILE_2` when present only in file 1. Duplicate primary IDs are rejected, and broken DRL preserves base missing-object diffs instead of dropping results.
  Result: PASS
- Regression path:
  Migration remains idempotent, legacy mapping-backed Generic/SFTP bridge paths still execute, and Mapping entities/screens/docs are explicitly marked deprecated historical configuration instead of active setup.
  Result: PASS

## Contract Checks
- Input/Output contract verified:
  `ruleSetId` plus optional `compareScopeId` is the active backend contract for Generic and SFTP callers. Unified diff output keeps compatible counts while extending metadata for RuleSet compare scopes.
- Entity/schema/service/screen contract verified:
  `RuleSetCompareScope` / `RuleSetCompareSource` are the active compare model. `ReconciliationMapping` / `ReconciliationMappingMember` remain only as deprecated historical migration input, and backend screen labels now reflect `Legacy Mappings`.

## Risks Observed
- An exploratory combined Gradle run of all four smoke classes in one JVM is still unstable because repeated Moqui re-init and auth fixture setup can contaminate cross-class state. Isolated suite runs pass consistently and were used as final evidence. This is a test-harness risk, not a product-path failure.

## Rollback Readiness
- Rollback procedure executed or simulated:
  Simulated only. Rollback remains the documented RSCUT project rollback: revert to the pre-RuleSet-compare-scope commit range, restore Mapping selector/routes if needed, and restore legacy SFTP job parameters from migration evidence before re-running baseline Mapping checks.
- Rollback result:
  No destructive rollback was executed during final verification. Existing migration and DAR-41 evidence is sufficient to drive rollback if required.

## Final Verdict
- Ready for dependent ticket: YES
- Notes:
  RuleSet compare-scope cutover is validated for backend compile/docs integrity, Generic execution, SFTP execution, migration idempotence, missing-object directionality, and matched-pair DRL behavior. No unresolved product blocker remains in the verified backend cutover path.
