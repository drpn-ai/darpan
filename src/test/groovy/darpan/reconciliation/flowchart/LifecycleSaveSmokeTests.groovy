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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-UI-050. save#Lifecycle turns dropdown choices into the start, the stage checks and the named question tree. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleSaveSmokeTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "lifecycle-save-smoke")
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
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: "DftJson", enumTypeId: "DarpanFileType",
                    enumCode: "JSON", description: "JSON", sequenceNum: 2]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: "LC_AUTH", companyUserGroupId: "KREWE",
                    description: "Lifecycle test account"]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        ec.message.clearErrors()
    }

    Map call(String name, Map params) {
        return ec.service.sync().name(name).parameters(params).disableAuthz().call() ?: [:]
    }

    Map save(Map params) {
        return call("facade.ReconciliationFacadeServices.save#Lifecycle",
                [nsAuthConfigId: "LC_AUTH", recordType: "SalesOrd", stageRecordTypes: ["ItemShip", "CustInvc"]] + params)
    }

    @Test
    void theOrderChainIsBuiltFromRecordTypesAlone() {
        Map out = save([lateAfterDays: 2])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertTrue((out.reconciliationName as String).startsWith("Sales Orders"), out.reconciliationName as String)

        Map got = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: out.reconciliationId])
        assertEquals("STAGES", got.reconciliation.buildMode)
        assertEquals(2L, got.reconciliation.lateAfterDays as Long)
        List<Map> qs = got.questions as List<Map>
        assertEquals(6, qs.size())
        Map start = qs.find { it.questionRole == "START" }
        Map ship = qs.find { it.parentReconciliationRunId == start.reconciliationRunId && it.noOutcomeLabel == null }
        Map bill = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "YES" && it.noOutcomeLabel == "Shipped, never billed" }
        Map done = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "YES" && it.noOutcomeLabel == "Shipped and billed" }
        Map skip = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "NO" && it.noOutcomeLabel == "Billed, never shipped" }
        Map stop = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "NO" && it.noOutcomeLabel == "Never shipped, never billed" }
        assertEquals(["Sales orders", "Fulfillment", "Invoice", "Invoice", "Invoice", "Invoice"], [start, ship, bill, done, skip, stop]*.runName)
        assertTrue([bill, done, skip, stop].every { it != null }, "every place is one question's findings")
        assertEquals(6, (qs*.ruleSetId as Set).size(), "every question has its own rule")
        // A question's records are what it finds, so its rule is named after them: that name heads the records page.
        String name = out.reconciliationName as String
        Map<String, String> ruleNames = [:]
        ec.artifactExecution.disableAuthz()
        try {
            qs.each { Map q -> ruleNames[q.reconciliationRunId as String] = ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", q.ruleSetId).one().ruleSetName }
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        assertEquals(["${name} · Sales orders", "${name} · Never shipped", "${name} · Shipped, never billed", "${name} · Shipped and billed",
                      "${name} · Billed, never shipped", "${name} · Never shipped, never billed"]*.toString(),
                [start, ship, bill, done, skip, stop].collect { ruleNames[it.reconciliationRunId as String] })

        // The queries: one keys-only population, one "is it missing?" per stage, shared by questions asking it.
        ec.artifactExecution.disableAuthz()
        try {
            def startSource = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", start.ruleSetId).one()
            def source = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", startSource.compareScopeId).one()
            def startQuery = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", source.sourceConfigId).one()
            assertEquals(["RECORDS", "SalesOrd", null, "LC_AUTH"],
                    [startQuery.queryTemplate, startQuery.recordType, startQuery.absentChildRecordType, startQuery.nsAuthConfigId])
            assertEquals("EVALUATE", startSource.scopeMode)
            // The walker's compare reads these off the source; create#RuleSetRun leaves them empty for a connector.
            assertEquals(["DftJson", "records", "internalId"], [source.fileTypeEnumId, source.recordRootExpression, source.primaryIdExpression])
            def startRuleSet = ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", start.ruleSetId).one()
            assertEquals(["records", "internalId"], [startRuleSet.explosionPath, startRuleSet.primaryKeyPath])
            def doneScope = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", done.ruleSetId).one()
            def doneSource = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", doneScope.compareScopeId).one()
            def doneQuery = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", doneSource.sourceConfigId).one()
            assertEquals(["LINKED_CHILD_PRESENT", "CustInvc", null], [doneQuery.queryTemplate, doneQuery.presentChildRecordType, doneQuery.absentChildRecordType])
            Closure<String> queryOf = { Map q ->
                def sc = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", q.ruleSetId).one()
                ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", sc.compareScopeId).one().sourceConfigId as String
            }
            // "No invoice?" is asked by bill and stop, "has an invoice?" by done and skip: one query each.
            assertEquals(queryOf(bill), queryOf(stop))
            assertEquals(queryOf(done), queryOf(skip))
            assertTrue(queryOf(bill) != queryOf(done))
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    @Test
    void aLifecyclesRulesStayOutOfTheSavedRunList() {
        Map out = save([:])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        Set<String> owned = (call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: out.reconciliationId])
                .questions as List<Map>)*.ruleSetId as Set<String>
        List listed = call("facade.ReconciliationFacadeServices.list#SavedRuns", [pageIndex: 0, pageSize: 200]).savedRuns as List
        assertTrue(listed*.savedRunId.intersect(owned).isEmpty(), "owned rules leaked into the run list")
    }

    @Test
    void theSameStagesTwiceGetTwoNames() {
        String first = save([:]).reconciliationName
        String second = save([:]).reconciliationName
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertTrue(first != second && second.endsWith(")"), "${first} / ${second}")
    }

    @Test
    void anAccountTheTenantCannotUseIsRefusedAndNothingIsWritten() {
        long before = count()
        Map out = save([nsAuthConfigId: "NOT_MINE"])
        assertTrue(ec.message.hasError())
        assertNull(out.reconciliationId)
        ec.message.clearErrors()
        assertEquals(before, count())
    }

    @Test
    void aRecordTypeOutsideTheListIsRefused() {
        save([stageRecordTypes: ["ItemShip", "X' OR 1=1"]])
        assertTrue(ec.message.errors.any { it.contains("is not a record type") }, ec.message.errors?.toString())
        ec.message.clearErrors()
    }

    private long count() {
        ec.artifactExecution.disableAuthz()
        try { return ec.entity.find("darpan.reconciliation.Reconciliation").count() } finally { ec.artifactExecution.enableAuthz() }
    }
}
