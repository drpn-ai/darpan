package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunObservability
import darpan.facade.reconciliation.RunVerificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationExecutionServiceSmokeTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String TEST_COMPANY_USER_GROUP_ID = "KREWE"
    // Task 13 fix round 1, Important 5: a second tenant, deliberately never made active for this
    // session, so a test can prove the backfill sweep is not scoped to the caller's active tenant.
    // GORJANA is already seeded as a real moqui.security.UserGroup by
    // ReconciliationSmokeTestSupport.seedSftpServerFixtures (called from setup() below) — reused
    // here rather than inventing a new tenant id, since companyUserGroupId is FK-checked against
    // UserGroup and an unseeded id fails the entity create outright.
    private static final String OTHER_TENANT_USER_GROUP_ID = "GORJANA"
    private static final String FILTER_TEST_MAPPING_ID = "AUTO_FILTER_TEST_MAPPING"
    // A SECOND automation for the end-to-end verification test, so its execution row cannot
    // collide with the .one() lookups the AUTO_API_ARTIFACT tests make against theirs.
    private static final String VERIFY_DIFF_AUTOMATION_ID = "AUTO_API_VERIFY_DIFF"

    private ExecutionContext ec
    private int filterFixtureCounter = 0

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "api-automation-execution-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        // The verification tests below resolve a real connector (OMS_RETURNS) to build their lookup;
        // without this the registry catalog is empty and buildVerificationLookup returns null for
        // every source. Mirrors HotWaxOmsRestSourceConfigFacadeSmokeTests, which loads it for the
        // same reason. The existing tests here inject their extractor via setSourceExtractor and are
        // unaffected by the registry being populated.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        // The returns fixtures below key on the OMS_RETURNS / SHOPIFY_RETURN_REFS systems, and
        // ReconciliationAutomationSource.systemEnumId is FK-checked against Enumeration -- without
        // these rows the source create fails on referential integrity before any assertion runs.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
        // Task 8 filter fixtures save real automations through save#Automation, which needs a source
        // type simple enough to pass validateSources without extra wiring. AUT_SRC_DB looks simplest
        // but databaseSourceQueryId is not actually threaded through save#Automation's sourceEntry
        // builder yet (a pre-existing, unrelated gap), so SFTP — the same source type
        // AutomationFacadeSmokeTests uses for its own non-API fixtures — is used instead.
        ReconciliationSmokeTestSupport.seedSftpServerFixtures(ec)
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetExecutionHooks()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void prepare() {
        ec.message.clearErrors()
        AutomationExecutionSupport.resetExecutionHooks()
        seedApiAutomation()
    }

    @AfterEach
    void resetHooks() {
        AutomationExecutionSupport.resetExecutionHooks()
    }

    @Test
    void apiAutomationWritesRunResultArtifactWhenRuleSetCompareReturnsOnlyDataset() {
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            String fileSide = source.get("fileSide")
            String location = fileSide == AutomationExecutionSupport.FILE_SIDE_1 ?
                    "component://darpan/data/test/test-orders-1.json" :
                    "component://darpan/data/test/test-orders-2.json"
            return [
                    dataAvailable: true,
                    fileLocation : location,
                    fileName     : "${fileSide}.json".toString(),
                    fileTypeEnumId: "DftJson",
                    recordCount  : 3,
            ]
        }

        Map<String, Object> result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId      : "AUTO_API_ARTIFACT",
                scheduledFireTime : Timestamp.valueOf("2026-05-01 10:00:00"),
                sparkMaster       : "local[1]",
                sparkAppName      : "AutomationExecutionServiceSmokeTests",
        ])

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(1, result.executedCount)

        def execution = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                .condition("automationId", "AUTO_API_ARTIFACT")
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(execution)
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
        assertEquals(4, execution.differenceCount)
        assertEquals("DARPAN_TEST_COMPARE_RS_result.json", execution.resultFileName)
        assertTrue((execution.resultDataManagerPath as String).startsWith("reconciliation-runs/DARPAN_TEST_COMPARE_RS/"))
        assertTrue((execution.resultDataManagerPath as String).endsWith("/DARPAN_TEST_COMPARE_RS_result.json"))
        assertNotNull(execution.reconciliationRunResultId)

        File outputFile = DataManagerSupport.resolveDataManagerFile(ec, execution.resultDataManagerPath, false)
        assertNotNull(outputFile)
        assertTrue(outputFile.exists())
        Map<String, Object> outputDocument = (Map<String, Object>) JSON_SLURPER.parseText(outputFile.getText("UTF-8"))
        assertEquals("AUTO_API_ARTIFACT", outputDocument.metadata.automationId)
        assertEquals("DARPAN_TEST_COMPARE_RS", outputDocument.metadata.savedRunId)
        assertEquals(TEST_COMPANY_USER_GROUP_ID, outputDocument.metadata.companyUserGroupId)
        assertEquals(4, outputDocument.summary.totalDifferences)
        assertEquals(4, ((List) outputDocument.differences).size())

        def runResult = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", execution.reconciliationRunResultId)
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(runResult)
        assertEquals(execution.resultDataManagerPath, runResult.resultDataManagerPath)
        assertEquals(TEST_COMPANY_USER_GROUP_ID, runResult.companyUserGroupId)
    }

    @Test
    void automationSaveCopiesSourceExclusionFilters() {
        String automationId = saveAutomationWithSourceFilters([
                [fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL"]],
        ])

        List rows = findAutomationFilterRows(automationId, "FILE_2")

        assertEquals(1, rows.size())
        assertEquals(1, rows[0].sequenceNum)
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
        assertEquals("EXCLUDE_IN", rows[0].operator)
        assertEquals("POS_SALES_CHANNEL,DRAFT_SALES_CHANNEL", rows[0].filterValues)
        assertNotNull(rows[0].companyUserGroupId)
        assertEquals(TEST_USER_ID, rows[0].createdByUserId)
    }

    @Test
    void resavingWithoutTheKeyPreservesFiltersAcrossTheSourceRecreate() {
        // The save deletes and recreates every source row, so the filters' parent disappears on each
        // save. "Leave unchanged" therefore has to survive a delete-and-recreate, not just a no-op.
        String automationId = saveAutomationWithSourceFilters([
                [fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]],
        ])
        resaveAutomationWithoutFilterKeys(automationId)

        List rows = findAutomationFilterRows(automationId, "FILE_2")

        assertEquals(1, rows.size())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
    }

    @Test
    void resavingWithAnEmptyListClearsFilters() {
        String automationId = saveAutomationWithSourceFilters([
                [fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]],
        ])
        resaveAutomationWithSourceFilters(automationId, [])

        assertEquals(0, findAutomationFilterRows(automationId, "FILE_2").size())
    }

    @Test
    void firstSaveSeedsFiltersFromTheRuleSet() {
        // Exclusions are only editable on the rules board, so a new automation submits none. Without
        // the seed it would run with no exclusions while its rule set has them.
        String compareScopeId = createRuleSetCompareScopeWithFilter("FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        String automationId = saveAutomationForCompareScope(compareScopeId)

        List rows = findAutomationFilterRows(automationId, "FILE_2")

        assertEquals(1, rows.size())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
        assertEquals("EXCLUDE_IN", rows[0].operator)
        assertEquals("POS_SALES_CHANNEL", rows[0].filterValues)
        // The seed is scoped to the fileSide it was written for — assert it did not also spray FILE_1.
        assertEquals(0, findAutomationFilterRows(automationId, "FILE_1").size())
    }

    @Test
    void firstSaveSeedsFiltersInRuleSetSequenceOrderRegardlessOfCreationOrder() {
        // Two rows created out of sequenceNum order, so a copy that (incorrectly) preserved creation
        // order instead of re-sorting by sequenceNum would be caught here.
        String compareScopeId = createRuleSetCompareScopeWithFilters("FILE_2", [
                [sequenceNum: 2, fieldExpression: "channelB", filterValues: "VALUE_B"],
                [sequenceNum: 1, fieldExpression: "channelA", filterValues: "VALUE_A"],
        ])
        String automationId = saveAutomationForCompareScope(compareScopeId)

        List rows = findAutomationFilterRows(automationId, "FILE_2")

        assertEquals(2, rows.size())
        assertEquals(1, rows[0].sequenceNum)
        assertEquals("channelA", rows[0].fieldExpression)
        assertEquals(2, rows[1].sequenceNum)
        assertEquals("channelB", rows[1].fieldExpression)
        assertEquals(0, findAutomationFilterRows(automationId, "FILE_1").size())
    }

    @Test
    void laterSavesDoNotReSeedOverAClearedSide() {
        String compareScopeId = createRuleSetCompareScopeWithFilter("FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        String automationId = saveAutomationForCompareScope(compareScopeId)
        resaveAutomationWithSourceFilters(automationId, [])
        resaveAutomationWithoutFilterKeys(automationId)

        assertEquals(0, findAutomationFilterRows(automationId, "FILE_2").size())
    }

    @Test
    void automationWithoutACompareScopeSeedsNothing() {
        String automationId = saveAutomationWithSourceFilters(null)

        assertEquals(0, findAutomationFilterRows(automationId, "FILE_2").size())
    }

    @Test
    void deletingAnAutomationRemovesItsFilterRows() {
        String automationId = saveAutomationWithSourceFilters([
                [fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]],
        ])
        deleteAutomation(automationId)

        assertEquals(0, findAutomationFilterRows(automationId, "FILE_2").size())
    }

    @Test
    void loadedAutomationReturnsExcludeFiltersInWireShape() {
        // Every other test here reads ReconciliationAutomationSourceFilter directly. None of them would
        // catch buildSourceExcludeFilterResponse regressing to [] for a populated side — which would
        // make the wizard rehydrate a draft showing no exclusions and a subsequent resave could then
        // submit [] explicitly, silently clearing the snapshot. Go through get#Automation (the load
        // path, not the save response) and assert wire shape: values as a List, not the stored CSV string.
        String automationId = saveAutomationWithSourceFilters([
                [fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN", values: ["POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL"]],
        ])

        Map<String, Object> loadResult = callFacade("facade.ReconciliationFacadeServices.get#Automation", [
                automationId: automationId,
        ])
        assertTrue((Boolean) loadResult.ok, loadResult.errors?.toString())

        List<Map<String, Object>> sources = ((Map) loadResult.automation).sources as List<Map<String, Object>>
        Map<String, Object> file2Source = sources.find { it.fileSide == "FILE_2" }
        assertNotNull(file2Source, "load result did not include a FILE_2 source")
        List<Map<String, Object>> file2Filters = file2Source.excludeFilters as List<Map<String, Object>>
        assertNotNull(file2Filters, "FILE_2 source did not carry an excludeFilters key")
        assertEquals(1, file2Filters.size())
        assertEquals("salesChannelEnumId", file2Filters[0].fieldExpression)
        assertEquals("EXCLUDE_IN", file2Filters[0].operator)
        assertEquals(["POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL"], file2Filters[0].values)

        Map<String, Object> file1Source = sources.find { it.fileSide == "FILE_1" }
        assertNotNull(file1Source, "load result did not include a FILE_1 source")
        assertEquals([], file1Source.excludeFilters ?: [])
    }

    // -- One-time backfill for pre-existing automations (Task 13) -----------------------------------

    @Test
    void backfillSeedsAnExistingAutomationThatHasNoFilterRows() {
        // The state every production automation is in: created before the feature existed, so both
        // source rows exist (which is exactly why the create-time seed can never fire for it).
        String compareScopeId = createRuleSetCompareScopeWithFilter("FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        String automationId = saveAutomationForCompareScope(compareScopeId)
        clearAutomationFilterRows(automationId)

        Map result = runBackfill()

        List rows = findAutomationFilterRows(automationId, "FILE_2")
        assertEquals(1, rows.size())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
        assertEquals("POS_SALES_CHANNEL", rows[0].filterValues)
        assertNotNull(rows[0].companyUserGroupId)
        assertTrue((result.rowsCreated as Integer) >= 1)
    }

    @Test
    void backfillIsIdempotentAndNeverOverwritesAnExistingSnapshot() {
        // Re-running must be safe: a snapshot deliberately frozen at an older rule-set state must
        // not be silently refreshed to the current one.
        String compareScopeId = createRuleSetCompareScopeWithFilter("FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        String automationId = saveAutomationForCompareScope(compareScopeId)
        replaceAutomationFilterRows(automationId, "FILE_2", "statusId", "ORDER_CANCELLED")

        runBackfill()
        runBackfill()

        List rows = findAutomationFilterRows(automationId, "FILE_2")
        assertEquals(1, rows.size())
        assertEquals("statusId", rows[0].fieldExpression)
    }

    @Test
    void backfillSkipsAutomationsWithNoCompareScope() {
        String automationId = saveAutomationWithoutCompareScope()

        Map result = runBackfill()

        assertEquals(0, findAutomationFilterRows(automationId, "FILE_2").size())
        assertTrue((result.automationsSkipped as Integer) >= 1)
    }

    @Test
    void backfillSeedsNothingWhenTheRuleSetHasNoExclusions() {
        String compareScopeId = createCompareScopeWithNoFilters()
        String automationId = saveAutomationForCompareScope(compareScopeId)
        clearAutomationFilterRows(automationId)

        runBackfill()

        assertEquals(0, findAutomationFilterRows(automationId, "FILE_1").size())
        assertEquals(0, findAutomationFilterRows(automationId, "FILE_2").size())
    }

    @Test
    void backfillCopiesBothSidesIndependently() {
        String compareScopeId = createRuleSetCompareScopeWithFilter("FILE_1", "displayFinancialStatus", "PENDING")
        addRuleSetCompareSourceFilter(compareScopeId, "FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        String automationId = saveAutomationForCompareScope(compareScopeId)
        clearAutomationFilterRows(automationId)

        runBackfill()

        assertEquals("displayFinancialStatus", findAutomationFilterRows(automationId, "FILE_1")[0].fieldExpression)
        assertEquals("salesChannelEnumId", findAutomationFilterRows(automationId, "FILE_2")[0].fieldExpression)
    }

    @Test
    void backfillPreservesSequenceOrderAcrossTheCopy() {
        String compareScopeId = createRuleSetCompareScopeWithFilter("FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        addRuleSetCompareSourceFilter(compareScopeId, "FILE_2", "statusId", "ORDER_CANCELLED")
        String automationId = saveAutomationForCompareScope(compareScopeId)
        clearAutomationFilterRows(automationId)

        runBackfill()

        List rows = findAutomationFilterRows(automationId, "FILE_2")
        // sequenceNum is a Moqui number-integer field, which the real EntityFacade returns as Long —
        // normalize to int before comparing against an Integer literal list (List.equals is strict
        // element-wise .equals(), with no numeric coercion, unlike the scalar assertEquals below).
        assertEquals([1, 2], (rows*.sequenceNum as List<Number>)*.intValue())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
        assertEquals("statusId", rows[1].fieldExpression)
    }

    @Test
    void backfillSweepsEveryTenantAndStampsEachAutomationsOwnCompanyUserGroupId() {
        // Task 13 fix round 1, Critical 1 + Important 5 — the central regression this round exists
        // for. Every other fixture in this file saves through save#Automation, which stamps the
        // caller's active session tenant (KREWE) onto everything, so no combination of them can tell
        // "the sweep processes every tenant" apart from "the sweep processes only mine". Build a
        // second tenant's rule set + automation directly (bypassing save#Automation) with no active
        // session tenant ever pointed at it, and prove the sweep still finds it, seeds it from its OWN
        // rule set (not an empty read silently swallowed by session-tenant scoping), and stamps the
        // new row with ITS OWN companyUserGroupId rather than the session's.
        Map otherTenantScope = createRuleSetCompareScopeWithFilterForTenant(
                OTHER_TENANT_USER_GROUP_ID, "FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        String otherTenantAutomationId = createAutomationForTenant(OTHER_TENANT_USER_GROUP_ID,
                otherTenantScope.compareScopeId as String, otherTenantScope.ruleSetId as String)

        String sameTenantCompareScopeId = createRuleSetCompareScopeWithFilter("FILE_2", "statusId", "ORDER_CANCELLED")
        String sameTenantAutomationId = saveAutomationForCompareScope(sameTenantCompareScopeId)
        clearAutomationFilterRows(sameTenantAutomationId)

        runBackfill()

        List otherTenantRows = findAutomationFilterRows(otherTenantAutomationId, "FILE_2")
        assertEquals(1, otherTenantRows.size(),
                "second tenant's automation was not seeded — the sweep is scoped to the caller's session tenant")
        assertEquals("salesChannelEnumId", otherTenantRows[0].fieldExpression)
        assertEquals(OTHER_TENANT_USER_GROUP_ID, otherTenantRows[0].companyUserGroupId)

        List sameTenantRows = findAutomationFilterRows(sameTenantAutomationId, "FILE_2")
        assertEquals(1, sameTenantRows.size())
        assertEquals("statusId", sameTenantRows[0].fieldExpression)
        assertEquals(TEST_COMPANY_USER_GROUP_ID, sameTenantRows[0].companyUserGroupId)
    }

    @Test
    void backfillSeedsAnEmptySideIndependentlyWhenTheOtherSideIsAlreadyPopulated() {
        // Minor, bundled with fix round 1: a regression that hoisted the existing-filter-rows check
        // out of the per-fileSide loop (checking once per automation instead of once per side) would
        // pass all the other backfill tests, since every other test starts from BOTH sides empty. Start
        // from FILE_1 already populated (seeded on the real first save) and only FILE_2 cleared, so a
        // per-automation (rather than per-fileSide) existence check would wrongly skip FILE_2 too.
        String compareScopeId = createRuleSetCompareScopeWithFilter("FILE_1", "displayFinancialStatus", "PENDING")
        addRuleSetCompareSourceFilter(compareScopeId, "FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        String automationId = saveAutomationForCompareScope(compareScopeId)
        assertEquals(1, findAutomationFilterRows(automationId, "FILE_1").size(),
                "test setup assumption failed: FILE_1 should already be seeded from the real first save")
        clearAutomationFilterRowsForSide(automationId, "FILE_2")

        runBackfill()

        List file1Rows = findAutomationFilterRows(automationId, "FILE_1")
        assertEquals(1, file1Rows.size())
        assertEquals("displayFinancialStatus", file1Rows[0].fieldExpression)
        List file2Rows = findAutomationFilterRows(automationId, "FILE_2")
        assertEquals(1, file2Rows.size())
        assertEquals("salesChannelEnumId", file2Rows[0].fieldExpression)
    }

    @Test
    void backfillOneAutomationsFailureDoesNotBlockALaterAutomationInTheSameSweep() {
        // Task 13 fix round 2 — discovered while addressing the "ok scoped to this call's own
        // errors" minor finding: Moqui's ServiceCallSyncImpl.callSingle refuses to run ANY service —
        // including the NEXT automation's own create# calls — while ec.message.hasError() is true
        // (checked at the very top of every single service call, framework-wide, before that
        // service's own actions ever run). Leaving a failed automation's error sitting on ec.message
        // for the rest of the sweep would silently no-op every automation processed after it: each
        // blocked create# call returns null without adding a NEW error, so the per-create# delta
        // check sees nothing to react to, and rowsCreated/sidesSeeded increment for rows that were
        // never actually written. Build one automation guaranteed to fail (compareScopeId set but no
        // matching ReconciliationAutomationSource rows — a real referential-integrity violation, the
        // same failure mode round 1's own fixture bug hit by accident) with an EXPLICIT id that sorts
        // before a normal, valid automation's own EXPLICIT id (the sweep reads in automationId order;
        // a Moqui sequenced id, as save#Automation would assign, is not lexicographically predictable
        // relative to a hand-picked prefix, so both ids here are explicit), and prove the valid one
        // still gets seeded and counted correctly despite running later in the very same sweep.
        String failingAutomationId = "A_BACKFILL_SWEEP_FAIL_${nextFilterFixtureId('X')}".toString()
        String validAutomationId = "B_BACKFILL_SWEEP_VALID_${nextFilterFixtureId('X')}".toString()
        assertTrue(failingAutomationId < validAutomationId,
                "test setup assumption failed: the failing automation id must sort before the valid one")

        Map<String, Object> failingScope = createRuleSetCompareScopeWithFilterForTenant(
                TEST_COMPANY_USER_GROUP_ID, "FILE_2", "salesChannelEnumId", "POS_SALES_CHANNEL")
        // withSourceRows=false: deliberately no ReconciliationAutomationSource rows, so this
        // automation's create# call trips a real referential-integrity violation.
        createAutomationForTenant(failingAutomationId, TEST_COMPANY_USER_GROUP_ID,
                failingScope.compareScopeId as String, failingScope.ruleSetId as String, false)

        Map<String, Object> validScope = createRuleSetCompareScopeWithFilterForTenant(
                TEST_COMPANY_USER_GROUP_ID, "FILE_2", "statusId", "ORDER_CANCELLED")
        createAutomationForTenant(validAutomationId, TEST_COMPANY_USER_GROUP_ID,
                validScope.compareScopeId as String, validScope.ruleSetId as String, true)

        // Not runBackfill() — this run is expected to report ok=false, unlike every other backfill
        // test, so it calls the service directly rather than through the shared ok=true-asserting helper.
        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.migrate#AutomationExcludeFilters")
                .disableAuthz()
                .call()

        assertFalse((Boolean) result.ok)
        assertTrue((result.errors as List).any { it.toString().contains(failingAutomationId) },
                "errors did not mention the failing automation: ${result.errors}")
        List rows = findAutomationFilterRows(validAutomationId, "FILE_2")
        assertEquals(1, rows.size(),
                "the valid automation, swept after the failing one, was not seeded — a prior failure blocked it")
        assertEquals("statusId", rows[0].fieldExpression)

        // The failing automation is deliberately never fixed (that is the point of this test) — every
        // later test in this @TestInstance(PER_CLASS) class shares this same database and would
        // otherwise re-sweep it on every runBackfill() call for the rest of the run, permanently
        // failing their ok=true assertion. Remove it now that this test is done with it.
        ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", failingAutomationId)
                .disableAuthz().useCache(false).deleteAll()
    }

    @Test
    void validationErrorsAreInvisibleToEcMessageGetErrorsButCaughtByFacadeSupportGetErrors() {
        // Task 13 fix round 2, New Important 2 regression coverage: AutomationFacadeSupport's
        // per-create# delta check now uses FacadeSupport.getErrors(ec) — errorList + validationErrorList
        // — instead of the narrower bare ec.message.getErrors() (errorList only). A create# that fails
        // Moqui PARAMETER validation adds a ValidationError, not a plain Error, and was previously
        // invisible to the delta check, letting rowsCreated increment for a row that was never
        // actually written. Moqui's own MessageFacadeImpl keeps these in genuinely separate lists
        // (getErrors() returns errorList only; addValidationError appends to validationErrorList), so
        // this calls addValidationError directly — the same public MessageFacade method the framework
        // itself uses internally on a real parameter-validation failure — to prove the distinction
        // deterministically, without depending on which internal Moqui code path a given malformed
        // value happens to be routed through.
        int errorCountBefore = ec.message.getErrors().size()
        int facadeErrorCountBefore = FacadeSupport.getErrors(ec).size()

        ec.message.addValidationError(null, "someField", "someService", "deliberately injected validation error", null)

        assertEquals(errorCountBefore, ec.message.getErrors().size(),
                "a validation error should NOT land in the narrower ec.message.errorList")
        assertEquals(facadeErrorCountBefore + 1, FacadeSupport.getErrors(ec).size(),
                "a validation error should be visible via FacadeSupport.getErrors (errorList + validationErrorList)")
        ec.message.clearErrors()
    }

    private void seedApiAutomation(String automationId = "AUTO_API_ARTIFACT") {
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomation", [automationId: automationId], [
                automationId            : automationId,
                automationName          : "API Automation Artifact Smoke",
                companyUserGroupId      : TEST_COMPANY_USER_GROUP_ID,
                createdByUserId         : TEST_USER_ID,
                inputModeEnumId         : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId              : "DARPAN_TEST_COMPARE_RS",
                savedRunType            : "ruleset",
                ruleSetId               : "DARPAN_TEST_COMPARE_RS",
                compareScopeId          : "DARPAN_TEST_ORDER_JSON_SCOPE",
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount     : 1,
                windowTimeZone          : "UTC",
                isActive                : "Y",
                createdDate             : ec.user.nowTimestamp,
                lastUpdatedDate         : ec.user.nowTimestamp,
        ])
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomationSource", [
                automationId: automationId,
                fileSide    : AutomationExecutionSupport.FILE_SIDE_1,
        ], [
                automationId         : automationId,
                fileSide             : AutomationExecutionSupport.FILE_SIDE_1,
                companyUserGroupId   : TEST_COMPANY_USER_GROUP_ID,
                createdByUserId      : TEST_USER_ID,
                sourceTypeEnumId     : AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                systemEnumId         : "SHOPIFY",
                fileTypeEnumId       : "DftJson",
                createdDate          : ec.user.nowTimestamp,
                lastUpdatedDate      : ec.user.nowTimestamp,
        ])
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomationSource", [
                automationId: automationId,
                fileSide    : AutomationExecutionSupport.FILE_SIDE_2,
        ], [
                automationId         : automationId,
                fileSide             : AutomationExecutionSupport.FILE_SIDE_2,
                companyUserGroupId   : TEST_COMPANY_USER_GROUP_ID,
                createdByUserId      : TEST_USER_ID,
                sourceTypeEnumId     : AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                systemEnumId         : "OMS",
                fileTypeEnumId       : "DftJson",
                createdDate          : ec.user.nowTimestamp,
                lastUpdatedDate      : ec.user.nowTimestamp,
        ])
    }

    /**
     * A returns automation with the two connectors the returns passes key on, seeded the way a real
     * one is: the API config id lives in safeMetadataJson.parameters, never as a column.
     * configIdsBySide maps a file side to its config id; omit a side to leave it with none.
     */
    private void seedReturnsAutomation(String automationId, Map<String, String> configIdsBySide) {
        seedSourcePair(automationId,
                [(AutomationExecutionSupport.FILE_SIDE_1): ["SHOPIFY_RETURN_REFS", "shopifyAuthConfigId"],
                 (AutomationExecutionSupport.FILE_SIDE_2): ["OMS_RETURNS", "omsRestSourceConfigId"]],
                configIdsBySide)
    }

    /** The same fixture for the orders connectors, which are the pair the exchange passes key on. */
    private void seedExchangeAutomation(String automationId, Map<String, String> configIdsBySide) {
        seedSourcePair(automationId,
                [(AutomationExecutionSupport.FILE_SIDE_1): ["SHOPIFY", "shopifyAuthConfigId"],
                 (AutomationExecutionSupport.FILE_SIDE_2): ["OMS", "omsRestSourceConfigId"]],
                configIdsBySide)
    }

    private void seedSourcePair(String automationId, Map<String, List<String>> specBySide,
                                Map<String, String> configIdsBySide) {
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomation",
                [automationId: automationId], [
                automationId            : automationId,
                automationName          : "Returns Verification Smoke",
                companyUserGroupId      : TEST_COMPANY_USER_GROUP_ID,
                createdByUserId         : TEST_USER_ID,
                inputModeEnumId         : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId              : "DARPAN_TEST_COMPARE_RS",
                savedRunType            : "ruleset",
                ruleSetId               : "DARPAN_TEST_COMPARE_RS",
                compareScopeId          : "DARPAN_TEST_ORDER_JSON_SCOPE",
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount     : 1,
                windowTimeZone          : "UTC",
                isActive                : "Y",
                createdDate             : ec.user.nowTimestamp,
                lastUpdatedDate         : ec.user.nowTimestamp,
        ])
        specBySide.each { side, spec ->
            String configId = configIdsBySide?.get(side)
            upsertEntityValue("darpan.reconciliation.ReconciliationAutomationSource",
                    [automationId: automationId, fileSide: side], [
                    automationId      : automationId,
                    fileSide          : side,
                    companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                    createdByUserId   : TEST_USER_ID,
                    sourceTypeEnumId  : AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                    systemEnumId      : spec[0],
                    fileTypeEnumId    : "DftJson",
                    // Exactly where the API connectors keep it, and nowhere else.
                    safeMetadataJson  : configId ? JsonOutput.toJson([parameters: [(spec[1]): configId]]) : null,
                    createdDate       : ec.user.nowTimestamp,
                    lastUpdatedDate   : ec.user.nowTimestamp,
            ])
        }
    }

    /** The row as the runner sees it: loaded by loadAutomationSources, not hand-built. */
    private def loadReturnsSource(String automationId, String fileSide) {
        return AutomationRuntimeSupport.loadAutomationSources(ec, automationId)[fileSide]
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        def existing = ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
        if (existing != null) return

        ec.entity.makeValue(entityName)
                .setAll(fields)
                .create()
    }

    // -- Source-exclusion-filter fixtures (Task 8) --------------------------------------------------

    private List findAutomationFilterRows(String automationId, String fileSide) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomationSourceFilter")
                .condition("automationId", automationId)
                .condition("fileSide", fileSide)
                .orderBy("sequenceNum").disableAuthz().useCache(false).list() ?: []
    }

    /**
     * Saves a brand-new automation. A non-null {@code file2Filters} is submitted explicitly on FILE_2
     * (backed by a fresh, filter-less RuleSet compare scope, since explicit submission always wins
     * regardless of what the rule set has). A null argument omits excludeFilters entirely on both
     * sides AND backs the automation with a "mapping" saved run instead of a ruleset one, so the saved
     * automation genuinely has no compareScopeId — exercising the "not ruleset-backed" seeding skip.
     */
    private String saveAutomationWithSourceFilters(List<Map<String, Object>> file2Filters) {
        Map<String, Object> savedRunRef
        if (file2Filters == null) {
            savedRunRef = [savedRunId: ensureFilterTestMapping(), savedRunType: "mapping"]
        } else {
            Map<String, Object> scope = createRuleSetCompareScope()
            savedRunRef = [savedRunId: scope.ruleSetId, savedRunType: "ruleset"]
        }
        Map<String, Object> file2Source = sftpSource("FILE_2", "OMS", "OMS_TEST_SFTP")
        if (file2Filters != null) file2Source.excludeFilters = file2Filters

        Map<String, Object> result = callFacade("facade.ReconciliationFacadeServices.save#Automation", [
                automationName : "Filter Automation ${nextFilterFixtureId('AUTOFLT')}".toString(),
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_SFTP_FILES,
                savedRunId     : savedRunRef.savedRunId,
                savedRunType   : savedRunRef.savedRunType,
                scheduleExpr   : "PT1H",
                sources        : [
                        sftpSource("FILE_1", "SHOPIFY", "SHOPIFY_TEST_SFTP"),
                        file2Source,
                ],
        ])
        assertTrue((Boolean) result.ok, result.errors?.toString())
        return ((Map) result.automation).automationId as String
    }

    /** First save of a fresh automation against an existing rule set compare scope; submits no excludeFilters. */
    private String saveAutomationForCompareScope(String compareScopeId) {
        def scope = ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz().useCache(false).one()
        assertNotNull(scope, "compare scope ${compareScopeId} was not seeded".toString())
        String ruleSetId = scope.ruleSetId as String

        Map<String, Object> result = callFacade("facade.ReconciliationFacadeServices.save#Automation", [
                automationName : "Filter Automation ${nextFilterFixtureId('AUTOFLT')}".toString(),
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_SFTP_FILES,
                savedRunId     : ruleSetId,
                savedRunType   : "ruleset",
                scheduleExpr   : "PT1H",
                sources        : [
                        sftpSource("FILE_1", "SHOPIFY", "SHOPIFY_TEST_SFTP"),
                        sftpSource("FILE_2", "OMS", "OMS_TEST_SFTP"),
                ],
        ])
        assertTrue((Boolean) result.ok, result.errors?.toString())
        return ((Map) result.automation).automationId as String
    }

    /** Resaves without submitting excludeFilters on either side — "leave unchanged" semantics. */
    private void resaveAutomationWithoutFilterKeys(String automationId) {
        Map<String, Object> savedRunRef = currentAutomationSavedRun(automationId)
        Map<String, Object> result = callFacade("facade.ReconciliationFacadeServices.save#Automation", [
                automationId   : automationId,
                automationName : "Filter Automation Resave".toString(),
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_SFTP_FILES,
                savedRunId     : savedRunRef.savedRunId,
                savedRunType   : savedRunRef.savedRunType,
                scheduleExpr   : "PT1H",
                sources        : [
                        sftpSource("FILE_1", "SHOPIFY", "SHOPIFY_TEST_SFTP"),
                        sftpSource("FILE_2", "OMS", "OMS_TEST_SFTP"),
                ],
        ])
        assertTrue((Boolean) result.ok, result.errors?.toString())
    }

    /** Resaves with an explicit FILE_2 excludeFilters list (including an empty one, to clear). */
    private void resaveAutomationWithSourceFilters(String automationId, List<Map<String, Object>> file2Filters) {
        Map<String, Object> savedRunRef = currentAutomationSavedRun(automationId)
        Map<String, Object> file2Source = sftpSource("FILE_2", "OMS", "OMS_TEST_SFTP")
        file2Source.excludeFilters = file2Filters

        Map<String, Object> result = callFacade("facade.ReconciliationFacadeServices.save#Automation", [
                automationId   : automationId,
                automationName : "Filter Automation Resave".toString(),
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_SFTP_FILES,
                savedRunId     : savedRunRef.savedRunId,
                savedRunType   : savedRunRef.savedRunType,
                scheduleExpr   : "PT1H",
                sources        : [
                        sftpSource("FILE_1", "SHOPIFY", "SHOPIFY_TEST_SFTP"),
                        file2Source,
                ],
        ])
        assertTrue((Boolean) result.ok, result.errors?.toString())
    }

    private void deleteAutomation(String automationId) {
        Map<String, Object> result = callFacade("facade.ReconciliationFacadeServices.delete#Automation", [
                automationId: automationId,
        ])
        assertTrue((Boolean) result.ok, result.errors?.toString())
    }

    /** A fresh RuleSet + single RuleSetCompareScope + FILE_1/FILE_2 sources, with no filter rows. */
    private Map<String, Object> createRuleSetCompareScope() {
        String ruleSetId = nextFilterFixtureId("AFRS")
        String compareScopeId = nextFilterFixtureId("AFSC")
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([
                ruleSetId         : ruleSetId,
                ruleSetName       : "Automation filter test RuleSet ${ruleSetId}".toString(),
                version           : "1.0",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId   : TEST_USER_ID,
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareScope").setAll([
                compareScopeId: compareScopeId,
                ruleSetId     : ruleSetId,
                objectType    : "ORDER",
                description   : "Automation filter test compare scope ${compareScopeId}".toString(),
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSource").setAll([
                compareScopeId     : compareScopeId,
                fileSide           : "FILE_1",
                systemEnumId       : "SHOPIFY",
                fileTypeEnumId     : "DftJson",
                primaryIdExpression: "id",
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSource").setAll([
                compareScopeId     : compareScopeId,
                fileSide           : "FILE_2",
                systemEnumId       : "OMS",
                fileTypeEnumId     : "DftJson",
                primaryIdExpression: "id",
        ]).create()
        return [ruleSetId: ruleSetId, compareScopeId: compareScopeId]
    }

    /** Same as {@link #createRuleSetCompareScope()}, plus one exclusion-filter row on the given side. */
    private String createRuleSetCompareScopeWithFilter(String fileSide, String fieldExpression, String value) {
        return createRuleSetCompareScopeWithFilters(fileSide, [
                [sequenceNum: 1, fieldExpression: fieldExpression, filterValues: value],
        ])
    }

    /**
     * Same as {@link #createRuleSetCompareScope()}, plus one exclusion-filter row per entry in
     * {@code filters} (each a map of sequenceNum/fieldExpression/filterValues), created in the given
     * list order — callers that want to prove sequenceNum-based sorting (not creation-order) survives
     * the copy should list entries out of sequenceNum order.
     */
    private String createRuleSetCompareScopeWithFilters(String fileSide, List<Map<String, Object>> filters) {
        Map<String, Object> scope = createRuleSetCompareScope()
        filters.each { Map<String, Object> filter ->
            ec.entity.makeValue("darpan.rule.RuleSetCompareSourceFilter").setAll([
                    compareScopeId    : scope.compareScopeId,
                    fileSide          : fileSide,
                    sequenceNum       : filter.sequenceNum,
                    fieldExpression   : filter.fieldExpression,
                    operator          : "EXCLUDE_IN",
                    filterValues      : filter.filterValues,
                    companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
            ]).create()
        }
        return scope.compareScopeId as String
    }

    /** Adds one more exclusion-filter row to an existing compare scope, after any already seeded there. */
    private void addRuleSetCompareSourceFilter(String compareScopeId, String fileSide, String fieldExpression, String value) {
        List existingRows = ec.entity.find("darpan.rule.RuleSetCompareSourceFilter")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", fileSide)
                .disableAuthz().useCache(false).list() ?: []
        int nextSequenceNum = existingRows ? ((existingRows*.sequenceNum) as List<Integer>).max() + 1 : 1
        ec.entity.makeValue("darpan.rule.RuleSetCompareSourceFilter").setAll([
                compareScopeId    : compareScopeId,
                fileSide          : fileSide,
                sequenceNum       : nextSequenceNum,
                fieldExpression   : fieldExpression,
                operator          : "EXCLUDE_IN",
                filterValues      : value,
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
        ]).create()
    }

    /** A fresh RuleSet compare scope with no RuleSetCompareSourceFilter rows on either side. */
    private String createCompareScopeWithNoFilters() {
        return createRuleSetCompareScope().compareScopeId as String
    }

    /** Saves a fresh automation backed by a mapping saved run, so it genuinely has no compareScopeId. */
    private String saveAutomationWithoutCompareScope() {
        return saveAutomationWithSourceFilters(null)
    }

    /** Deletes every ReconciliationAutomationSourceFilter row for an automation, on both sides. */
    private void clearAutomationFilterRows(String automationId) {
        ec.entity.find("darpan.reconciliation.ReconciliationAutomationSourceFilter")
                .condition("automationId", automationId)
                .disableAuthz().useCache(false).deleteAll()
    }

    /**
     * Replaces an automation's filter rows on one side with a single row of the given shape,
     * simulating a snapshot frozen at an older rule-set state — used to prove the backfill never
     * overwrites an existing snapshot.
     */
    private void replaceAutomationFilterRows(String automationId, String fileSide, String fieldExpression, String value) {
        ec.entity.find("darpan.reconciliation.ReconciliationAutomationSourceFilter")
                .condition("automationId", automationId)
                .condition("fileSide", fileSide)
                .disableAuthz().useCache(false).deleteAll()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSourceFilter").setAll([
                automationId      : automationId,
                fileSide          : fileSide,
                sequenceNum       : 1,
                fieldExpression   : fieldExpression,
                operator          : "EXCLUDE_IN",
                filterValues      : value,
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId   : TEST_USER_ID,
                createdDate       : ec.user.nowTimestamp,
                lastUpdatedDate   : ec.user.nowTimestamp,
        ]).create()
    }

    /**
     * Calls the one-time backfill service and returns its result map. Task 13 fix round 1, Critical
     * 2: moved from facade.ReconciliationFacadeServices (auto-grant trap for facade.*, allow-remote)
     * to reconciliation.ReconciliationNotificationServices, matching migrate#TenantNotificationSettings's
     * authenticate="false"/no-allow-remote posture — internal only, not reachable via /rpc/json.
     */
    private Map<String, Object> runBackfill() {
        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.migrate#AutomationExcludeFilters")
                .disableAuthz()
                .call()
        assertTrue((Boolean) result.ok, result.errors?.toString())
        // Task 13 fix round 2, New Important 1 regression coverage: the <message> summary line must
        // actually reach the messages out-parameter (it previously ran AFTER the messages set already
        // captured its snapshot, so it silently never appeared). Checked here so every existing and
        // future test that calls runBackfill() covers it, not just one dedicated test.
        List<String> messages = (result.messages ?: []) as List<String>
        assertTrue(messages.any { it?.toString()?.contains("Backfilled") },
                "messages out-parameter did not contain the backfill summary: ${messages}")
        return result
    }

    /** Deletes ReconciliationAutomationSourceFilter rows for one automation/fileSide only. */
    private void clearAutomationFilterRowsForSide(String automationId, String fileSide) {
        ec.entity.find("darpan.reconciliation.ReconciliationAutomationSourceFilter")
                .condition("automationId", automationId)
                .condition("fileSide", fileSide)
                .disableAuthz().useCache(false).deleteAll()
    }

    /**
     * Same as {@link #createRuleSetCompareScopeWithFilter}, but stamps the RuleSet and the filter row
     * for an explicit tenant instead of the fixed {@code TEST_COMPANY_USER_GROUP_ID} session tenant.
     * Task 13 fix round 1, Important 5: every other fixture in this file ends up owned by the same
     * session tenant, so no combination of them can distinguish "the sweep processes every tenant"
     * from "the sweep processes only the caller's active tenant" — this fixture exists so a test can
     * build a genuinely different tenant's rule set to tell the two apart.
     */
    private Map<String, Object> createRuleSetCompareScopeWithFilterForTenant(String tenantUserGroupId, String fileSide,
            String fieldExpression, String value) {
        String ruleSetId = nextFilterFixtureId("RSTEN")
        String compareScopeId = nextFilterFixtureId("SCTEN")
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([
                ruleSetId         : ruleSetId,
                ruleSetName       : "Tenant RuleSet ${ruleSetId}".toString(),
                version           : "1.0",
                companyUserGroupId: tenantUserGroupId,
                createdByUserId   : TEST_USER_ID,
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareScope").setAll([
                compareScopeId: compareScopeId,
                ruleSetId     : ruleSetId,
                objectType    : "ORDER",
                description   : "Tenant compare scope ${compareScopeId}".toString(),
        ]).create()
        ["FILE_1", "FILE_2"].each { String side ->
            ec.entity.makeValue("darpan.rule.RuleSetCompareSource").setAll([
                    compareScopeId     : compareScopeId,
                    fileSide           : side,
                    systemEnumId       : side == "FILE_1" ? "SHOPIFY" : "OMS",
                    fileTypeEnumId     : "DftJson",
                    primaryIdExpression: "id",
            ]).create()
        }
        ec.entity.makeValue("darpan.rule.RuleSetCompareSourceFilter").setAll([
                compareScopeId    : compareScopeId,
                fileSide          : fileSide,
                sequenceNum       : 1,
                fieldExpression   : fieldExpression,
                operator          : "EXCLUDE_IN",
                filterValues      : value,
                companyUserGroupId: tenantUserGroupId,
        ]).create()
        return [ruleSetId: ruleSetId, compareScopeId: compareScopeId]
    }

    /**
     * Creates a ReconciliationAutomation row directly for an explicit tenant, bypassing
     * save#Automation — which stamps the SESSION's active tenant, not an arbitrary one. This is the
     * shape every production automation actually has: created independently of whichever tenant an
     * operator's session happens to be scoped to when the one-time backfill later runs.
     */
    private String createAutomationForTenant(String tenantUserGroupId, String compareScopeId, String ruleSetId) {
        return createAutomationForTenant(nextFilterFixtureId("AUTOTEN"), tenantUserGroupId, compareScopeId, ruleSetId, true)
    }

    /**
     * Same as the 3-arg overload, but with an explicit automationId (save#Automation and the 3-arg
     * overload both let Moqui's sequenced-id generator pick the id, which is not lexicographically
     * predictable relative to another automation's id — some tests need to control sort order
     * directly) and an option to skip creating the ReconciliationAutomationSource rows, to
     * deliberately reproduce the referential-integrity failure a source-less automation's create#
     * calls hit.
     */
    private String createAutomationForTenant(String automationId, String tenantUserGroupId, String compareScopeId,
            String ruleSetId, boolean withSourceRows) {
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([
                automationId      : automationId,
                automationName    : "Tenant automation ${automationId}".toString(),
                companyUserGroupId: tenantUserGroupId,
                createdByUserId   : TEST_USER_ID,
                inputModeEnumId   : AutomationExecutionSupport.AUTOMATION_INPUT_SFTP_FILES,
                savedRunId        : ruleSetId,
                savedRunType      : "ruleset",
                ruleSetId         : ruleSetId,
                compareScopeId    : compareScopeId,
                isActive          : "Y",
                createdDate       : ec.user.nowTimestamp,
                lastUpdatedDate   : ec.user.nowTimestamp,
        ]).create()
        if (withSourceRows) {
            // ReconciliationAutomationSourceFilter FKs to ReconciliationAutomationSource(automationId,
            // fileSide) — exactly the row every pre-existing production automation already has on both
            // sides (that is why the create-time seed can never fire for them). Without these, every
            // filter-row insert the backfill attempts for this automation fails with a referential-
            // integrity violation.
            ["FILE_1", "FILE_2"].each { String side ->
                ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSource").setAll([
                        automationId      : automationId,
                        fileSide          : side,
                        companyUserGroupId: tenantUserGroupId,
                        createdByUserId   : TEST_USER_ID,
                        sourceTypeEnumId  : AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                        systemEnumId      : side == "FILE_1" ? "SHOPIFY" : "OMS",
                        fileTypeEnumId    : "DftJson",
                        createdDate       : ec.user.nowTimestamp,
                        lastUpdatedDate   : ec.user.nowTimestamp,
                ]).create()
            }
        }
        return automationId
    }

    private String ensureFilterTestMapping() {
        def existing = ec.entity.find("darpan.mapping.ReconciliationMapping")
                .condition("reconciliationMappingId", FILTER_TEST_MAPPING_ID)
                .disableAuthz().useCache(false).one()
        if (!existing) {
            ec.entity.makeValue("darpan.mapping.ReconciliationMapping").setAll([
                    reconciliationMappingId: FILTER_TEST_MAPPING_ID,
                    mappingName            : "Automation filter test mapping",
                    companyUserGroupId     : TEST_COMPANY_USER_GROUP_ID,
                    createdByUserId        : TEST_USER_ID,
            ]).create()
        }
        return FILTER_TEST_MAPPING_ID
    }

    private Map<String, Object> currentAutomationSavedRun(String automationId) {
        def automationRecord = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", automationId)
                .disableAuthz().useCache(false).one()
        return [savedRunId: automationRecord.savedRunId, savedRunType: automationRecord.savedRunType]
    }

    private Map<String, Object> sftpSource(String fileSide, String systemEnumId, String sftpServerId) {
        return [
                fileSide          : fileSide,
                sourceTypeEnumId  : "AUT_SRC_SFTP",
                systemEnumId      : systemEnumId,
                fileTypeEnumId    : "DftCsv",
                sftpServerId      : sftpServerId,
                remotePathTemplate: "/incoming/${fileSide.toLowerCase()}".toString(),
        ]
    }

    private String nextFilterFixtureId(String prefix) {
        filterFixtureCounter++
        return "${prefix}${filterFixtureCounter}".toString()
    }

    private Map<String, Object> callFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }


    // --- verification reads a diff document that EXISTS by then (2026-08-28) -------------------
    // Every test below hands verifyMissingDiffsIfEnabled a hand-built reconcileResult carrying
    // `diffLocation` -- a key reconcile#RuleSetCompareScope does not declare as an out-parameter and
    // therefore never returns. So they prove the pass runs when given a file, and nothing about
    // whether this path ever gives it one. It did not: ensureAutomationResultArtifact, which is what
    // sets diffLocation, ran AFTER all three passes, so resolveDiffFile returned null on every
    // scheduled run and each one reported DIFF_ARTIFACT_UNREADABLE. Same hollow-fixture shape as the
    // sourceConfigId gap fixed in d40082d, one layer further out. This test drives the REAL
    // orchestration instead.

    @Test
    void aScheduledRunVerifiesAgainstTheDiffDocumentItsOwnCompareProduced() {
        seedApiAutomation(VERIFY_DIFF_AUTOMATION_ID)
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            String fileSide = source.get("fileSide")
            String location = fileSide == AutomationExecutionSupport.FILE_SIDE_1 ?
                    "component://darpan/data/test/test-orders-1.json" :
                    "component://darpan/data/test/test-orders-2.json"
            return [
                    dataAvailable : true,
                    fileLocation  : location,
                    fileName      : "${fileSide}.json".toString(),
                    fileTypeEnumId: "DftJson",
                    recordCount   : 3,
            ]
        }

        Map<String, Object> result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : VERIFY_DIFF_AUTOMATION_ID,
                scheduledFireTime: Timestamp.valueOf("2026-05-01 10:00:00"),
                sparkMaster      : "local[1]",
                sparkAppName     : "AutomationExecutionServiceSmokeTests",
        ])

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(1, result.executedCount)

        def execution = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                .condition("automationId", VERIFY_DIFF_AUTOMATION_ID)
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(execution)
        String runResultId = execution.reconciliationRunResultId as String
        assertNotNull(runResultId, "the run must have been observed for its VERIFY step to be readable")

        // A PRECONDITION, not decoration. prepareMissingDiffPass returns "nothing to verify" before
        // it ever looks for the file, so on a fixture with no missing rows the assertion below would
        // pass without the gate under test being reached at all.
        File outputFile = DataManagerSupport.resolveDataManagerFile(ec, execution.resultDataManagerPath, false)
        assertNotNull(outputFile)
        Map<String, Object> outputDocument = (Map<String, Object>) JSON_SLURPER.parseText(outputFile.getText("UTF-8"))
        Map summary = (Map) outputDocument.summary
        long missingRows = (((summary?.onlyInFile1Count ?: 0) as Number).longValue() +
                ((summary?.onlyInFile2Count ?: 0) as Number).longValue())
        assertTrue(missingRows > 0L,
                "fixture must produce missing rows or the diff-document gate is never reached; summary was ${summary}")

        List verifySteps = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runResultId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_MISSING)
                .disableAuthz()
                .useCache(false)
                .list()
        def unreadable = verifySteps.find { def step ->
            ((step.metricsJson ?: "") as String).contains(RunVerificationSupport.SKIP_NO_DIFF_FILE)
        }
        assertNull(unreadable,
                "the compare's diff document must be on disk before verification reads it; step said: " +
                        "${unreadable?.errorMessage}")
    }

    // --- verification on the SCHEDULED path (design step 4) ------------------------------------
    // Until now this path had never verified anything: STAGE_VERIFY appeared 0 times in the whole
    // automation package, and the step-3 unit tests only cover the flag GATE (off, or nothing to
    // verify). Nothing proved the pass actually executes here. These two do.

    @Test
    void verificationRunsOnTheScheduledPathAndRecordsItsOwnStage() {
        File diffFile = writeMissingDiffDocument(["9001", "9002", "9003"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        Map<String, Object> reconcileResult = [
                differenceCount: 3L, missingInFile1Count: 0L, missingInFile2Count: 3L,
                missingObjectDifferenceCount: 3L,
                file1Label: "OMS", file2Label: "SHOPIFY",
                diffLocation: diffFile.absolutePath,
        ]
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_API_ARTIFACT").disableAuthz().useCache(false).one()

        boolean ran
        try {
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "true")
            ran = AutomationExecutionSupport.verifyMissingDiffsIfEnabled(ec, automation,
                    omsReturnsSource(), omsReturnsSource(), reconcileResult, runId, [:])
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
            diffFile.delete()
        }

        assertTrue(ran, "with the flag armed, a real diff and a lookup-capable connector, the pass must execute here")

        // The stage is the operator-visible proof. A scheduled run that verifies but shows no
        // VERIFY row in its timeline is indistinguishable from one that never verified at all —
        // which is exactly the confusion this whole change exists to remove.
        def verifyStep = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_MISSING)
                .disableAuthz().useCache(false).one()
        assertNotNull(verifyStep, "the scheduled path must record its VERIFY stage like the interactive one does")
        assertNotEquals(RunObservability.STATUS_RUNNING, verifyStep.statusEnumId,
                "the step must be closed, not left RUNNING for the stuck-run reaper to find")
    }

    @Test
    void aVerificationLookupCarriesTheAutomationsOwnTenantNotTheSessions() {
        // The scheduler authenticates as anonymous _NA_, which belongs to no tenant. Every lookup
        // service in this slot declares companyUserGroupId for exactly that reason, and omitting it
        // is what broke every scheduled automation on 2026-07-31. Asserted against REAL seeded
        // connector data (parameter names come from the registry, not from a literal here).
        List<Map> dispatched = []
        Closure stubDispatcher = { String serviceName, Map serviceParams ->
            dispatched.add([service: serviceName, params: serviceParams])
            return [:]
        }

        Closure lookup = RunVerificationSupport.buildVerificationLookup(
                ec, omsReturnsSource(), OTHER_TENANT_USER_GROUP_ID, stubDispatcher)

        assertNotNull(lookup, "OMS_RETURNS declares a lookupServiceName, so a lookup must be buildable")
        lookup.call(["27151073411"])

        assertEquals(1, dispatched.size())
        Map call = dispatched.first()
        assertTrue(((String) call.service).contains("lookup#"),
                "the lookup slot may only dispatch lookup#* services: ${call.service}")
        assertEquals(OTHER_TENANT_USER_GROUP_ID, call.params.companyUserGroupId,
                "the lookup must carry the RUN OWNER's tenant, never whatever the session happened to have")
        assertEquals(["27151073411"], call.params.externalIds,
                "ids must go under the connector-declared lookupIdsParameterName, not a hardcoded orderIds")
    }

    @Test
    void aScheduledSourceWithNoIdOfItsOwnFallsBackToTheRunsResolvedDefault() {
        // The commonest real shape: nothing on the row, nothing in metadata. The extractor still
        // runs, because resolveSourceExtractorConfigDefaults resolved the tenant's single active
        // config once per execution. Verification has to consult the SAME value or it goes inert on
        // exactly the automations whose extract worked fine.
        seedReturnsAutomation("AUTO_RET_NO_ID", [:])
        def source = loadReturnsSource("AUTO_RET_NO_ID", AutomationExecutionSupport.FILE_SIDE_2)

        List<Map> dispatched = []
        Closure stubDispatcher = { String serviceName, Map serviceParams ->
            dispatched.add([service: serviceName, params: serviceParams])
            return [:]
        }

        Closure lookup = RunVerificationSupport.buildVerificationLookup(
                ec, source, TEST_COMPANY_USER_GROUP_ID, stubDispatcher,
                [omsRestSourceConfigId: "TENANT_DEFAULT_CFG"] as Map<String, Object>)

        assertNotNull(lookup, "a source with no id of its own must use the run's resolved config default")
        lookup.call(["27151073411"])
        assertEquals("TENANT_DEFAULT_CFG", dispatched.first().params.omsRestSourceConfigId,
                "the fallback must travel under the connector-declared configParameterName")
    }

    @Test
    void aSourcesOwnConfigIdBeatsTheRunsDefault() {
        seedReturnsAutomation("AUTO_RET_PRECEDENCE", [(AutomationExecutionSupport.FILE_SIDE_2): "SOURCE_OWN_CFG"])
        def source = loadReturnsSource("AUTO_RET_PRECEDENCE", AutomationExecutionSupport.FILE_SIDE_2)

        List<Map> dispatched = []
        Closure stubDispatcher = { String serviceName, Map serviceParams ->
            dispatched.add([service: serviceName, params: serviceParams])
            return [:]
        }

        RunVerificationSupport.buildVerificationLookup(ec, source, TEST_COMPANY_USER_GROUP_ID, stubDispatcher,
                [omsRestSourceConfigId: "TENANT_DEFAULT_CFG"] as Map<String, Object>).call(["1"])

        assertEquals("SOURCE_OWN_CFG", dispatched.first().params.omsRestSourceConfigId,
                "same precedence the extractor uses: what the source declares wins over the tenant default")
    }

    @Test
    void theScheduledPassRunsOnRealSourceRowsUsingTheRunsConfigDefaults() {
        // End to end on the real shape: rows loaded the way the runner loads them, no config id
        // anywhere on them, and the run's defaults supplied the way executeAutomation supplies them.
        // Before the resolver fix this returned false and opened no VERIFY step at all.
        seedReturnsAutomation("AUTO_RET_EXEC_DEFAULTS", [:])
        File diffFile = writeMissingDiffDocument(["9001", "9002"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        Map<String, Object> reconcileResult = [
                differenceCount: 2L, missingInFile1Count: 0L, missingInFile2Count: 2L,
                missingObjectDifferenceCount: 2L,
                file1Label: "SHOPIFY", file2Label: "OMS",
                diffLocation: diffFile.absolutePath,
        ]
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_RET_EXEC_DEFAULTS").disableAuthz().useCache(false).one()

        boolean ran
        try {
            ran = AutomationExecutionSupport.verifyMissingDiffsIfEnabled(ec, automation,
                    loadReturnsSource("AUTO_RET_EXEC_DEFAULTS", AutomationExecutionSupport.FILE_SIDE_1),
                    loadReturnsSource("AUTO_RET_EXEC_DEFAULTS", AutomationExecutionSupport.FILE_SIDE_2),
                    reconcileResult, runId, [:],
                    [sourceExtractorConfigDefaults: [omsRestSourceConfigId: "TENANT_DEFAULT_CFG"]] as Map<String, Object>)
        } finally {
            diffFile.delete()
        }

        assertTrue(ran, "with real automation source rows and the run's config defaults, the pass must execute")
        def verifyStep = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_MISSING)
                .disableAuthz().useCache(false).one()
        assertNotNull(verifyStep, "the scheduled path must record its VERIFY stage on the real source shape too")
    }

    @Test
    void aScheduledRunThatCannotVerifySaysSoOnItsTimeline() {
        // The 2026-08-27 production shape, exactly: real automation source rows carrying no config id
        // of their own, and no run defaults to fall back on. The pass cannot build a lookup, so it
        // does not run — and until now that produced NO VERIFY row at all, which on a run reporting
        // 403 unverified differences was indistinguishable from the kill switch being off. The run
        // must now carry the reason itself instead of costing a source-reading session to guess.
        seedReturnsAutomation("AUTO_RET_NO_CONFIG", [:])
        File diffFile = writeMissingDiffDocument(["9001", "9002"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        Map<String, Object> reconcileResult = [
                differenceCount: 2L, missingInFile1Count: 0L, missingInFile2Count: 2L,
                missingObjectDifferenceCount: 2L,
                file1Label: "SHOPIFY", file2Label: "OMS",
                diffLocation: diffFile.absolutePath,
        ]
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_RET_NO_CONFIG").disableAuthz().useCache(false).one()

        boolean ran
        try {
            ran = AutomationExecutionSupport.verifyMissingDiffsIfEnabled(ec, automation,
                    loadReturnsSource("AUTO_RET_NO_CONFIG", AutomationExecutionSupport.FILE_SIDE_1),
                    loadReturnsSource("AUTO_RET_NO_CONFIG", AutomationExecutionSupport.FILE_SIDE_2),
                    reconcileResult, runId, [:], null)
        } finally {
            diffFile.delete()
        }

        assertFalse(ran, "no config id resolves anywhere, so the pass cannot run")
        def verifyStep = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_MISSING)
                .disableAuthz().useCache(false).one()
        assertNotNull(verifyStep, "a run that could not verify must still record the stage, saying why")
        assertEquals(RunObservability.STATUS_NO_DATA, verifyStep.statusEnumId,
                "nothing failed in the run itself — the pass had nothing it could check")
        String reason = verifyStep.errorMessage as String
        assertTrue(reason?.contains("omsRestSourceConfigId"),
                "the reason must name the config parameter that would not resolve, got: ${reason}")
    }

    // --- pass parity: every verification pass the interactive path runs, the scheduled one runs too

    @Test
    void theReturnPresencePassRunsOnTheScheduledPath() {
        // Third of the three verification passes, and the one that matters most for a returns
        // automation: it carries the grace/window-edge gate and cancelled-order refund suppression.
        // Interactive-only until now, so a scheduled RS_RETURNS run published counts an interactive
        // rerun of the same window would have corrected.
        seedReturnsAutomation("AUTO_RET_PRESENCE", [(AutomationExecutionSupport.FILE_SIDE_2): "SMOKE_OMS_RETURNS_CFG"])
        File diffFile = writeMissingDiffDocument(["9001", "9002"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        Map<String, Object> reconcileResult = [
                differenceCount: 2L, missingInFile1Count: 0L, missingInFile2Count: 2L,
                missingObjectDifferenceCount: 2L,
                file1Label: "SHOPIFY", file2Label: "OMS",
                diffLocation: diffFile.absolutePath,
        ]
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_RET_PRESENCE").disableAuthz().useCache(false).one()

        boolean ran
        try {
            ran = AutomationExecutionSupport.verifyReturnPresenceIfEnabled(ec, automation,
                    loadReturnsSource("AUTO_RET_PRESENCE", AutomationExecutionSupport.FILE_SIDE_1),
                    loadReturnsSource("AUTO_RET_PRESENCE", AutomationExecutionSupport.FILE_SIDE_2),
                    [fileLocation: null], [fileLocation: null],
                    reconcileResult, runId, [:], null)
        } finally {
            diffFile.delete()
        }

        assertTrue(ran, "an OMS_RETURNS/SHOPIFY_RETURN_REFS pair must run the return-presence pass on the scheduled path")
        def verifyStep = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_RETURNS)
                .disableAuthz().useCache(false).one()
        assertNotNull(verifyStep, "the pass must record its own VERIFY step, or it is invisible on the timeline")
    }

    @Test
    void theScheduledNotifyStageIsVisibleOnTheTimeline() {
        // Both paths notify, but only the interactive one opened a NOTIFY step. A scheduled run's
        // alert was therefore invisible on the run timeline: "did it fire?" was unanswerable from
        // the UI, which is the last stage-level difference between a triggered run and a manual one.
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])

        AutomationExecutionSupport.notifyRunCompletedWithStage(ec, runId, [:], [
                reconciliationRunResultId: runId,
                runName                  : "Returns Verification Smoke",
                companyUserGroupId       : TEST_COMPANY_USER_GROUP_ID,
                differenceCount          : 2L,
                statusEnumId             : "DAR_RUN_SUCCEEDED",
        ] as Map<String, Object>)

        def notifyStep = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_NOTIFY)
                .disableAuthz().useCache(false).one()
        assertNotNull(notifyStep, "a scheduled run must record its NOTIFY stage like the interactive one does")
        assertNotEquals(RunObservability.STATUS_RUNNING, notifyStep.statusEnumId,
                "the step must be closed, not left RUNNING for the stuck-run reaper to find")
    }

    @Test
    void theExchangePairPassRunsOnTheScheduledPath() {
        // Second of the three passes. The OMS orders connector declares pairLookupServiceName and the
        // Shopify orders connector declares exchangeSweepServiceName, so an orders automation is
        // exactly the pair this pass applies to -- and it never ran on a scheduled one.
        seedExchangeAutomation("AUTO_EXCHANGE_PAIR",
                [(AutomationExecutionSupport.FILE_SIDE_1): "SMOKE_SHOPIFY_CFG",
                 (AutomationExecutionSupport.FILE_SIDE_2): "SMOKE_OMS_CFG"])
        File diffFile = writeMissingDiffDocument(["9001"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        Map<String, Object> reconcileResult = [
                differenceCount: 1L, missingInFile1Count: 0L, missingInFile2Count: 1L,
                file1Label: "SHOPIFY", file2Label: "OMS", diffLocation: diffFile.absolutePath,
        ]
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_EXCHANGE_PAIR").disableAuthz().useCache(false).one()
        long now = 1756000000000L

        boolean ran
        try {
            ran = AutomationExecutionSupport.verifyExchangePairsIfEnabled(ec, automation,
                    loadReturnsSource("AUTO_EXCHANGE_PAIR", AutomationExecutionSupport.FILE_SIDE_1),
                    loadReturnsSource("AUTO_EXCHANGE_PAIR", AutomationExecutionSupport.FILE_SIDE_2),
                    [fileLocation: null], [fileLocation: null], reconcileResult, runId, [:],
                    [childWindowStartDate: new Timestamp(now - 86_400_000L),
                     childWindowEndDate  : new Timestamp(now)] as Map<String, Object>, null)
        } finally {
            diffFile.delete()
        }

        assertTrue(ran, "an OMS/SHOPIFY orders pair with a window must run the exchange pair pass here too")
        assertNotNull(ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_EXCHANGE)
                .disableAuthz().useCache(false).one(),
                "the exchange pass must record its own VERIFY step")
    }

    @Test
    void theExchangePairPassIsSkippedWithoutAWindow() {
        // Presence semantics need a window: exchanges are enumerated from Shopify by return date.
        // A windowless run must skip rather than sweep an unbounded range.
        seedExchangeAutomation("AUTO_EXCHANGE_NOWINDOW",
                [(AutomationExecutionSupport.FILE_SIDE_1): "SMOKE_SHOPIFY_CFG",
                 (AutomationExecutionSupport.FILE_SIDE_2): "SMOKE_OMS_CFG"])
        File diffFile = writeMissingDiffDocument(["9001"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_EXCHANGE_NOWINDOW").disableAuthz().useCache(false).one()

        boolean ran
        try {
            ran = AutomationExecutionSupport.verifyExchangePairsIfEnabled(ec, automation,
                    loadReturnsSource("AUTO_EXCHANGE_NOWINDOW", AutomationExecutionSupport.FILE_SIDE_1),
                    loadReturnsSource("AUTO_EXCHANGE_NOWINDOW", AutomationExecutionSupport.FILE_SIDE_2),
                    [fileLocation: null], [fileLocation: null],
                    [differenceCount: 1L, file1Label: "SHOPIFY", file2Label: "OMS",
                     diffLocation: diffFile.absolutePath] as Map<String, Object>,
                    runId, [:], null, null)
        } finally {
            diffFile.delete()
        }

        assertFalse(ran, "without a window the exchange pass must not run")
        assertNull(ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_EXCHANGE)
                .disableAuthz().useCache(false).one(),
                "a pass that did not apply must leave no VERIFY step behind")
    }

    @Test
    void theReturnPresencePassIsSkippedWhenTheRunIsNotAReturnsPair() {
        // AUTO_API_ARTIFACT is a SHOPIFY/OMS orders pair. Neither connector is a returns connector,
        // so the pass must not run -- and must not leave a VERIFY step claiming it did.
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_API_ARTIFACT").disableAuthz().useCache(false).one()
        Map<String, Object> sources = AutomationRuntimeSupport.loadAutomationSources(ec, "AUTO_API_ARTIFACT")

        boolean ran = AutomationExecutionSupport.verifyReturnPresenceIfEnabled(ec, automation,
                sources[AutomationExecutionSupport.FILE_SIDE_1], sources[AutomationExecutionSupport.FILE_SIDE_2],
                [fileLocation: null], [fileLocation: null],
                [differenceCount: 1L, file1Label: "SHOPIFY", file2Label: "OMS"] as Map<String, Object>,
                runId, [:], null)

        assertFalse(ran, "an orders pair has no returns connector on either side, so the pass must not run")
        assertNull(ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_RETURNS)
                .disableAuthz().useCache(false).one(),
                "a pass that did not apply must leave no VERIFY step behind")
    }

    // --- the REAL scheduled source shape, not a saved run's (2026-08-27) -------------------------
    // omsReturnsSource() below is a hand-built Map carrying a `sourceConfigId` KEY. No automation
    // source has ever had one: ReconciliationAutomationSource declares no such column, and
    // resolveSourceExtractorMetadata says so outright -- the API connectors "store the config id in
    // safeMetadataJson.parameters, never as a column on the source row". So that fixture proved the
    // pass works on a shape the scheduled path never produces, while on the real one the lookup came
    // back null and verification silently skipped every OMS/Shopify automation it was built for.
    // These tests load rows through loadAutomationSources, the same call the runner makes.

    @Test
    void aRealScheduledSourceRowResolvesTheConfigIdFromItsOwnMetadata() {
        seedReturnsAutomation("AUTO_RET_METADATA", [(AutomationExecutionSupport.FILE_SIDE_2): "SMOKE_OMS_RETURNS_CFG"])
        def source = loadReturnsSource("AUTO_RET_METADATA", AutomationExecutionSupport.FILE_SIDE_2)

        List<Map> dispatched = []
        Closure stubDispatcher = { String serviceName, Map serviceParams ->
            dispatched.add([service: serviceName, params: serviceParams])
            return [:]
        }

        Closure lookup = RunVerificationSupport.buildVerificationLookup(
                ec, source, TEST_COMPANY_USER_GROUP_ID, stubDispatcher)

        assertNotNull(lookup, "a real ReconciliationAutomationSource row must resolve its config id, " +
                "or verification silently does nothing on every scheduled OMS/Shopify run")
        lookup.call(["27151073411"])
        assertEquals("SMOKE_OMS_RETURNS_CFG", dispatched.first().params.omsRestSourceConfigId,
                "the id must travel under the connector-declared configParameterName")
    }

    /** A source row shaped like the automation's own, pointing at the seeded OMS_RETURNS connector. */
    private static Map<String, Object> omsReturnsSource() {
        return [systemEnumId    : "OMS_RETURNS",
                sourceConfigType: "HOTWAX_OMS_REST_RETURNS",
                sourceConfigId  : "SMOKE_OMS_RETURNS_CFG",
                fileSide        : AutomationExecutionSupport.FILE_SIDE_2] as Map<String, Object>
    }

    /** Mirrors ReconciliationServices.writeDiffDatasetOutput's line-oriented writer exactly. */
    private File writeMissingDiffDocument(List<String> ids) {
        File file = File.createTempFile("automation-verify-diff-", ".json")
        file.withWriter("UTF-8") { writer ->
            writer << "{\n"
            writer << "\"metadata\":" + JsonOutput.toJson([file1Label: "OMS", file2Label: "SHOPIFY",
                    reconciliation: "RULESET"]) + ",\n"
            writer << "\"summary\":" + JsonOutput.toJson([totalDifferences: ids.size(),
                    onlyInFile1Count: ids.size(), onlyInFile2Count: 0]) + ",\n"
            writer << "\"validationErrors\":[],\n"
            writer << "\"processingWarnings\":[],\n"
            writer << "\"differences\":["
            boolean first = true
            ids.each { String id ->
                if (!first) writer << ","
                writer << "\n" << JsonOutput.toJson([diffType: "missing_in_SHOPIFY", compareScopeId: "SCOPE_1",
                        objectType: "RETURNS", primaryId: id, presentIn: "OMS", missingIn: "SHOPIFY",
                        data: JsonOutput.toJson([returnId: id]), message: "Present in OMS, missing in SHOPIFY"])
                first = false
            }
            writer << "]\n}"
        }
        return file
    }
}
