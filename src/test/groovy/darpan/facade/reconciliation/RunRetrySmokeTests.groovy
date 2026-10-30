package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-163. Retry a failed run from the step it failed at. Side 1 reads OMS over an API whose base URL does not
 * resolve, so a retry that read it again would fail: the run only succeeds if side 1 is handed back as it landed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunRetrySmokeTests {

    private static final String KREWE = ReconciliationSmokeTestSupport.TEST_COMPANY_USER_GROUP_ID
    private static final String OTHER_TENANT = "RETRY_OTHER_TENANT"
    private ExecutionContext ec
    private String savedRunId
    private String compareScopeId
    private int runSeq = 0

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "run-retry")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/MappingSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.upsertEntity(ec, "moqui.security.UserGroup", [userGroupId: OTHER_TENANT], [
                userGroupId: OTHER_TENANT, description: "Another tenant", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        ReconciliationSmokeTestSupport.upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "AUT_SRC_API"], [
                enumId: "AUT_SRC_API", enumTypeId: "AutomationSourceType", enumCode: "API", description: "API source", sequenceNum: 1])
        ReconciliationSmokeTestSupport.upsertEntity(ec, "darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: "RETRY_OMS"], [
                omsRestSourceConfigId: "RETRY_OMS", description: "Unreachable OMS", companyUserGroupId: KREWE,
                baseUrl: "https://oms.example.invalid", ordersPath: "/rest/s1/oms/orders", authType: "NONE",
                connectTimeoutSeconds: 1L, readTimeoutSeconds: 1L, isActive: "Y", canReadOrders: "Y"])

        Map created = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                runName                   : "Retry ${UUID.randomUUID()}".toString(),
                file1SystemEnumId         : "OMS",
                file1SourceTypeEnumId     : "AUT_SRC_API",
                file1SystemMessageRemoteId: "HOTWAX_ORDERS_API",
                file1SourceConfigId       : "RETRY_OMS",
                file1SourceConfigType     : "HOTWAX_OMS_REST",
                file1PrimaryIdExpression  : "\$.records[*].orderId",
                file2SystemEnumId         : "SHOPIFY",
                file2FileTypeEnumId       : "DftCsv",
                file2PrimaryIdExpression  : "order_id",
                rules                     : [],
        ]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        savedRunId = created.savedRun.savedRunId as String
        compareScopeId = created.savedRun.compareScopeId as String
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    /** A failed run as the runner leaves one: side 1 read and stamped, side 2 never finished. */
    private String failedRun(Map overrides = [:], boolean side1Landed = true) {
        String runId = "RETRY_SRC_${++runSeq}".toString()
        String path = overrides.containsKey("file1DataManagerPath") ? overrides.file1DataManagerPath as String :
                "reconciliation-runs/${savedRunId}/20261030-0538530${runSeq}0/oms-orders-api.json".toString()
        File onDisk = DataManagerSupport.resolveDataManagerFile(ec, path, true)
        if (onDisk != null) {
            onDisk.text = JsonOutput.toJson([metadata: [sourceType: "OMS"], records: [[orderId: "A"], [orderId: "B"]]])
        }
        Timestamp started = new Timestamp(System.currentTimeMillis() + 1000L)
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationRunResult", [
                    reconciliationRunResultId: runId, savedRunId: savedRunId, savedRunType: "ruleset", ruleSetId: savedRunId,
                    compareScopeId: compareScopeId, companyUserGroupId: KREWE, statusEnumId: RunObservability.STATUS_FAILED,
                    startedDate: started, createdDate: started,
                    windowStartDate: Timestamp.valueOf("2026-10-01 04:00:00"), windowEndDate: Timestamp.valueOf("2026-10-30 04:00:00"),
                    windowTimeZone: "America/New_York", file1Name: "oms-orders-api.json", file1DataManagerPath: path] + overrides)
            if (side1Landed) {
                ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationRunStep", [
                        reconciliationRunStepId: "${runId}_E1".toString(), reconciliationRunResultId: runId, companyUserGroupId: KREWE,
                        stageCode: RunObservability.STAGE_EXTRACT_FILE1, stageSequence: 2, statusEnumId: RunObservability.STATUS_SUCCESS,
                        startedDate: started, completedDate: started, recordCount: 2])
            }
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationRunStep", [
                    reconciliationRunStepId: "${runId}_E2".toString(), reconciliationRunResultId: runId, companyUserGroupId: KREWE,
                    stageCode: RunObservability.STAGE_EXTRACT_FILE2, stageSequence: 3, statusEnumId: RunObservability.STATUS_FAILED,
                    startedDate: started, completedDate: started, errorMessage: "Java heap space"])
        } finally {
            if (!was) ec.artifactExecution.enableAuthz()
        }
        return runId
    }

    private Map plan(String runId) {
        return RunRetrySupport.plan(ec, KREWE, runId, savedRunId)
    }

    @Test
    void aRetryReusesTheSideThatLandedAndReadsOnlyTheRest() {
        String failed = failedRun()
        Map out = ec.service.sync().name("facade.ReconciliationFacadeServices.run#SavedRunDiff").parameters([
                savedRunId          : savedRunId,
                retryFromRunResultId: failed,
                file2Name           : "shopify.csv",
                file2Text           : "order_id\nA\nC\n",
        ]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map output = (Map) out.runResult.generatedOutput
        assertEquals(2L, output.totalDifferences as long, "B is only in HotWax, C only in Shopify")

        String retryId = out.runResult.reconciliationRunResultId as String
        def retry = ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("reconciliationRunResultId", retryId)
                .disableAuthz().useCache(false).one()
        assertEquals(failed, retry.parentRunResultId, "the retry is a new run linked to the failed one")
        assertEquals(Timestamp.valueOf("2026-10-01 04:00:00"), retry.windowStartDate, "over the failed run's own window")
        def source = ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("reconciliationRunResultId", failed)
                .disableAuthz().useCache(false).one()
        assertEquals(source.file1DataManagerPath, retry.file1DataManagerPath, "side 1 is the file the failed run read")
        def step1 = ec.entity.find("darpan.reconciliation.ReconciliationRunStep").condition("reconciliationRunResultId", retryId)
                .condition("stageCode", RunObservability.STAGE_EXTRACT_FILE1).disableAuthz().useCache(false).one()
        assertEquals(RunObservability.STATUS_SUCCESS, step1.statusEnumId)
        assertEquals(2, step1.recordCount as int)
    }

    @Test
    void onlyAFailedRunOfThisRuleSetIsRetried() {
        String succeeded = failedRun([statusEnumId: RunObservability.STATUS_SUCCESS])
        assertTrue((plan(succeeded).error as String).contains("Only a failed run can be retried; run ${succeeded} is SUCCESS"), plan(succeeded).error as String)
        assertTrue((RunRetrySupport.plan(ec, KREWE, failedRun(), "SOME_OTHER_RUN").error as String).contains("is not a run of SOME_OTHER_RUN"))
        assertTrue((plan("NO_SUCH_RUN").error as String).contains("was not found"))
    }

    @Test
    void anotherTenantsRunIsNotFound() {
        String theirs = failedRun([companyUserGroupId: OTHER_TENANT])
        assertEquals("Run ${theirs} was not found.".toString(), plan(theirs).error)
    }

    @Test
    void aRunWithNothingLandedIsRefusedAndSoIsAPathOutsideTheDataManager() {
        assertTrue((plan(failedRun([:], false)).error as String).contains("did not finish reading either side"))
        String escaping = failedRun([file1DataManagerPath: "../../../etc/hosts"])
        assertTrue((plan(escaping).error as String).contains("did not finish reading either side"),
                "a stamped path that leaves the data-manager root is never read")
    }

    @Test
    void aRuleSetEditedAfterTheRunStartedIsRefused() {
        String failed = failedRun([startedDate: new Timestamp(System.currentTimeMillis())])
        Map ready = plan(failed)
        assertNull(ready.error, ready.error as String)
        assertEquals(["FILE_1"], (ready.sides as Map).keySet() as List)

        Thread.sleep(20L)
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            // Saved after the failed run started, as a source edited while it ran would be. Stamped "now", never in the
            // future, so the other cases' later runs still start after it.
            def source = ec.entity.find("darpan.rule.RuleSetCompareSource").condition([compareScopeId: compareScopeId, fileSide: "FILE_2"])
                    .useCache(false).one()
            source.set("lastUpdatedStamp", new Timestamp(System.currentTimeMillis()))
            source.update()
        } finally {
            if (!was) ec.artifactExecution.enableAuthz()
        }
        assertTrue((plan(failed).error as String).contains("was edited after run ${failed} started"), plan(failed).error as String)
    }
}
