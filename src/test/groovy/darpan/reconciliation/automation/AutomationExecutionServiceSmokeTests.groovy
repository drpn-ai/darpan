package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
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
import static org.junit.jupiter.api.Assertions.assertNotNull
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

    private ExecutionContext ec
    private int filterFixtureCounter = 0

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "api-automation-execution-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
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

    private void seedApiAutomation() {
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomation", [automationId: "AUTO_API_ARTIFACT"], [
                automationId            : "AUTO_API_ARTIFACT",
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
                automationId: "AUTO_API_ARTIFACT",
                fileSide    : AutomationExecutionSupport.FILE_SIDE_1,
        ], [
                automationId         : "AUTO_API_ARTIFACT",
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
                automationId: "AUTO_API_ARTIFACT",
                fileSide    : AutomationExecutionSupport.FILE_SIDE_2,
        ], [
                automationId         : "AUTO_API_ARTIFACT",
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
        String automationId = nextFilterFixtureId("AUTOTEN")
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
}
