package darpan.query

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.jexl.ScriptProfiles

import static darpan.query.QueryTestSupport.GORJANA
import static darpan.query.QueryTestSupport.KREWE
import static darpan.query.QueryTestSupport.insert
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-334: Darpan's extension of moqui-jexl — tenant field and filter, QUERY profile, run-admission busy check, owner key. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class QueryHostSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = QueryTestSupport.boot("query-host")
        insert(ec, "jexl.script.UserScript", [userScriptId: "QH_KREWE", companyUserGroupId: KREWE, scriptName: "Krewe query",
                scriptProfile: "QUERY", scriptBody: "'k'", isActive: "Y"])
        insert(ec, "jexl.script.UserScript", [userScriptId: "QH_GORJANA", companyUserGroupId: GORJANA, scriptName: "Gorjana query",
                scriptProfile: "QUERY", scriptBody: "'g'", isActive: "Y"])
    }

    @AfterAll
    void cleanup() {
        QueryLoad.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void queryProfileHasTheSpecLimits() {
        assertEquals(20_000, ScriptProfiles.get("QUERY").maxReadRows)
        assertEquals(5_000_000L, ScriptProfiles.get("QUERY").units)
        assertEquals(10_000L, ScriptProfiles.get("QUERY").timeoutMs)
        assertEquals(20_000, ScriptProfiles.get("QUERY").maxCollectionSize)
        assertEquals(2_000_000, ScriptProfiles.get("QUERY").maxInputValues)
    }

    @Test
    void ownerKeyIsTheTenant() {
        Map out = ec.service.sync().name("query.QueryHostServices.get#QueryOwnerKey")
                .parameters([scriptDeployment: [scriptDeploymentId: "X", companyUserGroupId: KREWE]]).call()
        assertEquals(KREWE, out.ownerKey)
    }

    @Test
    void busyWhenRunsAreQueuedOrAtTheirCap() {
        QueryLoad.probe = { -> [runsQueued: 1, runsRunning: 2, runsCap: 8] }
        assertTrue((ec.service.sync().name("query.QueryHostServices.get#QueryBusyReason").call().busyReason as String).contains("waiting to start"))
        QueryLoad.probe = { -> [runsQueued: 0, runsRunning: 8, runsCap: 8] }
        assertTrue((ec.service.sync().name("query.QueryHostServices.get#QueryBusyReason").call().busyReason as String).contains("at their limit"))
        QueryLoad.probe = { -> [runsQueued: 0, runsRunning: 1, runsCap: 8] }
        assertNull(ec.service.sync().name("query.QueryHostServices.get#QueryBusyReason").call().busyReason)
        QueryLoad.resetForTests()
    }

    @Test
    void moquiJexlAsksDarpanWhetherItIsBusy() {
        assertEquals("query.QueryHostServices.get#QueryBusyReason", System.getProperty("jexl_busy_service"))
        assertEquals("query.QueryHostServices.get#QueryOwnerKey", System.getProperty("jexl_owner_key_service"))
    }

    @Test
    void aScriptIsStoredOnlyForTheCallersActiveTenant() {
        ec.service.sync().name("moqui.jexl.ScriptServices.store#UserScript").parameters([userScriptId: "QH_STEAL", scriptName: "Steal",
                scriptBody: "'x'", ownerFields: [companyUserGroupId: GORJANA]]).call()
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        ec.service.sync().name("moqui.jexl.ScriptServices.store#UserScript").parameters([userScriptId: "QH_OWN", scriptName: "Own",
                scriptBody: "'x'", ownerFields: [companyUserGroupId: KREWE]]).call()
        String ownErrors = ec.message.errorsString
        ec.message.clearErrors()

        assertTrue(errors.contains("active tenant"), errors)
        assertEquals("", ownErrors)
        assertNull(ec.entity.find("jexl.script.UserScript").condition("userScriptId", "QH_STEAL").disableAuthz().useCache(false).one())
        assertEquals(KREWE, ec.entity.find("jexl.script.UserScript").condition("userScriptId", "QH_OWN").disableAuthz().useCache(false).one().companyUserGroupId)
    }
}
