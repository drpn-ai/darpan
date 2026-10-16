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
        assertTrue((out.reconciliationName as String).startsWith("Sales orders: shipped → billed"), out.reconciliationName as String)

        Map got = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: out.reconciliationId])
        assertEquals("STAGES", got.reconciliation.buildMode)
        assertEquals(2L, got.reconciliation.lateAfterDays as Long)
        List<Map> qs = got.questions as List<Map>
        assertEquals(4, qs.size())
        Map start = qs.find { it.questionRole == "START" }
        Map ship = qs.find { it.parentReconciliationRunId == start.reconciliationRunId }
        Map bill = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "YES" }
        Map why = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "NO" }
        assertEquals(["Sales orders", "Fulfillment", "Invoice", "Invoice"], [start, ship, bill, why]*.runName)
        assertEquals("Shipped, never billed", bill.noOutcomeLabel)
        assertEquals("Shipped and billed", bill.yesOutcomeLabel)
        assertEquals("Billed, never shipped", why.yesOutcomeLabel)
        assertEquals("Never shipped, never billed", why.noOutcomeLabel)
        assertEquals(4, (qs*.ruleSetId as Set).size(), "every question has its own rule")

        // The queries: one keys-only population, one "is it missing?" per stage, shared by questions asking it.
        ec.artifactExecution.disableAuthz()
        try {
            def startSource = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", start.ruleSetId).one()
            def source = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", startSource.compareScopeId).one()
            def startQuery = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", source.sourceConfigId).one()
            assertEquals(["RECORDS", "SalesOrd", null, "LC_AUTH"],
                    [startQuery.queryTemplate, startQuery.recordType, startQuery.absentChildRecordType, startQuery.nsAuthConfigId])
            assertEquals("EVALUATE", startSource.scopeMode)
            List queries = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsAuthConfigId", "LC_AUTH")
                    .condition("absentChildRecordType", "CustInvc").list()
            assertEquals(1, queries.size(), "bill and the skip-ahead question share one invoice query")
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
