package darpan.script

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-167 — a script deployed to run on a schedule and post its text to a chat space. The facade runs as behind
 * /rpc/json (a real DARPAN_USER member, authz ON); the scheduled scan runs with NO user, as the ServiceJob thread does,
 * so each deployment must carry its own tenant. Chat delivery goes to TenantNotificationSupport's delivery hook.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScriptDeploymentSmokeTests {

    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String SVC = "facade.TenantScriptFacadeServices"
    private static final String KREWE_HOOK = "https://chat.googleapis.com/v1/spaces/KREWE/messages?key=k&token=t"
    private static final String GORJANA_HOOK = "https://chat.googleapis.com/v1/spaces/GORJANA/messages?key=k&token=t"

    /** Backorders by SKU as chat text, from a sample shaped like NetSuite sales-order differences. */
    private static final String BACKORDER_TEXT = '''
        var bySku = {:};
        for (var d : input.differences) {
            for (var line : d.record.data.itemsBackordered ?? []) {
                bySku[line.sku] = (bySku[line.sku] ?? 0) + d:dec(line.quantity);
            }
        }
        var lines = '';
        for (var sku : d:sorted(d:keys(bySku))) { lines = lines + '\\n' + sku + ': ' + d:str(bySku[sku]) }
        d:len(bySku) == 0 ? '' : 'Backordered units by SKU' + lines
    '''
    private static final String SAMPLE = '{"differences":[' +
            '{"record":{"data":{"order":"SO1","itemsBackordered":[{"sku":"B:M","quantity":"1"},{"sku":"A:S","quantity":"2"}]}}},' +
            '{"record":{"data":{"order":"SO2","itemsBackordered":[{"sku":"A:S","quantity":"1"}]}}}]}'

    private ExecutionContext ec
    private final List<Map> delivered = []

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-script-deployment")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)

        insert("moqui.security.UserGroup", [userGroupId: GORJANA, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "KREWE_SPACE", companyUserGroupId: KREWE,
                spaceName: "Krewe ops", webhookUrl: KREWE_HOOK, isActive: "Y"])
        insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "GORJANA_SPACE", companyUserGroupId: GORJANA,
                spaceName: "Gorjana ops", webhookUrl: GORJANA_HOOK, isActive: "Y"])
        insert("darpan.script.TenantScript", [tenantScriptId: "GORJANA_SCRIPT", companyUserGroupId: GORJANA,
                scriptName: "Gorjana hello", scriptProfile: "EXPRESSION", inputSource: "SAMPLE",
                scriptBody: "'hello from gorjana'", isActive: "Y"])
        insert("darpan.script.TenantScriptDeployment", [tenantScriptDeploymentId: "GORJANA_DEPLOY", companyUserGroupId: GORJANA,
                tenantScriptId: "GORJANA_SCRIPT", scheduleExpr: "0 0 9 * * ?", chatSpaceId: "GORJANA_SPACE", isActive: "Y",
                nextRunDate: Timestamp.valueOf("2026-01-01 00:00:00")])

        TenantNotificationSupport.setDeliveryHook { String ref, Map payload ->
            delivered << [ref: ref, text: payload?.text]
            return [ok: true, statusCode: 200]
        }
        insert("moqui.security.UserAccount", [userId: "DEPLOY_XT_OTHER", username: "deploy.xt.other",
                                              userFullName: "Other", currentPassword: "", disabled: "N"])
        assertTrue(ec.user.internalLoginUser("deploy.xt.other"))
        actAsKreweEditor()
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void freshTest() {
        actAsKreweEditor()
        delivered.clear()
    }

    private void actAsKreweEditor() {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    @Test
    void aDeployedScriptPreviewsWithoutSendingThenSendsItsTextToTheChatSpace() {
        String scriptId = saveScript("Backorders to chat", BACKORDER_TEXT)
        Map saved = call("save#TenantScriptDeployment", [tenantScriptId: scriptId, scheduleExpr: "0 0 9 * * ?",
                                                         chatSpaceId: "KREWE_SPACE", isActive: true])
        assertTrue(saved.ok as Boolean, "${saved.errors}")
        Map deployment = saved.deployment as Map
        assertNotNull(deployment.nextRunDate, "an active deployment is scheduled")
        assertTrue((deployment.nextRunDate as Timestamp).after(ec.user.nowTimestamp))

        Map preview = call("run#TenantScriptDeployment", [tenantScriptDeploymentId: deployment.tenantScriptDeploymentId])
        assertTrue(preview.ok as Boolean, "${preview.errors}")
        assertEquals("PREVIEW", (preview.outcome as Map).status)
        assertEquals("Backordered units by SKU\nA:S: 3\nB:M: 1", (preview.outcome as Map).text)
        assertEquals([], delivered, "a preview posts nothing")
        assertNull((preview.deployment as Map).lastRunStatus, "a preview is not recorded")

        Map sent = call("run#TenantScriptDeployment", [tenantScriptDeploymentId: deployment.tenantScriptDeploymentId, send: true])
        assertTrue(sent.ok as Boolean, "${sent.errors}")
        assertEquals("SENT", (sent.outcome as Map).status)
        assertEquals([[ref: KREWE_HOOK, text: "Backordered units by SKU\nA:S: 3\nB:M: 1"]], delivered)
        assertEquals("SENT", (sent.deployment as Map).lastRunStatus)
        assertNotNull((sent.deployment as Map).lastRunDate)
    }

    @Test
    void theScheduledScanRunsDueDeploymentsWithNoUserEachAsItsOwnTenant() {
        String scriptId = saveScript("Backorders scheduled", BACKORDER_TEXT)
        Map saved = call("save#TenantScriptDeployment", [tenantScriptId: scriptId, scheduleExpr: "0 0 9 * * ?",
                                                         chatSpaceId: "KREWE_SPACE", isActive: true])
        String id = (saved.deployment as Map).tenantScriptDeploymentId
        Timestamp due = Timestamp.valueOf("2026-01-01 00:00:00")
        setDeployment(id, [nextRunDate: due])
        setDeployment("GORJANA_DEPLOY", [nextRunDate: due, isActive: "Y"])
        Map idle = call("save#TenantScriptDeployment", [tenantScriptId: scriptId, scheduleExpr: "0 0 9 * * ?",
                                                        chatSpaceId: "KREWE_SPACE", isActive: false])
        setDeployment((idle.deployment as Map).tenantScriptDeploymentId as String, [nextRunDate: due])

        Timestamp now = ec.user.nowTimestamp
        ec.user.logoutUser()
        Map scan
        try {
            scan = ec.service.sync().name("script.TenantScriptServices.scan#DueScriptDeployments")
                    .parameters([nowTimestamp: now]).call() as Map
        } finally {
            actAsKreweEditor()
        }

        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        List<Map> results = scan.results as List<Map>
        assertEquals(["GORJANA_DEPLOY", id].toSet(), results*.tenantScriptDeploymentId.toSet(), "the switched-off deployment does not run")
        assertEquals([[ref: KREWE_HOOK, text: "Backordered units by SKU\nA:S: 3\nB:M: 1"], [ref: GORJANA_HOOK, text: "hello from gorjana"]].toSet(),
                delivered.toSet(), "each deployment posts to its own tenant's space: ${results}")
        def mine = row(id)
        assertEquals("SENT", mine.lastRunStatus)
        assertTrue(mine.nextRunDate.after(now), "the next run moved past now")

        // A second tick at the same moment finds nothing due: the claim moved the date first.
        delivered.clear()
        ec.user.logoutUser()
        try {
            Map again = ec.service.sync().name("script.TenantScriptServices.scan#DueScriptDeployments")
                    .parameters([nowTimestamp: now]).call() as Map
            assertEquals(0, again.ran)
        } finally {
            actAsKreweEditor()
        }
        assertEquals([], delivered)
    }

    @Test
    void emptyTextSendsNothingAndAScriptErrorIsRecordedNotSent() {
        String quiet = saveScript("Quiet", "''")
        Map quietDeploy = call("save#TenantScriptDeployment", [tenantScriptId: quiet, scheduleExpr: "0 0 9 * * ?", chatSpaceId: "KREWE_SPACE"])
        Map out = call("run#TenantScriptDeployment", [tenantScriptDeploymentId: (quietDeploy.deployment as Map).tenantScriptDeploymentId, send: true])
        assertEquals("NOTHING_TO_SEND", (out.outcome as Map).status)

        String broken = saveScript("Broken", "d:dec('not a number')")
        Map brokenDeploy = call("save#TenantScriptDeployment", [tenantScriptId: broken, scheduleExpr: "0 0 9 * * ?", chatSpaceId: "KREWE_SPACE"])
        Map failed = call("run#TenantScriptDeployment", [tenantScriptDeploymentId: (brokenDeploy.deployment as Map).tenantScriptDeploymentId, send: true])
        assertTrue(failed.ok as Boolean, "a script error is an outcome, not a service error: ${failed.errors}")
        assertEquals("SCRIPT_ERROR", (failed.outcome as Map).status)
        assertEquals("SCRIPT_ERROR", (failed.deployment as Map).lastRunStatus)

        String notText = saveScript("Number", "42")
        Map numberDeploy = call("save#TenantScriptDeployment", [tenantScriptId: notText, scheduleExpr: "0 0 9 * * ?", chatSpaceId: "KREWE_SPACE"])
        Map number = call("run#TenantScriptDeployment", [tenantScriptDeploymentId: (numberDeploy.deployment as Map).tenantScriptDeploymentId])
        assertEquals("SCRIPT_ERROR", (number.outcome as Map).status)
        assertTrue((number.outcome as Map).message.toString().contains("returns text"), "${number.outcome}")
        assertEquals([], delivered)
    }

    @Test
    void aDeploymentCannotUseAnotherTenantsSpaceOrABadScheduleOrMissTheRunItReads() {
        String scriptId = saveScript("Refusals", "'x'")
        Map foreign = call("save#TenantScriptDeployment", [tenantScriptId: scriptId, scheduleExpr: "0 0 9 * * ?", chatSpaceId: "GORJANA_SPACE"])
        assertEquals(["Chat space 'GORJANA_SPACE' was not found or is switched off."], foreign.errors)

        Map badCron = call("save#TenantScriptDeployment", [tenantScriptId: scriptId, scheduleExpr: "every tuesday", chatSpaceId: "KREWE_SPACE"])
        assertFalse(badCron.ok as Boolean)
        assertTrue((badCron.errors as List)[0].toString().contains("not a schedule"), "${badCron.errors}")

        Map runResultScript = call("save#TenantScript", [scriptName: "Reads a run", scriptProfile: "EXPRESSION",
                                                         inputSource: "RUN_RESULT", scriptBody: "'x'"])
        Map noRun = call("save#TenantScriptDeployment", [tenantScriptId: (runResultScript.script as Map).tenantScriptId,
                                                         scheduleExpr: "0 0 9 * * ?", chatSpaceId: "KREWE_SPACE"])
        assertEquals(["This script reads a run result: choose the saved run whose newest result it reads."], noRun.errors)

        Map foreignScript = call("save#TenantScriptDeployment", [tenantScriptId: "GORJANA_SCRIPT", scheduleExpr: "0 0 9 * * ?", chatSpaceId: "KREWE_SPACE"])
        assertEquals(["Script 'GORJANA_SCRIPT' was not found."], foreignScript.errors)
    }

    @Test
    void anotherTenantsDeploymentIsInvisibleAndUntouchable() {
        Map listed = call("list#TenantScriptDeployments", [:])
        assertFalse((listed.deployments as List<Map>).any { it.tenantScriptDeploymentId == "GORJANA_DEPLOY" })
        assertEquals(["Deployment 'GORJANA_DEPLOY' was not found."], call("run#TenantScriptDeployment", [tenantScriptDeploymentId: "GORJANA_DEPLOY", send: true]).errors)
        assertEquals(["Deployment 'GORJANA_DEPLOY' was not found."], call("delete#TenantScriptDeployment", [tenantScriptDeploymentId: "GORJANA_DEPLOY"]).errors)
        assertEquals(["Deployment 'GORJANA_DEPLOY' was not found."], call("save#TenantScriptDeployment", [tenantScriptDeploymentId: "GORJANA_DEPLOY",
                tenantScriptId: "GORJANA_SCRIPT", scheduleExpr: "0 0 9 * * ?", chatSpaceId: "KREWE_SPACE"]).errors)
        assertNotNull(row("GORJANA_DEPLOY"))
        assertEquals([], delivered)
    }

    @Test
    void deletingAScriptDeletesItsDeployments() {
        String scriptId = saveScript("Short lived", "'x'")
        Map saved = call("save#TenantScriptDeployment", [tenantScriptId: scriptId, scheduleExpr: "0 0 9 * * ?", chatSpaceId: "KREWE_SPACE", isActive: true])
        String id = (saved.deployment as Map).tenantScriptDeploymentId
        assertTrue(call("delete#TenantScript", [tenantScriptId: scriptId]).ok as Boolean)
        assertNull(row(id))
    }

    private String saveScript(String name, String body) {
        Map out = call("save#TenantScript", [scriptName: name, scriptProfile: "SCRIPT", inputSource: "SAMPLE",
                                             scriptBody: body, sampleInputJson: SAMPLE])
        assertTrue(out.ok as Boolean, "save#TenantScript failed: ${out.errors}")
        return (out.script as Map).tenantScriptId as String
    }

    private Map call(String verbNoun, Map params) {
        return withProductionAuthz { (Map) ec.service.sync().name("${SVC}.${verbNoun}".toString()).parameters(params).call() }
    }

    private def row(String id) {
        return ec.entity.find("darpan.script.TenantScriptDeployment").condition("tenantScriptDeploymentId", id).disableAuthz().useCache(false).one()
    }

    private void setDeployment(String id, Map fields) {
        def value = row(id)
        value.setAll(fields)
        value.update()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    /** Production posture: no harness grant on the stack, authz ON. Copied from SftpServerCrossTenantSmokeTests. */
    private <T> T withProductionAuthz(Closure<T> body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            return body.call()
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
            ec.message.clearErrors()
        }
    }
}
