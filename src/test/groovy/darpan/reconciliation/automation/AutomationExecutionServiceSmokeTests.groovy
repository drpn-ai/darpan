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

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "api-automation-execution-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
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
}
