package darpan.query

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static darpan.query.QueryTestSupport.EDITOR_ID
import static darpan.query.QueryTestSupport.GORJANA
import static darpan.query.QueryTestSupport.KREWE
import static darpan.query.QueryTestSupport.insert
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull

/** DAR-BE-334: migration TENANT_SCRIPTS_TO_QUERIES — old tenant scripts and deployments become queries and schedules. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class QueryMigrationSmokeTests {
    private ExecutionContext ec
    private Map first

    @BeforeAll
    void setup() {
        ec = QueryTestSupport.boot("query-migration")
        insert(ec, "darpan.script.TenantScript", [tenantScriptId: "QM_SAMPLE", companyUserGroupId: KREWE, scriptName: "Tender mix",
                scriptProfile: "SCRIPT", inputSource: "SAMPLE", scriptBody: "size(input.payments)", sampleInputJson: '{"payments":[]}',
                isActive: "Y", createdByUserId: EDITOR_ID])
        insert(ec, "darpan.script.TenantScript", [tenantScriptId: "QM_EXPR", companyUserGroupId: KREWE, scriptName: "One line",
                scriptProfile: "EXPRESSION", inputSource: "SAMPLE", scriptBody: "input.a + 1", isActive: "Y", createdByUserId: EDITOR_ID])
        insert(ec, "darpan.script.TenantScript", [tenantScriptId: "QM_RUN", companyUserGroupId: GORJANA, scriptName: "Backorders",
                scriptProfile: "SCRIPT", inputSource: "RUN_RESULT", scriptBody: "size(input.differences)", isActive: "Y", createdByUserId: "GORJANA_ADMIN"])
        insert(ec, "darpan.reconciliation.TenantChatSpace", [chatSpaceId: "GORJANA_SPACE", companyUserGroupId: GORJANA, spaceName: "Gorjana ops",
                webhookUrl: "https://chat.example.test/gorjana", isActive: "Y"])
        insert(ec, "darpan.script.TenantScriptDeployment", [tenantScriptDeploymentId: "QM_DEPLOY", companyUserGroupId: GORJANA,
                tenantScriptId: "QM_RUN", scheduleExpr: "0 0 9 * * ?", inputSavedRunId: "SAVED_7", chatSpaceId: "GORJANA_SPACE",
                isActive: "Y", lastUpdatedByUserId: "GORJANA_ADMIN"])
        first = ec.service.sync().name("query.QueryHostServices.migrate#TenantScriptsToQueries").call()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private def query(String id) { ec.entity.find("jexl.script.UserScript").condition("userScriptId", id).disableAuthz().useCache(false).one() }

    @Test
    void aSampleScriptMigratesAsAQuery() {
        def q = query("MIG_QM_SAMPLE")
        assertEquals(KREWE, q.companyUserGroupId)
        assertEquals("QUERY", q.scriptProfile)
        assertEquals("Tender mix", q.scriptName)
        assertEquals("size(input.payments)", q.scriptBody)
        assertNull(q.readSpecJson)
        assertEquals("Y", q.isActive)
    }

    @Test
    void anExpressionScriptMigratesAsAScript() {
        def q = query("MIG_QM_EXPR")
        assertEquals("QUERY", q.scriptProfile)
        assertEquals("input.a + 1", q.scriptBody)
    }

    @Test
    void aRunResultScriptMigratesWithAReadAndSwitchedOff() {
        def q = query("MIG_QM_RUN")
        Map read = (Map) new JsonSlurper().parseText(q.readSpecJson as String)
        assertEquals("runResult", read.inputs[0].source)
        assertEquals("SAVED_7", read.inputs[0].savedRunId)
        assertEquals("N", q.isActive)
    }

    @Test
    void aChatDeploymentMigratesOffWithChatOnly() {
        def d = ec.entity.find("jexl.script.UserScriptDeployment").condition("scriptDeploymentId", "MIG_QM_DEPLOY").disableAuthz().useCache(false).one()
        assertNotNull(d)
        assertEquals("N", d.isActive)
        assertEquals(GORJANA, d.companyUserGroupId)
        assertEquals("GORJANA_ADMIN", d.ownerUserId)
        assertEquals("MIG_QM_RUN", d.userScriptId)
        def deliveries = ec.entity.find("jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", "MIG_QM_DEPLOY").disableAuthz().useCache(false).list()
        assertEquals(["query.QueryHostServices.deliver#QueryChat"], deliveries*.deliveryServiceName)
        assertEquals([chatSpaceId: "GORJANA_SPACE"], new JsonSlurper().parseText(deliveries[0].parametersJson as String))
    }

    @Test
    void migrationIsIdempotent() {
        assertEquals(3, first.queries)
        assertEquals(1, first.schedules)
        Map again = ec.service.sync().name("query.QueryHostServices.migrate#TenantScriptsToQueries").call()
        assertEquals(0, again.queries)
        assertEquals(0, again.schedules)
        assertEquals(4, again.skipped)
    }
}
