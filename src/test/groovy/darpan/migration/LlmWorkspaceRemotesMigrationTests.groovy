package darpan.migration

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * LLM_WORKSPACE_REMOTES (DAR-OPS-029 S27, D19): the LLM provider settings were deleted (nothing read them), so the rows
 * they stored, an LLM API key among them, go too. Dry run counts, run deletes only those rows, a second run finds none,
 * and the registry row ships disabled.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class LlmWorkspaceRemotesMigrationTests {
    private static final String SERVICE = "migration.MigrationServices.migrate#LlmWorkspaceRemotes"
    private static final List<String> LLM_ROWS = ["OPENAI_RULE_WORKSPACE", "GEMINI_RULE_WORKSPACE", "RULE_WORKSPACE_LLM_ACTIVE"]
    private static final String KEPT_ROW = "LLMWS_TEST_UNRELATED_REMOTE"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "llm-workspace-remotes-migration")
    }

    @AfterAll
    void cleanup() {
        (LLM_ROWS + KEPT_ROW).each { remote(it)?.delete() }
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void dryRunCountsThenRunDeletesOnlyTheLlmRowsAndASecondRunFindsNone() {
        (LLM_ROWS + KEPT_ROW).each { String id ->
            ec.entity.makeValue("moqui.service.message.SystemMessageRemote").setAll([systemMessageRemoteId: id,
                    description: "test", username: "gpt-4.1-mini", password: id == KEPT_ROW ? null : "sk-test-key"]).createOrUpdate()
        }

        Map dry = call(true)
        assertEquals(3, dry.rowsUpdated as int, "the dry run counts the three rows")
        LLM_ROWS.each { assertNotNull(remote(it), "a dry run deletes nothing: ${it}") }

        Map run = call(false)
        assertEquals(3, run.rowsUpdated as int)
        LLM_ROWS.each { assertNull(remote(it), "${it} is deleted") }
        assertNotNull(remote(KEPT_ROW), "every other SystemMessageRemote stays")

        assertEquals(0, call(false).rowsUpdated as int, "idempotent: a second run finds nothing")
    }

    @Test
    void theRegistryRowShipsDisabledAndSupportsADryRun() {
        String seed = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toFile(),
                "runtime/component/darpan/data/MigrationRegistrySeedData.xml").text
        int at = seed.indexOf('migrationId="LLM_WORKSPACE_REMOTES"')
        assertTrue(at >= 0, "the registry row exists")
        String row = seed.substring(at, seed.indexOf('/>', at))
        assertTrue(row.contains('serviceName="' + SERVICE + '"'), row)
        assertTrue(row.contains('enabled="N"'), "ships disabled: " + row)
        assertTrue(row.contains('supportsDryRun="Y"'), row)
    }

    private Map call(boolean dryRun) {
        Map out = ec.service.sync().name(SERVICE).parameters([dryRun: dryRun]).disableAuthz().call()
        assertTrue(out?.ok as boolean, "${ec.message.errors}")
        return out
    }

    private def remote(String id) {
        return ec.entity.find("moqui.service.message.SystemMessageRemote").condition("systemMessageRemoteId", id)
                .disableAuthz().useCache(false).one()
    }
}
