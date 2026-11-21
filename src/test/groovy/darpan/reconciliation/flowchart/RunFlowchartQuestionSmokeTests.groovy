package darpan.reconciliation.flowchart

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-UI-048. One question through the real pipeline with a parent's keys, from the internal entry point. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunFlowchartQuestionSmokeTests {

    private ExecutionContext ec
    @TempDir Path tmp

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "run-flowchart-question-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        // The DftCsv file-type enum and the other shared reconciliation enums create#RuleSetRun validates.
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

    private String csvRuleSet() {
        Map created = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                runName: "Flowchart question ${UUID.randomUUID()}".toString(),
                file1SystemEnumId: "OMS", file1FileTypeEnumId: "DftCsv", file1PrimaryIdExpression: "order_id",
                file2SystemEnumId: "SHOPIFY", file2FileTypeEnumId: "DftCsv", file2PrimaryIdExpression: "order_id",
                rules: []]).disableAuthz().call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return created.savedRun.savedRunId as String
    }

    @Test
    void aQuestionAsksOnlyAboutItsParentsKeysAndSaysWhichItAsked() {
        String savedRunId = csvRuleSet()
        // ReconciliationRunResult.reconciliationRunId is a foreign key (RECRES_RUN), so the question exists first.
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationRun")
                .setAll([reconciliationRunId: "Q_1", ruleSetId: savedRunId, companyUserGroupId: "KREWE"]).createOrUpdate()
        ec.artifactExecution.enableAuthz()
        File include = tmp.resolve("parent-yes.txt").toFile()
        QuestionKeyFiles.write(include, ["A100", "A200"])
        File keysOut = tmp.resolve("q-file1-keys.txt").toFile()

        Map out = ec.service.sync().name("reconciliation.ReconciliationFlowchartServices.run#FlowchartQuestion").parameters([
                savedRunId: savedRunId,
                file1Name: "f1.csv", file1Text: "order_id\nA100\nA200\nA300\n",
                file2Name: "f2.csv", file2Text: "order_id\nA200\nA300\n",
                hasHeader: true,
                file1IncludeIdsLocation: include.absolutePath,
                file1KeysOutLocation: keysOut.absolutePath,
                reconciliationExecutionId: "EXEC_1", reconciliationRunId: "Q_1", parentRunResultId: "PARENT_1",
        ]).disableAuthz().call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        // FILE_1 kept A100, A200: A100 is missing in FILE_2, and FILE_2's A300 is now missing in FILE_1.
        // Unrestricted, FILE_1 would have held A300 too and the count would be 1 — so 2 proves the filter ran.
        assertEquals(2L, out.runResult.generatedOutput.totalDifferences as Long)
        assertEquals(["A100", "A200"] as Set, QuestionKeyFiles.read(keysOut))

        def row = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", out.runResult.reconciliationRunResultId as String)
                .disableAuthz().useCache(false).one()
        assertEquals("EXEC_1", row.reconciliationExecutionId)
        assertEquals("Q_1", row.reconciliationRunId)
        assertEquals("PARENT_1", row.parentRunResultId)
        assertEquals("AUT_STAT_SUCCESS", row.statusEnumId)
    }

    @Test
    void theFacadePathIsUnchanged() {
        String savedRunId = csvRuleSet()
        Map out = ec.service.sync().name("facade.ReconciliationFacadeServices.run#SavedRunDiff").parameters([
                savedRunId: savedRunId,
                file1Name: "f1.csv", file1Text: "order_id\nA100\nA200\nA300\n",
                file2Name: "f2.csv", file2Text: "order_id\nA200\nA300\nA400\n",
                hasHeader: true]).disableAuthz().call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(2L, out.runResult.generatedOutput.totalDifferences as Long)
        assertTrue(((List) out.runResult.processingWarnings).isEmpty())
    }
}
