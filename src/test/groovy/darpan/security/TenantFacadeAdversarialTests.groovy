package darpan.security

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-277 — real facade services, called as /rpc/json would call them for a tenant-A editor, pointed at tenant B's
 * ids. Each test asserts the SECURE outcome: red = a confirmed hole, green = the boundary held.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantFacadeAdversarialTests {

    static final String A = TenantAttackHarness.TENANT_A
    static final String B = TenantAttackHarness.TENANT_B

    TenantAttackHarness h

    @BeforeAll
    void setup() {
        h = new TenantAttackHarness("tenant-facade-adversarial")
        h.withoutReferentialIntegrity {
            h.insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "ADV_SPACE_B", companyUserGroupId: B,
                    spaceName: "B space", webhookUrl: "https://chat.googleapis.com/v1/spaces/B/messages?key=k&token=t", isActive: "Y"])
            h.insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "ADV_RR_B", companyUserGroupId: B,
                    statusEnumId: "AUT_STAT_RUNNING"])
        }
    }

    @AfterAll
    void cleanup() { h.close() }

    @BeforeEach
    void attacker() { h.asAttacker() }

    // ---- notification subscription keyed by a run-result id (child with no tenant column) ----

    @Test
    void subscribeRunNotificationRefusesForeignRunResult() {
        Map out = h.call("facade.ReconciliationFacadeServices.subscribe#RunNotification", [reconciliationRunResultId: "ADV_RR_B"])
        assertNotEquals(true, out.subscribed, "subscribed to tenant B's run: ${out}")
        assertTrue(h.rawList("darpan.reconciliation.ReconciliationRunNotifySubscription",
                [reconciliationRunResultId: "ADV_RR_B"]).isEmpty(), "a subscription row was written on tenant B's run")
    }

    // ---- chat space -------------------------------------------------------------------------

    @Test
    void saveTenantChatSpaceCannotOverwriteForeignSpace() {
        Map out = h.call("facade.SettingsFacadeServices.save#TenantChatSpace", [chatSpaceId: "ADV_SPACE_B", spaceName: "hijacked",
                webhookUrl: "https://chat.googleapis.com/v1/spaces/ATTACKER/messages?key=k&token=t"])
        def row = h.raw("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "ADV_SPACE_B"])
        assertEquals(B, row.companyUserGroupId, "tenant B's chat space re-owned: ${out}")
        assertEquals("B space", row.spaceName, "tenant B's chat space renamed: ${out}")
    }

    @Test
    void listTenantChatSpacesHidesForeignSpace() {
        Map out = h.call("facade.SettingsFacadeServices.list#TenantChatSpaces")
        assertFalse((out.chatSpaces as List ?: [])*.chatSpaceId.contains("ADV_SPACE_B"), "listed tenant B's space: ${out.chatSpaces}")
    }

    // ---- generated outputs addressed by a bare file name (no folder) ---------------------------

    static final String SECRET = "TENANT_B_SECRET_DIFF"

    /** Tenant B's result written at the data-manager root (the legacy, pre-run-folder layout) with B's run row on it. */
    private String seedBRootResult(String name) {
        File f = DataManagerSupport.resolveDataManagerFile(h.ec, name, true)
        f.parentFile.mkdirs()
        f.text = """{"differences":[{"type":"MISSING","id":"${SECRET}"}],"summary":{"total":1}}"""
        h.insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "RR_" + name, companyUserGroupId: B,
                statusEnumId: "AUT_STAT_SUCCESS", resultDataManagerPath: name])
        return name
    }

    @Test
    void getGeneratedOutputRefusesForeignBareNameAtDataManagerRoot() {
        String name = seedBRootResult("adv_b_read_result.json")
        Map out = h.call("facade.ReconciliationFacadeServices.get#GeneratedOutput", [fileName: name])
        assertFalse(String.valueOf(out.outputFile).contains(SECRET), "tenant A read tenant B's result file: ${out.outputFile}")
    }

    @Test
    void deleteGeneratedOutputCannotDeleteForeignBareNameAtDataManagerRoot() {
        String name = seedBRootResult("adv_b_del_result.json")
        Map out = h.call("facade.ReconciliationFacadeServices.delete#GeneratedOutput", [fileName: name])
        assertTrue(DataManagerSupport.resolveDataManagerFile(h.ec, name, false).exists(),
                "tenant A deleted tenant B's result file: ${out}")
        assertFalse(h.rawList("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "RR_" + name]).isEmpty(),
                "tenant A deleted tenant B's run-result row: ${out}")
    }

    /**
     * The file A deletes is A's own (its legacy output folder), but the run rows deleted after it are found by the bare
     * name alone, with no tenant condition: B's run that names the same file goes too.
     */
    @Test
    void deleteOwnGeneratedOutputDoesNotDeleteForeignRunRowsWithSameName() {
        String name = "adv_shared_result.json"
        h.insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "RR_B_SHARED", companyUserGroupId: B,
                statusEnumId: "AUT_STAT_SUCCESS", resultDataManagerPath: name])
        File ownDir = h.ec.resource.getLocationReference(TenantAccessSupport.resolveGenericOutputLocation(h.ec)).getFile()
        ownDir.mkdirs()
        new File(ownDir, name).text = '{"differences":[]}'
        Map out = h.call("facade.ReconciliationFacadeServices.delete#GeneratedOutput", [fileName: name])
        assertEquals(true, out.deleted, "test precondition: A may delete its own legacy output: ${out}")
        assertFalse(h.rawList("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "RR_B_SHARED"]).isEmpty(),
                "deleting A's own file deleted tenant B's run-result row")
    }

    @Test
    void listGeneratedOutputsDoesNotListForeignBareNameAtDataManagerRoot() {
        String name = seedBRootResult("adv_b_list_result.json")
        Map out = h.call("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
        assertFalse(String.valueOf(out.generatedOutputs).contains(name), "tenant A's listing shows tenant B's result: ${out.generatedOutputs}")
    }

    // ---- a signed-in DARPAN_USER whose tenant resolves to nothing -----------------------------

    private void seedForeignExecution() {
        h.withoutReferentialIntegrity {
            h.insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "ADV_EXEC_B",
                    automationId: "ADV_AUTO_B", companyUserGroupId: B, statusEnumId: "AUT_STAT_SUCCESS",
                    errorMessage: "B-private error text", createdDate: TenantAttackHarness.FROM_DATE])
        }
    }

    /** Removed from every tenant but still a DARPAN_USER (the facade grant needs no tenant). */
    @Test
    void listAutomationExecutionsGivesAUserWithNoTenantNothing() {
        seedForeignExecution()
        h.insert("moqui.security.UserAccount", [userId: "ADV_ORPHAN", username: "adv.orphan", userFullName: "x", currentPassword: "", disabled: "N"])
        h.insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: "ADV_ORPHAN", fromDate: TenantAttackHarness.FROM_DATE])
        h.actAs("adv.orphan", null)
        Map out = h.call("facade.ReconciliationFacadeServices.list#AutomationExecutions")
        List ids = (out.executions as List ?: [])*.automationExecutionId
        assertFalse(ids.contains("ADV_EXEC_B"), "a user with no tenant listed tenant B's executions: ${out.executions}")
    }

    /** The user's only tenant was deactivated (TenantSetting.disabled = Y). */
    @Test
    void listAutomationExecutionsGivesAUserOfADisabledTenantNothing() {
        seedForeignExecution()
        String C = "ADV_TENANT_C"
        h.seedTenant(C, "Adversarial C")
        h.seedUser("ADV_C_USER", "adv.cuser", C)
        h.insert("darpan.auth.TenantSetting", [companyUserGroupId: C, disabled: "Y"])
        h.actAs("adv.cuser", C)
        Map out = h.call("facade.ReconciliationFacadeServices.list#AutomationExecutions")
        List ids = (out.executions as List ?: [])*.automationExecutionId
        assertFalse(ids.contains("ADV_EXEC_B"), "a disabled tenant's user listed tenant B's executions: ${out.executions}")
    }

    // ---- platform-wide config that every tenant reads ------------------------------------------

    @Test
    void tenantEditorCannotRewriteGlobalLlmSettings() {
        Map out = h.call("facade.SettingsFacadeServices.save#LlmSettings", [llmProvider: "OPENAI", llmApiKey: "attacker-key",
                llmBaseUrl: "https://attacker.example.invalid/v1"])
        assertNotEquals(true, out.ok, "a tenant editor saved platform LLM settings: ${out}")
    }

    @Test
    void tenantEditorCannotCreateGlobalDataSourceDefinition() {
        Map out = h.call("facade.DataSourceDefinitionFacadeServices.create#DataSourceDefinition", [definitionId: "ADV_EVIL",
                definitionName: "evil", protocol: "REST_JSON", definitionJson: "{}"])
        assertNotEquals(true, out.ok, "a tenant editor created a platform data-source definition: ${out}")
        assertTrue(h.rawList("darpan.datasource.DataSourceDefinition", [dataSourceDefinitionId: "ADV_EVIL_V1"]).isEmpty())
    }
}
