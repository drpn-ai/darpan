package darpan.reconciliation.flowchart

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/** DAR-UI-048 plan 3. A run's past executions, and the NetSuite checks a tenant already has turned into runs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunFlowchartImportSmokeTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "run-flowchart-import-smoke")
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

    Map call(String name, Map params) {
        Map out = ec.service.sync().name(name).parameters(params).disableAuthz().call() ?: [:]
        return out
    }

    String csvRuleSet(String label) {
        Map created = call("facade.ReconciliationFacadeServices.create#RuleSetRun", [
                runName: "${label} ${UUID.randomUUID()}".toString(),
                file1SystemEnumId: "OMS", file1FileTypeEnumId: "DftCsv", file1PrimaryIdExpression: "order_id",
                file2SystemEnumId: "SHOPIFY", file2FileTypeEnumId: "DftCsv", file2PrimaryIdExpression: "order_id",
                rules: []])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return created.savedRun.savedRunId as String
    }

    static final Closure SAME_FILES = { Map q ->
        [file1Name: "f1.csv", file1Text: "order_id\nA100\n", file2Name: "f2.csv", file2Text: "order_id\nA100\n", hasHeader: true] }

    @Test
    void executionsAreGroupedNewestFirstWithARolledUpStatus() {
        String rsA = csvRuleSet("Exec A"), rsB = csvRuleSet("Exec B")
        String recId = call("facade.ReconciliationFacadeServices.save#Reconciliation",
                [reconciliationName: "Exec ${UUID.randomUUID()}".toString()]).reconciliation.reconciliationId
        String qA = call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsA]).question.reconciliationRunId
        call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsB, parentReconciliationRunId: qA, parentBranch: "YES"])
        Map first = RunFlowchartSupport.runReconciliation(ec, [reconciliationId: recId, questionCallExtras: SAME_FILES])
        Map second = RunFlowchartSupport.runReconciliation(ec, [reconciliationId: recId, questionCallExtras: SAME_FILES])
        ec.message.clearErrors()
        List executions = call("facade.ReconciliationFacadeServices.list#ReconciliationExecutions",
                [reconciliationId: recId]).executions as List
        assertEquals([second.reconciliationExecutionId, first.reconciliationExecutionId], executions*.reconciliationExecutionId)
        assertEquals("AUT_STAT_SUCCESS", executions[0].statusEnumId)
        assertEquals(2L, executions[0].questionCount as Long)
    }

    @Test
    void executionsListIncludesDeletedQuestionsRows() {
        String rsA = csvRuleSet("Del exec")
        String recId = call("facade.ReconciliationFacadeServices.save#Reconciliation",
                [reconciliationName: "DelExec ${UUID.randomUUID()}".toString()]).reconciliation.reconciliationId
        String qA = call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsA]).question.reconciliationRunId
        RunFlowchartSupport.runReconciliation(ec, [reconciliationId: recId, questionCallExtras: SAME_FILES])
        ec.message.clearErrors()
        call("facade.ReconciliationFacadeServices.delete#ReconciliationQuestion", [reconciliationRunId: qA])
        List executions = call("facade.ReconciliationFacadeServices.list#ReconciliationExecutions",
                [reconciliationId: recId]).executions as List
        assertEquals(1, executions.size())
    }

    @Test
    void rollupPrefersRunningThenCancelledThenFailed() {
        assertEquals("AUT_STAT_RUNNING", RunFlowchartSupport.rollupStatus(["AUT_STAT_SUCCESS", "AUT_STAT_RUNNING", "AUT_STAT_FAILED"]))
        assertEquals("AUT_STAT_CANCELLED", RunFlowchartSupport.rollupStatus(["AUT_STAT_FAILED", "AUT_STAT_CANCELLED"]))
        assertEquals("AUT_STAT_FAILED", RunFlowchartSupport.rollupStatus(["AUT_STAT_SUCCESS", "AUT_STAT_NOT_RUN", "AUT_STAT_FAILED"]))
        assertEquals("AUT_STAT_SUCCESS", RunFlowchartSupport.rollupStatus(["AUT_STAT_SUCCESS", "AUT_STAT_NO_DATA"]))
    }
}
