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
        assertEquals(8, qs.size())
        Map start = qs.find { it.questionRole == "START" }
        Map ship = qs.find { it.parentReconciliationRunId == start.reconciliationRunId && it.noOutcomeLabel == null }
        // Between the stages, the excused check: only records that really shipped go on to billing.
        Map excused = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "YES" && it.questionRole == "EXCUSED" }
        Map bill = qs.find { it.parentReconciliationRunId == excused.reconciliationRunId && it.parentBranch == "YES" && it.noOutcomeLabel == "Shipped, never billed" }
        Map done = qs.find { it.parentReconciliationRunId == excused.reconciliationRunId && it.parentBranch == "YES" && it.noOutcomeLabel == "Shipped and billed" }
        Map excusedStop = qs.find { it.parentReconciliationRunId == excused.reconciliationRunId && it.parentBranch == "NO" }
        assertEquals("Fulfillment not expected, never billed", excusedStop?.noOutcomeLabel)
        Map skip = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "NO" && it.noOutcomeLabel == "Billed, never shipped" }
        Map stop = qs.find { it.parentReconciliationRunId == ship.reconciliationRunId && it.parentBranch == "NO" && it.noOutcomeLabel == "Never shipped, never billed" }
        assertEquals(["Sales orders", "Fulfillment", "Invoice", "Invoice", "Invoice", "Invoice"], [start, ship, bill, done, skip, stop]*.runName)
        assertTrue([bill, done, skip, stop].every { it != null }, "every place is one question's findings")
        assertEquals(8, (qs*.ruleSetId as Set).size(), "every question has its own rule")
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
            // The excused records' own place asks "no invoice?" too; the excused check has its own query.
            assertEquals(queryOf(bill), queryOf(excusedStop))
            def excusedQuery = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", queryOf(excused)).one()
            assertEquals(["LINKED_CHILD_EXCUSED", "ItemShip"], [excusedQuery.queryTemplate, excusedQuery.absentChildRecordType])
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

    // DAR-BE-073. A return's credit memo is found by the HC Return Id it shares with the return, not by a link: every
    // query asking about Credit memo says so, the receipt stage's queries do not, and a stage rebuild keeps it.
    @Test
    void aStageReachedByASharedFieldSaysSoOnEveryQueryAskingAboutIt() {
        List reach = [[recordType: "CustCred", startField: "custbody_hc_return_id", stageField: "custbody_hc_return_id"]]
        Map out = save([recordType: "RtnAuth", stageRecordTypes: ["ItemRcpt", "CustCred"], stageReach: reach])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        Closure<Map<String, Set>> reachByStage = {
            Map<String, Set> by = [:]
            ec.artifactExecution.disableAuthz()
            try {
                (this.call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: out.reconciliationId]).questions as List<Map>)
                        .findAll { it.questionRole != "START" && it.isActive != "N" }.each { Map q ->
                    def sc = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", q.ruleSetId).one()
                    def src = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", sc.compareScopeId).one()
                    def query = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", src.sourceConfigId).one()
                    String stage = (query.absentChildRecordType ?: query.presentChildRecordType) as String
                    by.computeIfAbsent(stage) { [] as Set }.add(query.stageReach)
                }
            } finally {
                ec.artifactExecution.enableAuthz()
            }
            return by
        }
        String expected = '[{"recordType":"CustCred","startField":"custbody_hc_return_id","stageField":"custbody_hc_return_id"}]'
        Map<String, Set> before = reachByStage()
        assertEquals([expected] as Set, before.CustCred)
        assertEquals([null] as Set, before.ItemRcpt)

        // A reorder rebuilds the stage questions; the credit memo is still found by its return id.
        call("facade.ReconciliationFacadeServices.save#LifecycleStages", [reconciliationId: out.reconciliationId, stageRecordTypes: ["CustCred", "ItemRcpt"]])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        Map<String, Set> after = reachByStage()
        assertEquals([expected] as Set, after.CustCred)
        assertEquals([null] as Set, after.ItemRcpt)
    }

    @Test
    void aReachIsRefusedForAStageNotInTheLifecycleOrOnSomethingOtherThanAFieldId() {
        long before = count()
        save([recordType: "RtnAuth", stageRecordTypes: ["ItemRcpt", "CustCred"],
              stageReach: [[recordType: "CustRfnd", startField: "custbody_hc_return_id", stageField: "custbody_hc_return_id"]]])
        assertTrue(ec.message.errors.any { it.contains("is not one of this lifecycle's stages") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        save([recordType: "RtnAuth", stageRecordTypes: ["ItemRcpt", "CustCred"],
              stageReach: [[recordType: "CustCred", startField: "x = 1 OR 1", stageField: "custbody_hc_return_id"]]])
        assertTrue(ec.message.errors.any { it.contains("matched on a field id") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        assertEquals(before, count())
    }

    // --- DAR-UI-052: a return is settled by a refund, an invoice or a replacement order; places name their records

    static final List SETTLED_REACH = [
            [recordType: "CustCred", startField: "custbody_hc_return_id", stageField: "custbody_hc_return_id"],
            [recordType: "Settled", anyOf: [
                    [recordType: "CustRfnd", startField: "custbody_hc_return_id", stageField: "custbody_hc_return_id"],
                    [recordType: "SalesOrd", stageField: "custbody_hc_exchange_credit_memo_id",
                     via: [recordType: "CustCred", startField: "custbody_hc_return_id", stageField: "custbody_hc_return_id"]]]]]

    Map saveReturns() {
        return save([recordType: "RtnAuth", stageRecordTypes: ["ItemRcpt", "CustCred", "Settled"], stageReach: SETTLED_REACH])
    }

    private List<String> settledReach(String reconciliationId) {
        ec.artifactExecution.disableAuthz()
        try {
            return (call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: reconciliationId]).questions as List<Map>)
                    .findAll { it.questionRole != "START" && it.isActive != "N" }.collect { Map q ->
                def sc = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", q.ruleSetId).one()
                def src = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", sc.compareScopeId).one()
                def query = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", src.sourceConfigId).one()
                (query.absentChildRecordType ?: query.presentChildRecordType) == "Settled" ? query.stageReach as String : null
            }.findAll { it }.unique()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    @Test
    void settledIsReachedByAnyOfItsRecordsOnEveryQueryAskingAboutIt() {
        Map out = saveReturns()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String expected = '[{"recordType":"Settled","anyOf":[{"recordType":"CustRfnd","startField":"custbody_hc_return_id",' +
                '"stageField":"custbody_hc_return_id"},{"recordType":"SalesOrd","stageField":"custbody_hc_exchange_credit_memo_id",' +
                '"via":{"recordType":"CustCred","startField":"custbody_hc_return_id","stageField":"custbody_hc_return_id"}}]}]'
        assertEquals([expected], settledReach(out.reconciliationId as String))
        // A rebuild keeps it.
        call("facade.ReconciliationFacadeServices.save#LifecycleStages", [reconciliationId: out.reconciliationId,
                stageRecordTypes: ["ItemRcpt", "CustCred", "Settled"], stageReach: SETTLED_REACH])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals([expected], settledReach(out.reconciliationId as String))
    }

    // Settled is no NetSuite type: saved without what settles it, every record would read "never settled".
    @Test
    void settledWithoutItsRecordsOrWithAnUnknownOneIsRefused() {
        long before = count()
        save([recordType: "RtnAuth", stageRecordTypes: ["ItemRcpt", "Settled"]])
        assertTrue(ec.message.errors.any { it.contains("Settlement needs the records that reach it") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        save([recordType: "RtnAuth", stageRecordTypes: ["ItemRcpt", "Settled"], stageReach: [[recordType: "Settled", anyOf: [[recordType: "Journal"]]]]])
        assertTrue(ec.message.errors.any { it.contains("'Journal' is not a record type") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        assertEquals(before, count())
    }

    private void seedConclusion(String enumId, String label) {
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId: "DarpanConclusion", description: "Run result conclusion"]).createOrUpdate()
            ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: enumId, enumTypeId: "DarpanConclusion", enumCode: enumId,
                    description: label]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    private List conclusionRows(String entity, String reconciliationId, String place) {
        ec.artifactExecution.disableAuthz()
        try {
            Map q = (call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: reconciliationId]).questions as List<Map>)
                    .find { it.isActive != "N" && it.noOutcomeLabel == place }
            def sc = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", q.ruleSetId).one()
            return ec.entity.find(entity).condition("compareScopeId", sc.compareScopeId).orderBy("sequenceNum,conditionSeq").list()
                    .findAll { true }.collect { it.getMap() }
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    @Test
    void aPlaceNamesItsRecordsByConclusionsThatSurviveARebuild() {
        seedConclusion("CONC_TEST_POS", "POS return not received in NetSuite")
        seedConclusion("CONC_TEST_EXCHANGE", "Exchange sent, goods not back")
        Map out = saveReturns()
        String rid = out.reconciliationId as String
        List conclusions = [
                [conclusionEnumId: "CONC_TEST_POS", conditions: [[field: "hcReturnChannel", operator: "IN", values: ["POS_RTN_CHANNEL"], checkLabel: "Returned at a store"]]],
                [conclusionEnumId: "CONC_TEST_EXCHANGE", conditions: [[field: "hcOutcome", values: ["Exchange"], checkLabel: "Outcome is Exchange"],
                                                                     [field: "hcReturnChannel", operator: "NOT_BLANK", checkLabel: "Has a channel"]]]]
        Map saved = call("facade.ReconciliationFacadeServices.save#LifecycleConclusions",
                [reconciliationId: rid, place: "Credited, never received", conclusions: conclusions])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(["POS return not received in NetSuite", "Exchange sent, goods not back"],
                (saved.conclusions as List<Map>).find { it.place == "Credited, never received" }.conclusions*.label)

        List rules = conclusionRows("darpan.rule.RuleSetConclusionRule", rid, "Credited, never received")
        assertEquals([[10, "CONC_TEST_POS", "FINDING", "KREWE"], [20, "CONC_TEST_EXCHANGE", "FINDING", "KREWE"]],
                rules.collect { [it.sequenceNum as int, it.conclusionEnumId, it.appliesToBucket, it.companyUserGroupId] })
        List conditions = conclusionRows("darpan.rule.RuleSetConclusionCondition", rid, "Credited, never received")
        assertEquals([[10, 1, "FILE_1", "hcReturnChannel", "IN", "POS_RTN_CHANNEL"], [20, 1, "FILE_1", "hcOutcome", "IN", "Exchange"],
                      [20, 2, "FILE_1", "hcReturnChannel", "NOT_BLANK", ""]],
                conditions.collect { [it.sequenceNum as int, it.conditionSeq as int, it.subject, it.fieldExpression, it.operator, it.conditionValues ?: ""] })

        // A rebuild makes new questions; the place of the same name names its records the same way.
        call("facade.ReconciliationFacadeServices.save#LifecycleStages", [reconciliationId: rid,
                stageRecordTypes: ["ItemRcpt", "CustCred", "Settled"], stageReach: SETTLED_REACH])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(["CONC_TEST_POS", "CONC_TEST_EXCHANGE"], conclusionRows("darpan.rule.RuleSetConclusionRule", rid, "Credited, never received")*.conclusionEnumId)
        Map got = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: rid])
        assertEquals(["Credited, never received"], (got.conclusions as List<Map>)*.place)
    }

    @Test
    void aConclusionIsRefusedForAnUnknownPlaceConclusionOrOperator() {
        seedConclusion("CONC_TEST_POS", "POS return not received in NetSuite")
        String rid = saveReturns().reconciliationId as String
        Map ok = [conclusionEnumId: "CONC_TEST_POS", conditions: [[field: "hcReturnChannel", values: ["POS_RTN_CHANNEL"], checkLabel: "At a store"]]]
        call("facade.ReconciliationFacadeServices.save#LifecycleConclusions", [reconciliationId: rid, place: "Nowhere", conclusions: [ok]])
        assertTrue(ec.message.errors.any { it.contains("is not one of this lifecycle's places") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        call("facade.ReconciliationFacadeServices.save#LifecycleConclusions", [reconciliationId: rid, place: "Credited, never received",
                conclusions: [[conclusionEnumId: "DftJson", conditions: ok.conditions]]])
        assertTrue(ec.message.errors.any { it.contains("is not a conclusion") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        call("facade.ReconciliationFacadeServices.save#LifecycleConclusions", [reconciliationId: rid, place: "Credited, never received",
                conclusions: [[conclusionEnumId: "CONC_TEST_POS", conditions: [[field: "hcReturnChannel", operator: "OLDER_THAN", values: ["30"], checkLabel: "x"]]]]])
        assertTrue(ec.message.errors.any { it.contains("A condition is one of") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        assertEquals([], conclusionRows("darpan.rule.RuleSetConclusionRule", rid, "Credited, never received"))
    }

    private long count() {
        ec.artifactExecution.disableAuthz()
        try { return ec.entity.find("darpan.reconciliation.Reconciliation").count() } finally { ec.artifactExecution.enableAuthz() }
    }
}
