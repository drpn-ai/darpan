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
    // A second tenant, deliberately never made active for this session.
    // GORJANA is already seeded as a real moqui.security.UserGroup by
    // ReconciliationSmokeTestSupport.seedSftpServerFixtures (called from setup() below) — reused
    // here rather than inventing a new tenant id, since companyUserGroupId is FK-checked against
    // UserGroup and an unseeded id fails the entity create outright.
    private static final String OTHER_TENANT_USER_GROUP_ID = "GORJANA"
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

    /**
     * DAR-BE-092 end to end through the real executor and Spark compare: an automation that runs an audit's check
     * reads the audit's window (the month to date) instead of its own previous-day window, and after the run writes
     * the check's health and its findings. Its own automation id, so the AUTO_API_ARTIFACT tests keep their windows.
     */
    @Test
    void anAuditAutomationReadsTheMonthToDateAndWritesTheLedger() {
        String automationId = "AUTO_API_AUDIT"
        seedApiAutomation(automationId)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
        upsertEntityValue("darpan.audit.Audit", [auditId: "AUTO_AUDIT"], [auditId: "AUTO_AUDIT",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, auditCode: "month-close", auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        upsertEntityValue("darpan.audit.AuditStatement", [statementId: "AUTO_AUDIT_R4"], [statementId: "AUTO_AUDIT_R4",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, auditId: "AUTO_AUDIT", statementCode: "R4",
                categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", statementText: "Every Shopify order reaches the OMS."])
        upsertEntityValue("darpan.audit.AuditCheck", [checkId: "AUTO_AUDIT_CHECK"], [checkId: "AUTO_AUDIT_CHECK",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, checkCode: "orders-shopify-vs-oms", label: "Shopify orders against the OMS",
                automationId: automationId, file1RecordType: "order", file2RecordType: "order"])
        upsertEntityValue("darpan.audit.AuditStatementCheck", [checkId: "AUTO_AUDIT_CHECK", statementId: "AUTO_AUDIT_R4"], [
                checkId: "AUTO_AUDIT_CHECK", statementId: "AUTO_AUDIT_R4", companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                diffTypes: "MISSING_IN_FILE_2"])
        List<Map> windowsSeen = []
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> window, Map<String, Object> ignoredParams ->
            windowsSeen << window
            String fileSide = source.get("fileSide")
            return [dataAvailable : true,
                    fileLocation  : fileSide == AutomationExecutionSupport.FILE_SIDE_1 ?
                            "component://darpan/data/test/test-orders-1.json" : "component://darpan/data/test/test-orders-2.json",
                    fileName      : "${fileSide}.json".toString(),
                    fileTypeEnumId: "DftJson",
                    recordCount   : 3]
        }

        Timestamp fire = Timestamp.from(java.time.Instant.parse("2026-05-14T10:00:00Z"))
        Map<String, Object> result = AutomationExecutionSupport.executeAutomation(ec, [automationId: automationId,
                scheduledFireTime: fire, sparkMaster: "local[1]", sparkAppName: "AutomationExecutionServiceSmokeTests"])

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(1, result.executedCount)
        assertEquals([java.time.Instant.parse("2026-05-01T00:00:00Z"), java.time.Instant.parse("2026-05-15T00:00:00Z")],
                [(windowsSeen[0].childWindowStartDate as Timestamp).toInstant(), (windowsSeen[0].childWindowEndDate as Timestamp).toInstant()],
                "the audit's month to date, not the automation's previous day")

        def run = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", "AUTO_AUDIT_CHECK").disableAuthz().useCache(false).one()
        assertEquals(["AUDCS_RAN", "2026-05", java.sql.Date.valueOf("2026-05-14")], [run.statusEnumId, run.period, run.reportDate])
        List reads = ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", run.checkRunId).orderBy("readSeqId")
                .disableAuthz().useCache(false).list()
        assertEquals([["SHOPIFY", "AUDSR_COMPLETE", 3L], ["OMS", "AUDSR_COMPLETE", 3L]],
                reads.collect { [it.systemEnumId, it.statusEnumId, it.recordCount as Long] })
        List findings = ec.entity.find("darpan.audit.AuditFinding").condition("checkId", "AUTO_AUDIT_CHECK").disableAuthz().useCache(false).list()
        assertEquals(2, findings.size(), "the two orders missing in the OMS break R4")
        assertTrue(findings*.recordKey.contains("6470622019715"))
        assertEquals(["AUTO_AUDIT_R4"] as Set, findings*.statementId as Set)
        Map one = findings.find { it.recordKey == "6470622019715" }.getMap()
        assertEquals("6470622019715: Present in Shopify, missing in HotWax", one.headline, "the document's own message")
        List recs = ec.entity.find("darpan.audit.AuditFindingRecord").condition("findingId", one.findingId).disableAuthz().useCache(false).list()
        assertEquals([["SHOPIFY", "order", "6470622019715"]], recs.collect { [it.systemEnumId, it.recordType, it.recordId] })
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

    // DAR-OPS-029 F-E2: two executions that reach the same queued (PENDING) window at once: the first claims it under a row
    // lock while its transaction is still open, the second waits on that lock, reads RUNNING and skips. Exactly one re-drives it.
    @Test
    void twoExecutionsReachingOneQueuedWindowRunItOnce() {
        seedApiAutomation()
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", "AUTO_API_ARTIFACT")
                .disableAuthz().useCache(false).one()
        Timestamp fire = Timestamp.valueOf("2026-03-02 06:00:00")
        Map window = [windowStartDate: Timestamp.valueOf("2026-03-01 00:00:00"), windowEndDate: Timestamp.valueOf("2026-03-02 00:00:00"),
                      childWindowStartDate: Timestamp.valueOf("2026-03-01 00:00:00"), childWindowEndDate: Timestamp.valueOf("2026-03-02 00:00:00")]
        def queued = ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationExecution").setAll([automationId: "AUTO_API_ARTIFACT",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, statusEnumId: AutomationExecutionSupport.STATUS_PENDING, scheduledDate: fire,
                windowStartDate: window.windowStartDate, windowEndDate: window.windowEndDate, childWindowStartDate: window.childWindowStartDate,
                childWindowEndDate: window.childWindowEndDate, retryCount: 1, createdDate: ec.user.nowTimestamp, lastUpdatedDate: ec.user.nowTimestamp])
        queued.setSequencedIdPrimary()
        ec.artifactExecution.disableAuthz()
        try { queued.create() } finally { ec.artifactExecution.enableAuthz() }
        java.util.concurrent.CountDownLatch firstClaimed = new java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1)
        List<Boolean> reused = Collections.synchronizedList([])
        List<Throwable> failures = Collections.synchronizedList([])
        Closure caller = { boolean holdOpen ->
            ExecutionContext tec = ec.factory.getExecutionContext()
            try {
                tec.artifactExecution.disableAuthz()
                boolean began = tec.transaction.begin(60)
                try {
                    reused << (AutomationExecutionSupport.findOrCreateExecution(tec, automation, fire, window, 1).reused as boolean)
                    if (holdOpen) {
                        firstClaimed.countDown()
                        releaseFirst.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } finally {
                    tec.transaction.commit(began)
                }
            } catch (Throwable t) {
                failures << t
            } finally {
                tec.destroy()
            }
        }
        Thread first = Thread.start { caller.call(true) }
        assertTrue(firstClaimed.await(30, java.util.concurrent.TimeUnit.SECONDS), "the first claim must land")
        Thread second = Thread.start { caller.call(false) }
        second.join(500L)
        releaseFirst.countDown()
        first.join(30_000L)
        second.join(30_000L)

        assertTrue(failures.isEmpty(), "no claim may throw: ${failures*.toString()}")
        assertEquals(2, reused.size(), "both callers answered: ${reused}")
        assertEquals(1, reused.count { it }, "exactly one execution re-drives the queued window: ${reused}")
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
                relativeWindowTypeEnumId: AutomationWindows.WINDOW_PREVIOUS_DAY,
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
                 (AutomationExecutionSupport.FILE_SIDE_2): ["OMS_RETURNS_GQL", "omsRestSourceConfigId"]],
                configIdsBySide)
    }

    /** The same fixture for the orders connectors, which are the pair the exchange passes key on. */
    private void seedExchangeAutomation(String automationId, Map<String, String> configIdsBySide) {
        seedSourcePair(automationId,
                [(AutomationExecutionSupport.FILE_SIDE_1): ["SHOPIFY", "shopifyAuthConfigId"],
                 (AutomationExecutionSupport.FILE_SIDE_2): ["OMS_GQL", "omsRestSourceConfigId"]],
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
                relativeWindowTypeEnumId: AutomationWindows.WINDOW_PREVIOUS_DAY,
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
    void theScheduledPassLooksUpOnlyWithTheOtherSidesRecordCount() {
        // DAR-BE-320: a side is looked up only when its missing rows are <= 5% of the records checked
        // against it, and the scheduled path is what hands the pass those counts. 2 missing in OMS
        // (FILE_2) checked against 1,000 Shopify records (FILE_1) is 0.2%: both ids are looked up.
        seedReturnsAutomation("AUTO_RET_SHARE_GATE", [:])
        File diffFile = missingInOmsDiffDocument(["9001", "9002"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        Map<String, Object> reconcileResult = [
                differenceCount: 2L, missingInFile1Count: 0L, missingInFile2Count: 2L,
                missingObjectDifferenceCount: 2L,
                file1Label: "SHOPIFY", file2Label: "OMS",
                diffLocation: diffFile.absolutePath,
        ]
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_RET_SHARE_GATE").disableAuthz().useCache(false).one()
        try {
            AutomationExecutionSupport.verifyMissingDiffsIfEnabled(ec, automation,
                    loadReturnsSource("AUTO_RET_SHARE_GATE", AutomationExecutionSupport.FILE_SIDE_1),
                    loadReturnsSource("AUTO_RET_SHARE_GATE", AutomationExecutionSupport.FILE_SIDE_2),
                    reconcileResult, runId, [:],
                    [sourceExtractorConfigDefaults: [omsRestSourceConfigId: "TENANT_DEFAULT_CFG"]] as Map<String, Object>,
                    [recordCount: 1000] as Map, [recordCount: 998] as Map)
        } finally {
            diffFile.delete()
        }
        def verifyStep = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_MISSING)
                .disableAuthz().useCache(false).one()
        assertNotNull(verifyStep)
        assertEquals(2L, ((verifyStep.recordCount ?: 0) as Number).longValue(),
                "both missing ids were sent to the lookup: ${verifyStep.metricsJson} ${verifyStep.errorMessage} ${reconcileResult.processingWarnings}")
    }

    @Test
    void theScheduledPassLooksNothingUpWithoutRecordCounts() {
        // Fail closed: no counts, no share, no lookup (the step still records that the pass ran).
        seedReturnsAutomation("AUTO_RET_NO_COUNTS", [:])
        File diffFile = missingInOmsDiffDocument(["9001", "9002"])
        String runId = RunObservability.beginRun(ec, [
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID, savedRunId: "DARPAN_TEST_COMPARE_RS"])
        Map<String, Object> reconcileResult = [
                differenceCount: 2L, missingInFile1Count: 0L, missingInFile2Count: 2L,
                missingObjectDifferenceCount: 2L,
                file1Label: "SHOPIFY", file2Label: "OMS",
                diffLocation: diffFile.absolutePath,
        ]
        def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", "AUTO_RET_NO_COUNTS").disableAuthz().useCache(false).one()
        try {
            AutomationExecutionSupport.verifyMissingDiffsIfEnabled(ec, automation,
                    loadReturnsSource("AUTO_RET_NO_COUNTS", AutomationExecutionSupport.FILE_SIDE_1),
                    loadReturnsSource("AUTO_RET_NO_COUNTS", AutomationExecutionSupport.FILE_SIDE_2),
                    reconcileResult, runId, [:],
                    [sourceExtractorConfigDefaults: [omsRestSourceConfigId: "TENANT_DEFAULT_CFG"]] as Map<String, Object>)
        } finally {
            diffFile.delete()
        }
        def verifyStep = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .condition("stageCode", RunObservability.STAGE_VERIFY_MISSING)
                .disableAuthz().useCache(false).one()
        assertNotNull(verifyStep)
        assertEquals(0L, ((verifyStep.recordCount ?: 0) as Number).longValue(), "nothing was looked up")
        assertTrue(((reconcileResult.processingWarnings ?: []) as List).any { it.toString().contains("how many records") },
                "the run says why nothing was looked up: ${reconcileResult.processingWarnings}")
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
        return [systemEnumId    : "OMS_RETURNS_GQL",
                sourceConfigType: "HOTWAX_OMS_GQL_RETURNS",
                sourceConfigId  : "SMOKE_OMS_RETURNS_CFG",
                fileSide        : AutomationExecutionSupport.FILE_SIDE_2] as Map<String, Object>
    }

    /** Mirrors ReconciliationServices.writeDiffDatasetOutput's line-oriented writer exactly. */
    /** writeMissingDiffDocument with the sides swapped: rows missing in OMS (FILE_2), the side these runs look up. */
    private File missingInOmsDiffDocument(List<String> ids) {
        File file = writeMissingDiffDocument(ids)
        file.setText(file.getText("UTF-8").replace("OMS", "\u0000").replace("SHOPIFY", "OMS").replace("\u0000", "SHOPIFY"), "UTF-8")
        return file
    }

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
