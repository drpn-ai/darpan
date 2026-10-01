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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-UI-048. Save a chart through the facade, refuse a bad one, run it end to end, read the execution. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunFlowchartFacadeSmokeTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "run-flowchart-facade-smoke")
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

    @Test
    void aChartSavesRunsAndReadsBack() {
        String rsA = csvRuleSet("Has it shipped"), rsB = csvRuleSet("Has it billed"), rsC = csvRuleSet("Why not shipped")
        String recId = call("facade.ReconciliationFacadeServices.save#Reconciliation",
                [reconciliationName: "Chart ${UUID.randomUUID()}".toString()]).reconciliation.reconciliationId
        String qA = call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsA, runSequence: 1, noOutcomeLabel: "Never shipped"]).question.reconciliationRunId
        String qB = call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsB, parentReconciliationRunId: qA, parentBranch: "YES"]).question.reconciliationRunId
        String qC = call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsC, parentReconciliationRunId: qA, parentBranch: "NO"]).question.reconciliationRunId
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        Map got = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId])
        assertEquals(3, (got.questions as List).size())
        assertEquals("KREWE", got.reconciliation.companyUserGroupId)

        // A: FILE_1 A100..A400, FILE_2 lacks A400 -> A400 is "no"; yes = A100..A300.
        // B (yes of A): FILE_1 restricted to A100..A300; FILE_2 lacks A300 -> no = A300, yes = A100, A200.
        // C (no of A): FILE_1 restricted to A400; FILE_2 has it -> yes = A400.
        Map texts = [(qA): ["order_id\nA100\nA200\nA300\nA400\n", "order_id\nA100\nA200\nA300\n"],
                     (qB): ["order_id\nA100\nA200\nA300\nA400\n", "order_id\nA100\nA200\n"],
                     (qC): ["order_id\nA100\nA200\nA300\nA400\n", "order_id\nA400\n"]]
        Map summary = RunFlowchartSupport.runReconciliation(ec, [
                reconciliationId: recId,
                questionCallExtras: { Map q -> [file1Name: "f1.csv", file1Text: texts[q.reconciliationRunId][0],
                                               file2Name: "f2.csv", file2Text: texts[q.reconciliationRunId][1], hasHeader: true] }])
        assertEquals("AUT_STAT_SUCCESS", summary.statusEnumId, summary.toString())

        List results = call("facade.ReconciliationFacadeServices.get#ReconciliationExecution",
                [reconciliationExecutionId: summary.reconciliationExecutionId]).results as List
        Map byQ = results.collectEntries { [(it.reconciliationRunId): it] }
        assertEquals(3L, byQ[qA].yesCount as Long)
        assertEquals(2L, byQ[qB].yesCount as Long)
        assertEquals(1L, byQ[qC].yesCount as Long)
        assertEquals(byQ[qA].reconciliationRunResultId, byQ[qB].parentRunResultId)
    }

    @Test
    void aBadTreeIsRefusedWithTheTreeRule() {
        String rsA = csvRuleSet("Loop A"), rsB = csvRuleSet("Loop B")
        String recId = call("facade.ReconciliationFacadeServices.save#Reconciliation",
                [reconciliationName: "Bad ${UUID.randomUUID()}".toString()]).reconciliation.reconciliationId
        String qA = call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsA]).question.reconciliationRunId
        ec.message.clearErrors()
        Map out = call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
                [reconciliationId: recId, ruleSetId: rsB, parentReconciliationRunId: qA])
        assertFalse(out.ok as boolean)
        assertTrue((out.errors as List).any { it.toString().contains("yes or no") })
        ec.message.clearErrors()
    }

    @Test
    void anotherTenantsRunIsNotFound() {
        String recId = call("facade.ReconciliationFacadeServices.save#Reconciliation",
                [reconciliationName: "Mine ${UUID.randomUUID()}".toString()]).reconciliation.reconciliationId
        // A preference alone cannot move a user into a tenant they do not belong to, so sign in as a
        // real GORJANA member (the SavedRunsFacadeSmokeTests.loginAsTenantUser pattern), then restore.
        signInAsGorjanaEditor()
        try {
            Map out = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId])
            assertFalse(out.ok as boolean)
            assertTrue((out.errors as List).any { it.toString().contains("was not found") })
        } finally {
            ec.message.clearErrors()
            if (!ec.user.internalLoginUser("TEST_CUSTOMER_USER")) ec.user.internalLoginUser("test.customer")
            ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, "KREWE")
        }
    }

    private void signInAsGorjanaEditor() {
        java.sql.Timestamp from = java.sql.Timestamp.valueOf("2026-04-23 00:00:00")
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "GORJANA", description: "GORJANA",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "TEST_GORJANA_EDITOR",
                    username: "test.gorjana.editor", userFullName: "test.gorjana.editor", currentPassword: "", disabled: "N"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "GORJANA",
                    userId: "TEST_GORJANA_EDITOR", fromDate: from]).createOrUpdate()
            ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                    tenantUserGroupId: "GORJANA", userId: "TEST_GORJANA_EDITOR",
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: from]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        if (!ec.user.internalLoginUser("TEST_GORJANA_EDITOR")) ec.user.internalLoginUser("test.gorjana.editor")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, "GORJANA")
        assertEquals("GORJANA", TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        ec.message.clearErrors()
    }

    @Test
    void runFromTheFacadeReturnsAnExecutionIdAtOnce() {
        String rsA = csvRuleSet("Async A")
        String recId = call("facade.ReconciliationFacadeServices.save#Reconciliation",
                [reconciliationName: "Async ${UUID.randomUUID()}".toString()]).reconciliation.reconciliationId
        call("facade.ReconciliationFacadeServices.save#ReconciliationQuestion", [reconciliationId: recId, ruleSetId: rsA])
        Map out = call("facade.ReconciliationFacadeServices.run#Reconciliation", [reconciliationId: recId])
        assertTrue(out.ok as boolean, out.errors?.toString())
        assertNotNull(out.reconciliationExecutionId)
    }
}
