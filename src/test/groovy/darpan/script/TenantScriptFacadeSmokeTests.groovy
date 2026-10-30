package darpan.script

import darpan.facade.common.TenantAccessSupport
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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-164 — a tenant writes, saves and runs a script through the facade, as behind /rpc/json: a real DARPAN_USER
 * member, the harness grant removed, authz ON. Includes the cross-tenant case the XML-actions rule asks for: another
 * tenant's script is invisible and cannot be run, changed or deleted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScriptFacadeSmokeTests {

    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String SVC = "facade.TenantScriptFacadeServices"

    private static final String TENDER_SCRIPT = '''
        var paid = 0;
        for (var p : input.payments) { paid = paid + d:dec(p.amount) }
        { 'orderId': input.orderId, 'paid': d:money(paid), 'cards': d:count(input.payments, 'tender', 'CARD') }
    '''
    private static final String SAMPLE = '{"orderId":"KRW-1","payments":[{"tender":"CARD","amount":"12.50"},{"tender":"CASH","amount":7}]}'

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-script-facade")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // KREWE tenant + editor membership

        insert("moqui.security.UserGroup", [userGroupId: GORJANA, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        insert("darpan.script.TenantScript", [tenantScriptId: "GORJANA_SCRIPT", companyUserGroupId: GORJANA,
                scriptName: "Gorjana secret", scriptProfile: "EXPRESSION", inputSource: "SAMPLE",
                scriptBody: "'gorjana-only'", isActive: "Y", createdByUserId: "GORJANA_ADMIN"])

        // UserFacade caches groups per login; switch user once so the DARPAN_USER membership is seen.
        insert("moqui.security.UserAccount", [userId: "SCRIPT_XT_OTHER", username: "script.xt.other",
                                              userFullName: "Other", currentPassword: "", disabled: "N"])
        assertTrue(ec.user.internalLoginUser("script.xt.other"))
        actAsKreweEditor()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsKreweEditor() {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    @Test
    void aTenantSavesAScriptAndRunsItWithNoCodeChange() {
        Map saved = call("save#TenantScript", [scriptName: "Tender mix", scriptProfile: "SCRIPT", inputSource: "SAMPLE",
                                               scriptBody: TENDER_SCRIPT, sampleInputJson: SAMPLE])
        assertTrue(saved.ok as Boolean, "save failed: ${saved.errors}")
        String id = (saved.script as Map).tenantScriptId as String
        assertTrue(id ==~ /\d+/, "a new script takes a minted id, got ${id}")
        assertEquals(KREWE, row(id).companyUserGroupId)

        Map listed = call("list#TenantScripts", [:])
        assertTrue(listed.ok as Boolean, "${listed.errors}")
        assertTrue((listed.scripts as List<Map>).any { it.tenantScriptId == id && it.scriptName == "Tender mix" })
        assertTrue((listed.library as List).size() > 10, "the editor gets the d: library")

        Map ran = call("run#TenantScript", [tenantScriptId: id])
        assertTrue(ran.ok as Boolean, "${ran.errors}")
        Map run = ran.run as Map
        assertTrue(run.ok as Boolean, "${run}")
        assertEquals([orderId: "KRW-1", paid: new BigDecimal("19.50"), cards: 1], run.result)
        assertTrue((run.units as long) > 0)
        assertEquals("SAMPLE", (ran.inputSummary as Map).source)

        // The editor tries an unsaved edit against other input; the saved row is untouched.
        Map draft = call("run#TenantScript", [tenantScriptId: id, scriptProfile: "EXPRESSION",
                                              scriptBody: "d:len(input.payments) > 1", inputJson: '{"payments":[1,2,3]}'])
        assertTrue(draft.ok as Boolean, "${draft.errors}")
        assertEquals(true, (draft.run as Map).result)
        assertEquals(TENDER_SCRIPT, row(id).scriptBody)
    }

    @Test
    void aScriptThatDoesNotParseIsRefusedWithTheLineAndNotSaved() {
        Map out = call("save#TenantScript", [scriptName: "Broken", scriptBody: "var a = 1;\nvar b = ;"])
        assertFalse(out.ok as Boolean)
        assertTrue((out.errors as List)[0].toString().startsWith("Line 2"), "${out.errors}")
        assertNull(rowNamed("Broken"))

        Map hostile = call("save#TenantScript", [scriptName: "Hostile", scriptBody: "''.class.forName('java.lang.Runtime')"])
        assertFalse(hostile.ok as Boolean)
        assertEquals("NOT_ALLOWED", (hostile.validation as Map).error.kind)
        assertNull(rowNamed("Hostile"))
    }

    @Test
    void aScriptErrorIsARunResultNotAServiceError() {
        Map out = call("run#TenantScript", [scriptBody: "while (true) { }", inputJson: "{}"])
        assertTrue(out.ok as Boolean, "the service itself succeeds: ${out.errors}")
        assertFalse((out.run as Map).ok as Boolean)
        assertEquals("UNITS", ((out.run as Map).error as Map).kind)
    }

    @Test
    void badInputIsAServiceErrorBeforeAnythingRuns() {
        Map json = call("run#TenantScript", [scriptBody: "input", inputJson: "{not json"])
        assertFalse(json.ok as Boolean)
        assertTrue((json.errors as List)[0].toString().contains("not valid JSON"), "${json.errors}")
        Map noRun = call("run#TenantScript", [scriptBody: "input", inputSource: "RUN_RESULT"])
        assertFalse(noRun.ok as Boolean)
        assertEquals(["Choose a run result for the script to read."], noRun.errors)
    }

    @Test
    void anotherTenantsScriptIsInvisibleAndUntouchable() {
        Map listed = call("list#TenantScripts", [:])
        assertFalse((listed.scripts as List<Map>).any { it.tenantScriptId == "GORJANA_SCRIPT" }, "foreign script leaked: ${listed.scripts}")

        Map ran = call("run#TenantScript", [tenantScriptId: "GORJANA_SCRIPT"])
        assertEquals(["Script 'GORJANA_SCRIPT' was not found."], ran.errors)
        assertNull(ran.run)

        Map saved = call("save#TenantScript", [tenantScriptId: "GORJANA_SCRIPT", scriptName: "Hijack", scriptBody: "1"])
        assertEquals(["Script 'GORJANA_SCRIPT' was not found."], saved.errors)

        Map deleted = call("delete#TenantScript", [tenantScriptId: "GORJANA_SCRIPT"])
        assertEquals(["Script 'GORJANA_SCRIPT' was not found."], deleted.errors)

        def victim = row("GORJANA_SCRIPT")
        assertEquals(GORJANA, victim.companyUserGroupId)
        assertEquals("Gorjana secret", victim.scriptName)
        assertEquals("'gorjana-only'", victim.scriptBody)
    }

    @Test
    void namesAreUniqueWithinTheTenantAndDeleteRemovesTheRow() {
        Map first = call("save#TenantScript", [scriptName: "Once", scriptProfile: "EXPRESSION", scriptBody: "1 + 1"])
        assertTrue(first.ok as Boolean, "${first.errors}")
        Map clash = call("save#TenantScript", [scriptName: "once", scriptProfile: "EXPRESSION", scriptBody: "2"])
        assertEquals(["A script named 'once' already exists."], clash.errors)
        String id = (first.script as Map).tenantScriptId as String
        Map deleted = call("delete#TenantScript", [tenantScriptId: id])
        assertTrue(deleted.ok as Boolean, "${deleted.errors}")
        assertNull(row(id))
    }

    private Map call(String verbNoun, Map params) {
        return withProductionAuthz { (Map) ec.service.sync().name("${SVC}.${verbNoun}".toString()).parameters(params).call() }
    }

    private def row(String id) {
        return ec.entity.find("darpan.script.TenantScript").condition("tenantScriptId", id).disableAuthz().useCache(false).one()
    }

    private def rowNamed(String name) {
        return ec.entity.find("darpan.script.TenantScript").condition("scriptName", name).disableAuthz().useCache(false).one()
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
