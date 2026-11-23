package darpan.query

import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.jexl.script.ScriptDeliveries
import org.moqui.jexl.script.ScriptResult

import static darpan.query.QueryTestSupport.EDITOR_ID
import static darpan.query.QueryTestSupport.GORJANA
import static darpan.query.QueryTestSupport.KREWE
import static darpan.query.QueryTestSupport.insert
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-334: the chat and email deliveries of scheduled queries — the query's tenant only, an export row per emailed file. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class QueryDeliverySmokeTests {
    private static final Map FILE = [name: "late.csv", columns: ["orderId"], rows: [["SO-1"], ["SO-2"]]]
    private ExecutionContext ec
    private final List<Map> posts = []
    private final List<Map> emails = []

    @BeforeAll
    void setup() {
        ec = QueryTestSupport.boot("query-delivery")
        insert(ec, "darpan.reconciliation.TenantChatSpace", [chatSpaceId: "QD_KREWE_SPACE", companyUserGroupId: KREWE,
                spaceName: "Krewe ops", webhookUrl: "https://chat.example.test/krewe", isActive: "Y"])
        insert(ec, "darpan.reconciliation.TenantChatSpace", [chatSpaceId: "QD_GORJANA_SPACE", companyUserGroupId: GORJANA,
                spaceName: "Gorjana ops", webhookUrl: "https://chat.example.test/gorjana", isActive: "Y"])
        ["QD_K": KREWE, "QD_G": GORJANA].each { String id, String tenant ->
            insert(ec, "jexl.script.UserScript", [userScriptId: id, companyUserGroupId: tenant, scriptName: "Late orders",
                    scriptProfile: "QUERY", scriptBody: "'x'", isActive: "Y", ownerUserId: EDITOR_ID])
            insert(ec, "jexl.script.UserScriptDeployment", [scriptDeploymentId: id + "_D", userScriptId: id, companyUserGroupId: tenant,
                    cronExpression: "0 0 9 * * ?", ownerUserId: EDITOR_ID, isActive: "N"])
        }
        insert(ec, "jexl.script.UserScriptDeploymentDelivery", [scriptDeploymentId: "QD_K_D", deliverySeqId: "01",
                deliveryServiceName: QueryDelivery.EMAIL_SERVICE, parametersJson: '{"toAddresses":"ops@example.test, lead@example.test"}', isActive: "Y"])
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> posts << [ref: ref, text: payload?.text]; return [ok: true, statusCode: 200] }
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        ScriptDeliveries.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @AfterEach
    void reset() {
        posts.clear(); emails.clear()
        ScriptDeliveries.resetForTests()
        ec.message.clearAll()
    }

    private Map deliver(String service, String deploymentId, Map parameters, Map result = [summary: "2 late orders", files: [FILE], data: null]) {
        Map deployment = ec.entity.find("jexl.script.UserScriptDeployment").condition("scriptDeploymentId", deploymentId).disableAuthz().useCache(false).one().getMap()
        List files = ((List<Map>) result.files).collect { Map f -> [name: f.name, contentType: "text/csv", contentBytes: ScriptResult.toCsv(f)] }
        Map out = ec.service.sync().name("query.QueryHostServices.${service}").parameters([scriptDeployment: deployment,
                delivery: [parameters: parameters], scriptResult: result, files: files, scriptRunId: "QD_RUN"]).call()
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        return out + [errors: errors]
    }

    @Test
    void chatGetsTheSummaryNotFiles() {
        Map out = deliver("deliver#QueryChat", "QD_K_D", [chatSpaceId: "QD_KREWE_SPACE"], [summary: "2 late orders", files: [], data: null])
        assertEquals("OK", out.outcome, out.errors as String)
        assertEquals(1, posts.size())
        assertEquals("Late orders: 2 late orders", posts[0].text)
    }

    @Test
    void chatSaysWhatWasEmailed() {
        deliver("deliver#QueryChat", "QD_K_D", [chatSpaceId: "QD_KREWE_SPACE"])
        assertTrue((posts[0].text as String).contains("Sent 1 file (late.csv) to 2 people by email."), posts[0].text as String)
    }

    @Test
    void emailWritesOneExportRowPerFileWithRecipients() {
        ScriptDeliveries.sendEmail = { def c, Map parameters -> emails << parameters; return [:] }
        Map out = deliver("deliver#QueryEmail", "QD_K_D", [toAddresses: "ops@example.test", ccAddresses: "lead@example.test"])
        assertEquals("OK", out.outcome, out.errors as String)
        assertEquals("DARPAN_QUERY_RESULT", emails[0].emailTemplateId)
        assertEquals(["late.csv"], emails[0].attachments*.fileName)
        def logs = ec.entity.find("darpan.admin.DataExportLog").condition("resourceType", "SCRIPT_EMAIL").condition("resourceRef", "late.csv")
                .disableAuthz().useCache(false).list()
        assertEquals(1, logs.size())
        assertEquals("ops@example.test, lead@example.test", logs[0].recipients)
        assertEquals(KREWE, logs[0].companyUserGroupId)
        assertEquals(EDITOR_ID, logs[0].userId)
    }

    @Test
    void aForeignChatSpaceFails() {
        Map out = deliver("deliver#QueryChat", "QD_K_D", [chatSpaceId: "QD_GORJANA_SPACE"])
        assertTrue((out.errors as String).contains("not this tenant's"), out.errors as String)
        assertEquals(0, posts.size())
    }

    @Test
    void anOwnerWhoLeftTheTenantDeliversNothing() {
        ScriptDeliveries.sendEmail = { def c, Map parameters -> emails << parameters; return [:] }
        Map out = deliver("deliver#QueryEmail", "QD_G_D", [toAddresses: "ops@example.test"])
        assertTrue((out.errors as String).contains("not a member"), out.errors as String)
        assertEquals(0, emails.size())
    }
}
