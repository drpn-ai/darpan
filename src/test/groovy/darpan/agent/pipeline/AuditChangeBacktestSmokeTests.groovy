package darpan.agent.pipeline

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-159. Before a person approves an agent's change, Darpan shows which open findings it would close and which
 * records would newly raise one, from data already landed: the latest succeeded execution's extract and result
 * document, or the findings ledger itself. Nothing is read live and nothing is written. Tenants and rows are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditChangeBacktestSmokeTests {

    private static final String MINE = "KREWE"
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String USERNAME = "test.customer"
    private static final String AUTO = "TBT_AUTO"
    private static final String AUTO2 = "TBT_AUTO2"
    private static final String CHECK = "TBT_CHK"
    private static final String CHECK2 = "TBT_CHK2"
    private static final String CONNECTOR = "darpan.reconciliation.SourceSystemConnector"
    private static final String FINDING = "darpan.audit.AuditFinding"
    private static final String EXECUTION = "darpan.reconciliation.ReconciliationAutomationExecution"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String RUNS = "reconciliation-runs"

    private ExecutionContext ec
    private String runId
    private String scopeId
    private String run2Id
    private String scope2Id

    /** The records the basis execution read, after the run's own filter (memoOrigin HOTWAX). */
    private static final List<Map> RECORDS = [
            [tranId: "CM-1", memoOrigin: "HOTWAX", status: "Open"],
            [tranId: "CM-2", memoOrigin: "HOTWAX", status: "Closed"],
            [tranId: "CM-3", memoOrigin: "HOTWAX", status: "Voided"]].asImmutable()
    /** The two-sided run: memos (FILE_1) against applications (FILE_2); CM-4 has no application. */
    private static final List<Map> MEMOS = [[tranId: "CM-1", memoOrigin: "HOTWAX"], [tranId: "CM-2", memoOrigin: "HOTWAX"],
                                            [tranId: "CM-4", memoOrigin: "HOTWAX"]].asImmutable()
    private static final List<Map> APPLICATIONS = [[tranId: "CM-1", status: "Applied"], [tranId: "CM-2", status: "Applied"]].asImmutable()

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-change-backtest")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/SystemMessageRemoteSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: MINE, userId: USER,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
            upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
            ["CONC_TBT_A": "Memo left open", "CONC_TBT_B": "Store kept the item"].each { String id, String label ->
                upsert("moqui.basic.Enumeration", [enumId: id, enumTypeId: "DarpanConclusion", enumCode: id, description: label])
            }
            upsert("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TBT_AUTH", companyUserGroupId: MINE, authType: "NONE",
                    isActive: "Y", description: "test"])
            ["TBT_Q": "open credit memos", "TBT_Q2": "credit memo applications"].each { String q, String label ->
                upsert("darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: q, nsAuthConfigId: "TBT_AUTH",
                        recordType: "CustCred", companyUserGroupId: MINE, isActive: "Y", description: label])
                [["tranId", "tranid"], ["memoOrigin", "memoorigin"], ["status", "status"]].eachWithIndex { List f, int i ->
                    upsert("darpan.reconciliation.NsSuiteQlSourceQueryField", [nsSuiteQlSourceQueryId: q, recordFieldName: f[0],
                            sourceColumn: f[1], sequenceNum: (i + 1) as Long])
                }
            }
        }
        actAs(MINE)
        Map run = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                runName: "Open credit memos", scopeMode: "EVALUATE",
                file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API", file1SourceConfigId: "TBT_Q",
                file1SourceConfigType: "NETSUITE_SUITEQL", file1PrimaryIdExpression: '$.records[*].tranId', rules: []]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        runId = run.savedRun.savedRunId as String
        scopeId = run.savedRun.compareScopeId as String
        Map run2 = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                runName: "Memos against applications", scopeMode: "COMPARE",
                file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API", file1SourceConfigId: "TBT_Q",
                file1SourceConfigType: "NETSUITE_SUITEQL", file1PrimaryIdExpression: '$.records[*].tranId',
                file2SystemEnumId: "NETSUITE_SUITEQL", file2SourceTypeEnumId: "AUT_SRC_API", file2SourceConfigId: "TBT_Q2",
                file2SourceConfigType: "NETSUITE_SUITEQL", file2PrimaryIdExpression: '$.records[*].tranId', rules: []]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        run2Id = run2.savedRun.savedRunId as String
        scope2Id = run2.savedRun.compareScopeId as String
        asSystem {
            upsert("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: scopeId, fileSide: "FILE_1", sequenceNum: 1L,
                    fieldExpression: '$.records[*].memoOrigin', operator: "INCLUDE_IN", filterValues: "HOTWAX", companyUserGroupId: MINE])
            upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTO, automationName: "credit memos",
                    companyUserGroupId: MINE, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: runId, savedRunType: "ruleset",
                    ruleSetId: runId, compareScopeId: scopeId, isActive: "N", relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY",
                    windowTimeZone: "UTC", scheduleExpr: "0 0 6 * * ?"])
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTO, fileSide: "FILE_1",
                    companyUserGroupId: MINE, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                    safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"TBT_Q"}}'])
            upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTO2, automationName: "memos against applications",
                    companyUserGroupId: MINE, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: run2Id, savedRunType: "ruleset",
                    ruleSetId: run2Id, compareScopeId: scope2Id, isActive: "N", relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY",
                    windowTimeZone: "UTC", scheduleExpr: "0 0 6 * * ?"])
            ["FILE_1": "TBT_Q", "FILE_2": "TBT_Q2"].each { String side, String q ->
                upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTO2, fileSide: side,
                        companyUserGroupId: MINE, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                        safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"' + q + '"}}'])
            }
            upsert("darpan.audit.Audit", [auditId: "${MINE}_TBT".toString(), companyUserGroupId: MINE, auditCode: "month-close-tbt",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            ["R1", "R2", "R3", "R4"].eachWithIndex { String code, int i ->
                upsert("darpan.audit.AuditStatement", [statementId: "TBT_${code}".toString(), companyUserGroupId: MINE,
                        auditId: "${MINE}_TBT".toString(), statementCode: code, categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM",
                        sequenceNum: (i + 1) as Long, statementText: "${code} text".toString(), isActive: "Y"])
            }
            upsert("darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: MINE, checkCode: "credits-open-tbt",
                    label: "Open credit memos", savedRunId: runId, automationId: AUTO, graceDays: 1L, headlineTemplate: "Memo {tranId} is open"])
            // R1 counts every row; R2 only the rows concluded "the store kept the item".
            upsert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "TBT_R1", companyUserGroupId: MINE,
                    sequenceNum: 1L, diffTypes: "FINDING"])
            upsert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "TBT_R2", companyUserGroupId: MINE,
                    sequenceNum: 1L, diffTypes: "FINDING", conclusionCodes: "CONC_TBT_B"])
            // The two-sided check: R3 counts a memo with no application row on file 1's side missing; R4 a mismatch or a
            // memo with no application.
            upsert("darpan.audit.AuditCheck", [checkId: CHECK2, companyUserGroupId: MINE, checkCode: "memos-applied-tbt",
                    label: "Memos applied", savedRunId: run2Id, automationId: AUTO2, graceDays: 1L, headlineTemplate: "Memo {tranId}"])
            upsert("darpan.audit.AuditStatementCheck", [checkId: CHECK2, statementId: "TBT_R3", companyUserGroupId: MINE,
                    sequenceNum: 1L, diffTypes: "MISSING_IN_FILE_1"])
            upsert("darpan.audit.AuditStatementCheck", [checkId: CHECK2, statementId: "TBT_R4", companyUserGroupId: MINE,
                    sequenceNum: 1L, diffTypes: "FIELD_MISMATCH,MISSING_IN_FILE_2"])
        }
        // A memo still Open or Pending is left open; any other memo with an origin, the store kept the item.
        ec.service.sync().name("facade.RunConclusionFacadeServices.save#RunConclusions").parameters([savedRunId: runId,
                conclusions: [
                        [key: "1", conclusion: "CONC_TBT_A", bucket: "FINDING", fix: "Re-sync the memo",
                         conditions: [[field: "status", operator: "IN", values: ["Open", "Pending"], checkLabel: "Memo is open"]]],
                        [key: "2", conclusion: "CONC_TBT_B", bucket: "FINDING", fix: "Ask the store",
                         conditions: [[field: "memoOrigin", operator: "NOT_BLANK", checkLabel: "Has an origin"]]]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)

        asSystem {
            // The basis: succeeded, its extract and result still on disk.
            execution("TBT_EXEC1", "AUT_STAT_SUCCESS", "2026-11-02 06:10:00", true)
            // Newer and succeeded, but its files are gone: not a basis.
            execution("TBT_EXEC2", "AUT_STAT_SUCCESS", "2026-11-03 06:10:00", false)
            // Newer still with its files, but failed: never a basis.
            execution("TBT_EXEC3", "AUT_STAT_FAILED", "2026-11-03 07:10:00", true)
            twoSidedExecution("TBT_EXEC21")
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /** Five open findings and one fixed one: R1 for CM-1, CM-2, CM-3; R2 for CM-2, CM-3 (concluded CONC_TBT_B). */
    @BeforeEach
    void reset() {
        actAs(MINE)
        asSystem {
            ec.entity.find(FINDING).condition("companyUserGroupId", MINE).deleteAll()
            finding("F-TBT1", "TBT_R1", "CM-1", "AUDFS_OPEN", "CONC_TBT_A")
            finding("F-TBT2", "TBT_R1", "CM-2", "AUDFS_OPEN", "CONC_TBT_B")
            finding("F-TBT3", "TBT_R2", "CM-2", "AUDFS_OPEN", "CONC_TBT_B")
            finding("F-TBT4", "TBT_R1", "CM-3", "AUDFS_PENDING", "CONC_TBT_B")
            finding("F-TBT5", "TBT_R2", "CM-3", "AUDFS_OPEN", "CONC_TBT_B")
            finding("F-TBT6", "TBT_R1", "CM-2", "AUDFS_FIXED", "CONC_TBT_B", "2026-09")
            // Open, but in the period before the basis window: no basis of this month may close it.
            finding("F-TBT7", "TBT_R1", "CM-2", "AUDFS_OPEN", "CONC_TBT_B", "2026-10")
            finding("F-TBT21", "TBT_R4", "CM-1", "AUDFS_OPEN", null, "2026-11", CHECK2)
            finding("F-TBT22", "TBT_R4", "CM-4", "AUDFS_OPEN", null, "2026-11", CHECK2)
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", CHECK2).condition("statementId", "TBT_R3")
                    .one().set("conclusionCodes", null).update()
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", CHECK2).condition("statementId", "TBT_R4")
                    .one().setAll([diffTypes: "FIELD_MISMATCH,MISSING_IN_FILE_2", conclusionCodes: null]).update()
        }
        ec.message.clearAll()
    }

    @Test
    void "exclusion backtest lists the findings it would close and writes nothing"() {
        Map target = [savedRunId: runId, fileSide: "FILE_1"]
        Map merged = AuditChangeLevers.merge("EXCLUSION_FILTER", AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target),
                [fieldExpression: '$.records[*].tranId', filterValues: ["CM-2"]])
        Map before = snapshot()

        Map out = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, merged)

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(true, out.supported, out.toString())
        assertEquals("TBT_EXEC1", out.basisExecutionId, "the latest succeeded execution that still has its extract")
        assertEquals(["F-TBT2", "F-TBT3"], out.closed, "both open findings of CM-2 this month; not the fixed one, not October's")
        assertEquals([], out.opened)
        assertEquals(4, out.unchanged)
        assertEquals(["CLOSES_ONLY", "LATEST_WINDOW_ONLY"], out.limits)
        assertEquals(before, snapshot(), "no finding, execution, ledger row, pending mark or file was written")

        Map voided = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, AuditChangeLevers.merge("EXCLUSION_FILTER",
                AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target), [fieldExpression: '$.records[*].status', filterValues: ["voided"]]))
        assertEquals(["F-TBT4", "F-TBT5"], voided.closed, "values match without case, as the extract's own filter does; a pending finding closes too")
        assertEquals(before, snapshot())

        // R51: an agent exclusion never loosens (merge refuses another operator), but a revert's restored state may.
        Map widerState = (Map) AuditChangeLevers.copy(AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target))
        ((List<Map>) ((Map) widerState.filters).FILE_1).find { it.fieldExpression == '$.records[*].memoOrigin' }.filterValues = ["HOTWAX", "STORE"]
        Map wider = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, widerState)
        assertTrue("WIDENING_NOT_COMPUTED" in wider.limits, "an include that lists more lets records back in: ${wider}")

        Map other = AuditChangeBacktest.run(ec, "TBT_OTHER_TENANT", "EXCLUSION_FILTER", target, merged)
        assertEquals([supported: false, reason: "TENANT_MISMATCH", basisExecutionId: null, closed: [], opened: [], unchanged: 0, limits: []], other,
                "only the session's active tenant is ever read")
    }

    @Test
    void "no landed extract reports NO_LANDED_EXTRACT"() {
        File extract = landed("TBT_EXEC1", "memos.json")
        File aside = new File(extract.parentFile, "memos.json.moved")
        assertTrue(extract.renameTo(aside))
        try {
            Map target = [savedRunId: runId, fileSide: "FILE_1"]
            Map merged = AuditChangeLevers.merge("EXCLUSION_FILTER", AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target),
                    [fieldExpression: '$.records[*].tranId', filterValues: ["CM-2"]])
            Map out = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, merged)
            assertEquals(false, out.supported)
            assertEquals("NO_LANDED_EXTRACT", out.reason, "a failed execution's files are never the basis")
            assertNull(out.basisExecutionId)
            assertEquals([], out.closed)
            assertEquals([], out.opened)
            assertEquals(0, out.unchanged)
        } finally {
            assertTrue(aside.renameTo(extract))
        }
    }

    @Test
    void "statement narrowing closes findings outside the new conclusion codes"() {
        Map target = [auditId: "${MINE}_TBT".toString(), checkCode: "credits-open-tbt", statementCode: "R1"]
        Map merged = AuditChangeLevers.merge("STATEMENT_NARROWING", AuditChangeLevers.current(ec, "STATEMENT_NARROWING", target),
                [conclusionCodes: ["CONC_TBT_A"]])
        Map before = snapshot()

        Map out = AuditChangeBacktest.run(ec, MINE, "STATEMENT_NARROWING", target, merged)

        assertEquals(true, out.supported, out.toString())
        assertNull(out.basisExecutionId, "the findings ledger alone")
        assertEquals(["F-TBT2", "F-TBT4", "F-TBT7"], out.closed, "R1's open findings concluded other than CONC_TBT_A, any period")
        assertEquals([], out.opened)
        assertEquals(1, out.unchanged, "R1's CM-1 stays")
        assertEquals(["CLOSES_ONLY"], out.limits)
        assertEquals(before, snapshot())

        Map r2 = [auditId: "${MINE}_TBT".toString(), checkCode: "credits-open-tbt", statementCode: "R2"]
        Map wider = AuditChangeBacktest.run(ec, MINE, "STATEMENT_NARROWING", r2, AuditChangeLevers.merge("STATEMENT_NARROWING",
                AuditChangeLevers.current(ec, "STATEMENT_NARROWING", r2), [conclusionCodes: ["CONC_TBT_B", "CONC_TBT_A"]]))
        assertEquals(["CLOSES_ONLY", "WIDENING_NOT_COMPUTED"], wider.limits, "adding a code lets findings in")

        Map byType = AuditChangeBacktest.run(ec, MINE, "STATEMENT_NARROWING", target, AuditChangeLevers.merge("STATEMENT_NARROWING",
                AuditChangeLevers.current(ec, "STATEMENT_NARROWING", target), [diffTypes: ["MISSING_IN_FILE_2"]]))
        assertEquals(false, byType.supported, "a finding does not record its diff type")
        assertEquals("DIFF_TYPE_NOT_ON_FINDINGS", byType.reason)
    }

    @Test
    void "conclusion rows backtest reports a changed conclusion"() {
        Map target = [savedRunId: runId]
        // "Left open" now means Closed: CM-1 (Open) falls to "the store kept the item", CM-2 (Closed) leaves it.
        Map merged = AuditChangeLevers.merge("CONCLUSION_ROWS", AuditChangeLevers.current(ec, "CONCLUSION_ROWS", target),
                [rows: [[conclusionCode: "CONC_TBT_A", conditions: [[fieldExpression: "status", operator: "IN", checkValue: "Closed"]]]]])
        Map before = snapshot()

        Map out = AuditChangeBacktest.run(ec, MINE, "CONCLUSION_ROWS", target, merged)

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(true, out.supported, out.toString())
        assertEquals("TBT_EXEC1", out.basisExecutionId)
        assertEquals(["F-TBT3"], out.closed, "R2 no longer counts CM-2; R1 counts any conclusion, so F-TBT2 stays")
        assertEquals(["CM-1"], out.opened, "R2 would newly count CM-1")
        assertEquals(5, out.unchanged)
        assertEquals(["LATEST_WINDOW_ONLY"], out.limits)
        assertEquals(before, snapshot())
    }

    @Test
    void "fix text is display only"() {
        Map target = [savedRunId: runId, conclusionCode: "CONC_TBT_A"]
        Map merged = AuditChangeLevers.merge("FIX_TEXT", AuditChangeLevers.current(ec, "FIX_TEXT", target), [fixActionTemplate: "Re-import the memo"])
        Map out = AuditChangeBacktest.run(ec, MINE, "FIX_TEXT", target, merged)
        assertEquals([supported: true, reason: "DISPLAY_ONLY", basisExecutionId: null, closed: [], opened: [], unchanged: 0, limits: []], out)
    }

    @Test
    void "a two-sided exclusion opens the other side's record it leaves unmatched"() {
        Map target = [savedRunId: run2Id, fileSide: "FILE_1"]
        Map merged = AuditChangeLevers.merge("EXCLUSION_FILTER", AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target),
                [fieldExpression: '$.records[*].tranId', filterValues: ["CM-1", "CM-4"]])
        Map before = snapshot()

        Map out = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, merged)

        assertEquals(true, out.supported, out.toString())
        assertNull(out.reason)
        assertEquals("TBT_EXEC21", out.basisExecutionId)
        assertEquals(["F-TBT21", "F-TBT22"], out.closed, "R4 counts neither a dropped memo nor its application left alone")
        assertEquals(["CM-1"], out.opened, "CM-1's application is now missing on file 1's side, which R3 counts")
        assertEquals(["LATEST_WINDOW_ONLY"], out.limits)
        assertEquals(before, snapshot())

        asSystem {
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", CHECK2).condition("statementId", "TBT_R3")
                    .one().set("conclusionCodes", "CONC_TBT_A").update()
        }
        Map undecided = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, merged)
        assertEquals(true, undecided.supported, "closings still hold")
        assertEquals("OPENED_NOT_COMPUTED", undecided.reason, "R3 also asks for a conclusion the new row has not reached")
        assertEquals(["F-TBT21", "F-TBT22"], undecided.closed)
        assertEquals([], undecided.opened)
        assertTrue("OPENED_NOT_COMPUTED" in undecided.limits)
    }

    @Test
    void "an undecided link never lists the finding the new missing row may keep"() {
        // R4 now also counts a memo whose application stands alone, but only for one conclusion.
        asSystem {
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", CHECK2).condition("statementId", "TBT_R4")
                    .one().setAll([diffTypes: "FIELD_MISMATCH,MISSING_IN_FILE_1", conclusionCodes: "CONC_TBT_A"]).update()
        }
        Map target = [savedRunId: run2Id, fileSide: "FILE_1"]
        Map out = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, AuditChangeLevers.merge("EXCLUSION_FILTER",
                AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target), [fieldExpression: '$.records[*].tranId', filterValues: ["CM-1", "CM-4"]]))
        assertEquals(true, out.supported, out.toString())
        assertEquals(["F-TBT22"], out.closed, "F-TBT21 (CM-1, whose application the other side holds) may stay open under R4")
        assertEquals("OPENED_NOT_COMPUTED", out.reason)
        assertTrue("OPENED_NOT_COMPUTED" in out.limits)
        assertEquals(["CM-1"], out.opened, "R3 still counts it with any conclusion")
    }

    @Test
    void "an exclusion the extract would not take is refused as the extract refuses it"() {
        Map target = [savedRunId: runId, fileSide: "FILE_1"]
        Closure<Map> backtest = { String field ->
            AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, AuditChangeLevers.merge("EXCLUSION_FILTER",
                    AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target), [fieldExpression: field, filterValues: ["CM-2"]]))
        }
        withConnector([filterParameterName: null]) {
            assertEquals("SOURCE_TAKES_NO_FILTERS", backtest('$.records[*].tranId').reason)
        }
        asSystem {
            upsert("darpan.datasource.DataSourceDefinition", [dataSourceDefinitionId: "TBT_DEF", definitionName: "memos", protocol: "GRAPHQL",
                    definitionVersion: 1L, statusId: "ACTIVE",
                    definitionJson: JsonOutput.toJson([steps: [[id: "exclusions", kind: "filter", fields: ["tranId", "memoOrigin", "batch"]]]])])
            upsert("darpan.datasource.DataSourceDefinition", [dataSourceDefinitionId: "TBT_DEF_NONE", definitionName: "memos", protocol: "GRAPHQL",
                    definitionVersion: 1L, statusId: "ACTIVE", definitionJson: JsonOutput.toJson([steps: [[id: "read", kind: "query"]]])])
        }
        withConnector([extractServiceName: "reconciliation.DataDefinedExtractionServices.extract#DataDefinedSource",
                       dataSourceDefinitionId: "TBT_DEF"]) {
            Map ok = backtest('$.records[*].tranId')
            assertEquals(true, ok.supported, ok.toString())
            assertEquals(["F-TBT2", "F-TBT3"], ok.closed)
            assertEquals("FILTER_FIELD_NOT_OFFERED", backtest('$.records[*].status').reason, "the filter step does not offer status")
            assertEquals("FIELD_NOT_IN_LANDED_RECORDS", backtest('$.records[*].batch').reason, "offered, but no landed record carries it")
            File extract = landed("TBT_EXEC1", "memos.json")
            String whole = extract.text
            extract.text = JsonOutput.toJson([metadata: [recordCount: 0], records: []])
            try {
                Map empty = backtest('$.records[*].batch')
                assertEquals(true, empty.supported, "an empty window drops nothing, which is an answer: ${empty}")
                assertEquals([], empty.closed)
                assertEquals("TBT_EXEC1", empty.basisExecutionId)
                assertTrue("LATEST_WINDOW_ONLY" in empty.limits)
            } finally {
                extract.text = whole
            }
        }
        withConnector([extractServiceName: "reconciliation.DataDefinedExtractionServices.extract#DataDefinedSource",
                       dataSourceDefinitionId: "TBT_DEF_NONE"]) {
            assertEquals("SOURCE_TAKES_NO_FILTERS", backtest('$.records[*].tranId').reason, "a definition with no filter step")
        }
    }

    @Test
    void "a truncated landed extract is a failed backtest, not a failed plan"() {
        File extract = landed("TBT_EXEC1", "memos.json")
        String whole = extract.text
        extract.text = whole.substring(0, whole.length() - 20)
        try {
            Map target = [savedRunId: runId, fileSide: "FILE_1"]
            Map out = AuditChangeBacktest.run(ec, MINE, "EXCLUSION_FILTER", target, AuditChangeLevers.merge("EXCLUSION_FILTER",
                    AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target), [fieldExpression: '$.records[*].tranId', filterValues: ["CM-2"]]))
            assertEquals(false, out.supported)
            assertEquals("BACKTEST_FAILED", out.reason, out.toString())
            assertNotNull(out.detail)
            assertEquals([], out.closed)
        } finally {
            extract.text = whole
        }
    }

    // ------------------------------------------------------------------ fixtures

    private void execution(String id, String status, String completed, boolean withFiles) {
        String dir = "${RUNS}/${id}/20261102-060000000"
        String extractPath = "${dir}/memos.json"
        String resultPath = "${dir}/${id}-result.json"
        upsert(EXECUTION, [automationExecutionId: id, automationId: AUTO, companyUserGroupId: MINE, statusEnumId: status,
                completedDate: Timestamp.valueOf(completed), createdDate: Timestamp.valueOf(completed),
                windowStartDate: Timestamp.valueOf("2026-11-01 00:00:00"), windowEndDate: Timestamp.valueOf("2026-11-02 00:00:00"),
                file1Name: "memos.json", file1DataManagerPath: extractPath, file1RecordCount: RECORDS.size() as Long,
                resultFileName: "${id}-result.json".toString(), resultDataManagerPath: resultPath])
        File extract = DataManagerSupport.resolveDataManagerFile(ec, extractPath, true)
        File result = DataManagerSupport.resolveDataManagerFile(ec, resultPath, true)
        if (!withFiles) {
            extract.delete(); result.delete()
            return
        }
        extract.text = JsonOutput.toJson([metadata: [recordCount: RECORDS.size()], records: RECORDS])
        // As the conclude pass left it: one row per line, each already concluded under the saved tree.
        Map<String, String> concluded = ["CM-1": "CONC_TBT_A", "CM-2": "CONC_TBT_B", "CM-3": "CONC_TBT_B"]
        List<String> rows = RECORDS.collect { Map r ->
            JsonOutput.toJson([diffType: "FINDING", primaryId: r.tranId, data: r, conclusion: [code: concluded[r.tranId as String]]])
        }
        result.text = '{\n"metadata":{"file1Label":"NetSuite"},\n"summary":{"totalDifferences":3},\n"differences":[\n' +
                rows.join(",\n") + "]\n}\n"
    }

    private File landed(String executionId, String name) {
        return DataManagerSupport.resolveDataManagerFile(ec, "${RUNS}/${executionId}/20261102-060000000/${name}".toString(), false)
    }

    private void twoSidedExecution(String id) {
        String dir = "${RUNS}/${id}/20261102-060000000"
        upsert(EXECUTION, [automationExecutionId: id, automationId: AUTO2, companyUserGroupId: MINE, statusEnumId: "AUT_STAT_SUCCESS",
                completedDate: Timestamp.valueOf("2026-11-02 06:10:00"), createdDate: Timestamp.valueOf("2026-11-02 06:10:00"),
                windowStartDate: Timestamp.valueOf("2026-11-01 00:00:00"), windowEndDate: Timestamp.valueOf("2026-11-02 00:00:00"),
                file1Name: "memos.json", file1DataManagerPath: "${dir}/memos.json".toString(),
                file2Name: "applications.json", file2DataManagerPath: "${dir}/applications.json".toString(),
                resultFileName: "${id}-result.json".toString(), resultDataManagerPath: "${dir}/${id}-result.json".toString()])
        DataManagerSupport.resolveDataManagerFile(ec, "${dir}/memos.json", true).text = JsonOutput.toJson([records: MEMOS])
        DataManagerSupport.resolveDataManagerFile(ec, "${dir}/applications.json", true).text = JsonOutput.toJson([records: APPLICATIONS])
        String row = JsonOutput.toJson([diffType: "MISSING_IN_FILE_2", primaryId: "CM-4", data: MEMOS[2]])
        DataManagerSupport.resolveDataManagerFile(ec, "${dir}/${id}-result.json", true).text =
                '{\n"metadata":{"file1Label":"Memos","file2Label":"Applications"},\n"summary":{"totalDifferences":1},\n"differences":[\n' + row + "]\n}\n"
    }

    /** The run's NETSUITE_SUITEQL connector with these fields for the closure, then as it was. */
    private void withConnector(Map fields, Closure work) {
        Map was = asSystem {
            def row = ec.entity.find(CONNECTOR).condition("systemEnumId", "NETSUITE_SUITEQL").one()
            Map before = fields.keySet().collectEntries { [(it): row.get(it)] }
            row.setAll(fields).update()
            before
        }
        try {
            work.call()
        } finally {
            asSystem { ec.entity.find(CONNECTOR).condition("systemEnumId", "NETSUITE_SUITEQL").one().setAll(was).update() }
        }
    }

    private void finding(String id, String statementId, String recordKey, String state, String conclusion, String period = "2026-11",
                         String checkId = CHECK) {
        upsert(FINDING, [findingId: id, companyUserGroupId: MINE, statementId: statementId, checkId: checkId, recordKey: recordKey,
                period: period, stateEnumId: state, headline: "Memo ${recordKey} is open".toString(),
                firstFoundDate: Date.valueOf("2026-11-01"), lastSeenDate: Date.valueOf("2026-11-02"), timesSeen: 1L,
                conclusionEnumId: conclusion])
    }

    /** Every row and file the backtest must leave alone. */
    private Map snapshot() {
        Map rows = asSystem {
            [findings  : ec.entity.find(FINDING).condition("companyUserGroupId", MINE).orderBy("findingId").useCache(false).list()
                    .collect { [it.findingId, it.stateEnumId, it.lastSeenDate, it.timesSeen, it.closedStamp, it.conclusionEnumId] },
             executions: ec.entity.find(EXECUTION).orderBy("automationExecutionId").useCache(false).list()
                    .collect { [it.automationExecutionId, it.statusEnumId, it.lastUpdatedDate] },
             ledger    : ["darpan.audit.AuditCheckRun", "darpan.audit.AuditFindingEvent", "darpan.audit.AuditFindingRecord",
                          "darpan.audit.AuditFindingCondition", "darpan.config.TenantConfigPending", "darpan.config.TenantConfigVersion",
                          "darpan.reconciliation.ReconciliationRunStep", "darpan.reconciliation.ReconciliationRunResult",
                          "darpan.rule.RuleSetConclusionRule", "darpan.rule.RuleSetCompareSourceFilter"]
                    .collectEntries { [(it): ec.entity.find(it).count()] }]
        }
        File root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), false)
        List<String> files = []
        root?.eachFileRecurse { File f -> if (f.isFile()) files << "${root.toPath().relativize(f.toPath())}:${f.length()}:${f.lastModified()}".toString() }
        return rows + [files: files.sort()]
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearAll()
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void upsert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
