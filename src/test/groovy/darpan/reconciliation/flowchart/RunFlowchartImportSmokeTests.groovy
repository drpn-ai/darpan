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
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

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

    // A one-source NetSuite check as the Settings check wizard saves it. NETSUITE_SUITEQL and AUT_SRC_API are
    // seeded by the class setup; DftJson is mirrored from MappingSeedData.xml.
    private String nsCheck(String id, Map queryExtra = [:], boolean mapKeyColumn = true, List<String> keyFields = []) {
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: "DftJson", enumTypeId: "DarpanFileType",
                    enumCode: "JSON", description: "JSON", sequenceNum: 2]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: "IMPORT_AUTH", companyUserGroupId: "KREWE",
                    description: "Import test account"]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQuery").setAll([nsSuiteQlSourceQueryId: "${id}_Q".toString(),
                    nsAuthConfigId: "IMPORT_AUTH", companyUserGroupId: "KREWE", description: "${id} finding".toString(),
                    recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate",
                    queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", isActive: "Y"] + queryExtra).createOrUpdate()
            if (mapKeyColumn) (["internalId"] + keyFields).unique().eachWithIndex { String field, int i ->
                ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQueryField").setAll([nsSuiteQlSourceQueryId: "${id}_Q".toString(),
                        recordFieldName: field, sourceColumn: field == "internalId" ? "id" : field.toLowerCase(), sequenceNum: i + 1]).createOrUpdate()
            }
            ec.entity.makeValue("darpan.rule.RuleSet").setAll([ruleSetId: "${id}_RS".toString(), ruleSetName: "Check ${id}".toString(),
                    companyUserGroupId: "KREWE", explosionPath: "records", primaryKeyPath: "internalId"]).createOrUpdate()
            ec.entity.makeValue("darpan.rule.RuleSetCompareScope").setAll([compareScopeId: "${id}_SC".toString(), ruleSetId: "${id}_RS".toString(),
                    scopeMode: "EVALUATE", objectType: "ORDER", companyUserGroupId: "KREWE"]).createOrUpdate()
            ec.entity.makeValue("darpan.rule.RuleSetCompareSource").setAll([compareScopeId: "${id}_SC".toString(), fileSide: "FILE_1",
                    systemEnumId: "NETSUITE_SUITEQL", sourceTypeEnumId: "AUT_SRC_API", sourceConfigType: "NETSUITE_SUITEQL",
                    sourceConfigId: "${id}_Q".toString(), fileTypeEnumId: "DftJson", recordRootExpression: "records",
                    primaryIdExpression: "internalId", companyUserGroupId: "KREWE"]).createOrUpdate()
            keyFields.eachWithIndex { String field, int i ->
                ec.entity.makeValue("darpan.rule.RuleSetCompareSourceKeyField").setAll([compareScopeId: "${id}_SC".toString(),
                        fileSide: "FILE_1", sequenceNum: i + 1, fieldExpression: field, companyUserGroupId: "KREWE"]).createOrUpdate()
            }
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        return "${id}_RS".toString()
    }

    @Test
    void checksBecomeYesQuestionsUnderOneStartPerAccountAndRecordType() {
        String a = nsCheck("IMP_A_${UUID.randomUUID().toString().substring(0, 6)}")
        String b = nsCheck("IMP_B_${UUID.randomUUID().toString().substring(0, 6)}")
        Map out = call("facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns", [:])
        assertTrue(out.ok as boolean, out.errors?.toString())
        Map run = (out.runs as List).find { it.reconciliationName == "NetSuite checks: sales orders" } as Map
        assertNotNull(run)
        List questions = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: run.reconciliationId]).questions as List
        Map start = questions.find { it.questionRole == "START" } as Map
        assertNotNull(start)
        [a, b].each { String rs ->
            Map q = questions.find { it.ruleSetId == rs } as Map
            assertEquals(start.reconciliationRunId, q.parentReconciliationRunId)
            assertEquals("YES", q.parentBranch)
            assertTrue((q.noOutcomeLabel as String).endsWith("finding"))
        }
    }

    @Test
    void importingTwiceAddsNothing() {
        nsCheck("IMP_T_${UUID.randomUUID().toString().substring(0, 6)}")
        call("facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns", [:])
        Map again = call("facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns", [:])
        assertEquals(0, again.imported as int)
        List named = (call("facade.ReconciliationFacadeServices.list#Reconciliations", [:]).reconciliations as List)
                .findAll { it.reconciliationName == "NetSuite checks: sales orders" }
        assertEquals(1, named.size())
    }

    @Test
    void aCheckWithNoKeyColumnIsSkipped() {
        String suffix = UUID.randomUUID().toString().substring(0, 6)
        String rs = nsCheck("IMP_K_${suffix}", [:], false)
        String sibling = nsCheck("IMP_KV_${suffix}")
        Map out = call("facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns", [:])
        assertTrue(out.ok as boolean, out.errors?.toString())
        assertTrue((out.skipped as int) >= 1)
        assertTrue((out.messages as List).any { (it as String).contains("Check ${rs.replace('_RS', '')}") }, out.messages?.toString())
        // Every run in the tenant, not only this import's: when nothing else is new, out.runs is empty.
        List runs = call("facade.ReconciliationFacadeServices.list#Reconciliations", [:]).reconciliations as List
        List<String> placed = runs.collectMany { Map r ->
            (this.call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: r.reconciliationId]).questions as List)
                    .collect { it.ruleSetId as String }
        }
        assertTrue(placed.contains(sibling), "the import ran, and put the valid check in a run")
        assertFalse(placed.contains(rs))
    }

    @Test
    void aRecordsQueryIsAPopulationNotACheck() {
        String suffix = UUID.randomUUID().toString().substring(0, 6)
        List<String> populations = [nsCheck("IMP_R_${suffix}", [queryTemplate: "RECORDS"]),
                                    nsCheck("IMP_E_${suffix}", [queryTemplate: null]),          // empty reads as RECORDS
                                    nsCheck("IMP_L_${suffix}", [queryTemplate: "SALES_LINES"])]
        call("facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns", [:])
        List all = call("facade.ReconciliationFacadeServices.list#Reconciliations", [:]).reconciliations as List
        all.each { Map r ->
            assertFalse((this.call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: r.reconciliationId]).questions as List)
                    .any { it.ruleSetId in populations && it.questionRole != "START" })
        }
    }

    // A START that built its key differently from a check under it is the tree's "could never line up" case
    // (RunFlowchartTree I5), so a check with a composite key needs a START with that same composite key.
    @Test
    void aCheckWithKeyFieldsGetsItsOwnStartThatBuildsTheSameKey() {
        String suffix = UUID.randomUUID().toString().substring(0, 6)
        String plain = nsCheck("IMP_P_${suffix}")
        String composite = nsCheck("IMP_C_${suffix}", [:], true, ["internalId", "tranId"])
        Map out = call("facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns", [:])
        assertTrue(out.ok as boolean, out.errors?.toString())
        Map<String, String> startOf = [:]
        (out.runs as List).each { Map r ->
            List qs = this.call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: r.reconciliationId]).questions as List
            Map start = qs.find { it.questionRole == "START" } as Map
            [plain, composite].each { String rs ->
                Map q = qs.find { it.ruleSetId == rs } as Map
                if (q == null) return
                assertEquals(start.file1Key, q.file1Key)
                startOf[rs] = start.reconciliationRunId as String
            }
        }
        assertEquals(2, startOf.size(), "both checks are imported")
        assertNotEquals(startOf[plain], startOf[composite])

        Map again = call("facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns", [:])
        assertEquals(0, again.imported as int)
        List named = (call("facade.ReconciliationFacadeServices.list#Reconciliations", [:]).reconciliations as List)
                .findAll { (it.reconciliationName as String).startsWith("NetSuite checks: sales orders") }
        assertEquals(named.size(), named*.reconciliationName.unique().size(), "two runs never share a name")
    }
}
