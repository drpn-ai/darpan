package darpan.reconciliation.flowchart

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull

/** DAR-UI-048. An automation whose saved-run type is "reconciliation" runs the flowchart walker. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunFlowchartAutomationSmokeTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "run-flowchart-automation-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void tenant() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, "KREWE")
        ec.message.clearErrors()
    }

    @Test
    void aReconciliationAutomationWalksTheChart() {
        Closure<String> ruleSet = { String label ->
            ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                    runName: "${label} ${UUID.randomUUID()}".toString(),
                    file1SystemEnumId: "OMS", file1FileTypeEnumId: "DftCsv", file1PrimaryIdExpression: "order_id",
                    file2SystemEnumId: "SHOPIFY", file2FileTypeEnumId: "DftCsv", file2PrimaryIdExpression: "order_id",
                    rules: []]).disableAuthz().call().savedRun.savedRunId as String
        }
        String recId = ec.service.sync().name("facade.ReconciliationFacadeServices.save#Reconciliation")
                .parameters([reconciliationName: "Auto ${UUID.randomUUID()}".toString()]).disableAuthz().call().reconciliation.reconciliationId
        String qA = ec.service.sync().name("facade.ReconciliationFacadeServices.save#ReconciliationQuestion")
                .parameters([reconciliationId: recId, ruleSetId: ruleSet("A")]).disableAuthz().call().question.reconciliationRunId
        ec.service.sync().name("facade.ReconciliationFacadeServices.save#ReconciliationQuestion")
                .parameters([reconciliationId: recId, ruleSetId: ruleSet("B"), parentReconciliationRunId: qA, parentBranch: "YES"]).disableAuthz().call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        String automationId = "FC_AUTO_${UUID.randomUUID().toString().substring(0, 8)}"
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([
                automationId: automationId, automationName: "Flowchart automation", companyUserGroupId: "KREWE",
                inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: recId, savedRunType: "reconciliation",
                relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY", isActive: "Y"]).create()
        ec.artifactExecution.enableAuthz()

        AutomationExecutionSupport.executeAutomation(ec, [automationId: automationId,
                scheduledFireTime: Timestamp.valueOf("2026-08-15 01:00:00")])
        ec.message.clearErrors()

        def execution = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                .condition("automationId", automationId).disableAuthz().useCache(false).list().first()
        assertEquals("AUT_STAT_FAILED", execution.statusEnumId)
        assertNotNull(execution.reconciliationRunResultId)
        def first = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", execution.reconciliationRunResultId).disableAuthz().useCache(false).one()
        List rows = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationExecutionId", first.reconciliationExecutionId).disableAuthz().useCache(false).list()
        assertEquals(["AUT_STAT_FAILED", "AUT_STAT_NOT_RUN"] as Set, rows*.statusEnumId as Set)
    }
}
