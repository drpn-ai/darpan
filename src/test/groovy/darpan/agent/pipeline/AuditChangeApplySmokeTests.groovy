package darpan.agent.pipeline

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-159. An agent's draft proposal becomes a live change only through the existing saves, only when a person applies
 * it, only while the plan still matches the config (planHash), and with a config version kept before and after. Plan
 * checks the change with the real save validators and rolls it back. Tenants and rows are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditChangeApplySmokeTests {

    private static final String FACADE = "facade.AgentChangeFacadeServices"
    private static final String PROPOSAL = "darpan.agent.AuditChangeProposal"
    private static final String VERSION = "darpan.config.TenantConfigVersion"
    private static final String PENDING = "darpan.config.TenantConfigPending"
    private static final String FILTER = "darpan.rule.RuleSetCompareSourceFilter"
    private static final String AUTO_FILTER = "darpan.reconciliation.ReconciliationAutomationSourceFilter"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TAC_OTHER"
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String USERNAME = "test.customer"
    private static final String CONV = "TAC_CONV"
    private static final String AUTO = "TAC_AUTO"
    private static final String NEW_FIELD = '$.records[*].tranId'
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")

    private ExecutionContext ec
    private String runId
    private String scopeId

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-change-apply")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/SystemMessageRemoteSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("moqui.security.UserGroupMember", [userGroupId: OTHER, userId: USER, fromDate: FROM])
            upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: OTHER, userId: USER,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
            upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
            ["CONC_TAC_A": "Memo left open", "CONC_TAC_B": "Store kept the item", "CONC_TAC_C": "Memo voided"].each { String id, String label ->
                upsert("moqui.basic.Enumeration", [enumId: id, enumTypeId: "DarpanConclusion", enumCode: id, description: label])
            }
            upsert("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TAC_AUTH", companyUserGroupId: MINE, description: "krewe netsuite", authType: "NONE", isActive: "Y"])
            upsert("darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "TAC_Q", nsAuthConfigId: "TAC_AUTH",
                    recordType: "CustCred", companyUserGroupId: MINE, isActive: "Y", description: "open credit memos"])
            [["tranId", "tranid"], ["memoOrigin", "memoorigin"], ["status", "status"]].eachWithIndex { List f, int i ->
                upsert("darpan.reconciliation.NsSuiteQlSourceQueryField", [nsSuiteQlSourceQueryId: "TAC_Q", recordFieldName: f[0],
                        sourceColumn: f[1], sequenceNum: (i + 1) as Long])
            }
        }
        Map run = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                runName: "Open credit memos", scopeMode: "EVALUATE",
                file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API", file1SourceConfigId: "TAC_Q",
                file1SourceConfigType: "NETSUITE_SUITEQL", file1PrimaryIdExpression: '$.records[*].tranId', rules: []]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        runId = run.savedRun.savedRunId as String
        scopeId = run.savedRun.compareScopeId as String
        asSystem {
            upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTO, automationName: "credit memos",
                    companyUserGroupId: MINE, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: runId, savedRunType: "ruleset",
                    ruleSetId: runId, compareScopeId: scopeId, isActive: "N", relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY",
                    windowTimeZone: "UTC", scheduleExpr: "0 0 6 * * ?"])
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTO, fileSide: "FILE_1",
                    companyUserGroupId: MINE, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                    safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"TAC_Q"}}'])
            upsert("darpan.audit.Audit", [auditId: "${MINE}_MC".toString(), companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            ["R1", "R2"].eachWithIndex { String code, int i ->
                upsert("darpan.audit.AuditStatement", [statementId: "${MINE}_${code}".toString(), companyUserGroupId: MINE,
                        auditId: "${MINE}_MC".toString(), statementCode: code, categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM",
                        sequenceNum: (i + 1) as Long, statementText: "${code} text".toString(), isActive: "Y"])
            }
            upsert("darpan.agent.AgentConversation", [conversationId: CONV, companyUserGroupId: MINE, userId: USER,
                    auditId: "${MINE}_MC".toString(), createdDate: FROM])
            upsert("darpan.agent.AgentMessage", [messageId: "TAC_MSG1", conversationId: CONV, companyUserGroupId: MINE, sequenceNum: 1,
                    kind: "USER", contentJson: '[{"type":"text","text":"Exclude M1088731 from R1; it is a test order. Memos marked Voided are fine."}]'])
            // A tool result naming an id is never the human's word, even inside a USER row's blocks.
            upsert("darpan.agent.AgentMessage", [messageId: "TAC_MSG2", conversationId: CONV, companyUserGroupId: MINE, sequenceNum: 2,
                    kind: "USER", contentJson: '[{"type":"tool_result","content":"M7777777"}]'])
            upsert("darpan.agent.AgentMessage", [messageId: "TAC_MSG3", conversationId: CONV, companyUserGroupId: MINE, sequenceNum: 3,
                    kind: "TOOL_RESULT", contentJson: '{"text":"M7777777"}'])
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /** One filter on the run and the same on its automation; a check linked to R1 and R2 (graceDays 1); a two-cause tree. */
    @BeforeEach
    void reset() {
        actAs(MINE)
        asSystem {
            ec.entity.find(PROPOSAL).deleteAll()
            ec.entity.find(VERSION).deleteAll()
            ec.entity.find(FILTER).condition("compareScopeId", scopeId).deleteAll()
            ec.entity.find(AUTO_FILTER).condition("automationId", AUTO).deleteAll()
            upsert(FILTER, [compareScopeId: scopeId, fileSide: "FILE_1", sequenceNum: 1L, fieldExpression: '$.records[*].memoOrigin',
                    operator: "INCLUDE_IN", filterValues: "HOTWAX", companyUserGroupId: MINE])
            upsert(AUTO_FILTER, [automationId: AUTO, fileSide: "FILE_1", sequenceNum: 1L, fieldExpression: '$.records[*].memoOrigin',
                    operator: "INCLUDE_IN", filterValues: "HOTWAX", companyUserGroupId: MINE])
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", MINE).deleteAll()
            upsert("darpan.audit.AuditCheck", [checkId: "TAC_CHK", companyUserGroupId: MINE, checkCode: "credits-open",
                    label: "Open credit memos", savedRunId: runId, graceDays: 1L, headlineTemplate: "Memo {tranId} is open"])
            upsert("darpan.audit.AuditStatementCheck", [checkId: "TAC_CHK", statementId: "${MINE}_R1".toString(), companyUserGroupId: MINE,
                    sequenceNum: 1L, diffTypes: "FINDING"])
            upsert("darpan.audit.AuditStatementCheck", [checkId: "TAC_CHK", statementId: "${MINE}_R2".toString(), companyUserGroupId: MINE,
                    sequenceNum: 1L, diffTypes: "FINDING", conclusionCodes: "CONC_TAC_B"])
        }
        Map tree = ec.service.sync().name("facade.RunConclusionFacadeServices.save#RunConclusions").parameters([savedRunId: runId,
                conclusions: [
                        [key: "1", conclusion: "CONC_TAC_A", bucket: "FINDING", fix: "Re-sync the memo",
                         conditions: [[field: "status", operator: "IN", values: ["Open", "Pending"], checkLabel: "Memo is open"]]],
                        [key: "2", conclusion: "CONC_TAC_B", bucket: "FINDING", fix: "Ask the store",
                         conditions: [[field: "memoOrigin", operator: "NOT_BLANK", checkLabel: "Has an origin"]]]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertNotNull(tree)
        ec.message.clearAll()
    }

    // ------------------------------------------------------------------ plan

    @Test
    void "plan validates without persisting"() {
        settle()
        asSystem { ec.entity.find(PENDING).deleteAll() }
        List<Map> before = filterRows()
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, filterValues: ["M1088731"]])
        assertFalse(p.hadError as boolean, p.errorText as String)
        assertEquals("PLANNED", p.status)
        assertEquals("PASS", p.provenanceStatus)
        assertEquals([supported: false, reason: "not built"], p.backtest)
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertFalse(plan.hadError as boolean, plan.errorText as String)
        assertTrue((plan.planHash as String) ==~ /[0-9a-f]{64}/)
        assertEquals(proposal(p.proposalId).planHash, plan.planHash, "planning the same config twice gives the same hash")
        assertTrue(plan.after.toString().contains("M1088731"), "the plan shows the change: ${plan.after}")
        assertFalse(plan.before.toString().contains("M1088731"))
        assertEquals(before, filterRows(), "the validating save was rolled back")
        settle()
        assertNull(asSystem { ec.entity.find(PENDING).condition("companyUserGroupId", MINE).useCache(false).one() },
                "a rolled-back save marks no tenant pending")

        Map bad = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: '$.records[*]', filterValues: ["M1088731"]])
        assertFalse(bad.hadError as boolean, bad.errorText as String)
        assertEquals("INVALID", bad.status, "the save's own validator refused it")
        assertTrue((bad.statusMessage as String).contains("does not resolve to a record field"), bad.statusMessage as String)
        assertEquals(before, filterRows())
    }

    @Test
    void "a proposal off the contract is refused and nothing is written"() {
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, values: ["M1088731"]])
        assertTrue(p.hadError as boolean, "unknown key 'values'")
        assertEquals(0L, asSystem { ec.entity.find(PROPOSAL).count() })
        Map t = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_3"], [fieldExpression: NEW_FIELD, filterValues: ["M1088731"]])
        assertTrue(t.hadError as boolean, "unknown side")
    }

    // ------------------------------------------------------------------ apply

    @Test
    void "long text is refused or kept within its column"() {
        String longReason = "x" * 300
        Map r = call("create#AuditChangeProposal", [conversationId: CONV, kind: "FIX_TEXT", target: [savedRunId: runId, conclusionCode: "CONC_TAC_A"],
                after: [fixActionTemplate: "Re-import the memo"], reasonText: longReason])
        assertTrue(r.hadError as boolean, "a reason over 255 characters is refused in words")
        assertTrue((r.errorText as String).contains("255"), r.errorText as String)
        List<String> many = (1..40).collect { "M1000${String.format('%04d', it)}".toString() }
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, filterValues: many])
        assertFalse(p.hadError as boolean, p.errorText as String)
        assertEquals("BLOCKED", p.provenanceStatus)
        assertEquals(many, p.blockedIds, "the service answers every blocked id")
        String stored = proposal(p.proposalId).blockedIds as String
        assertTrue(stored.length() <= 255 && stored.contains("more"), stored)
    }

    @Test
    void "apply writes the change and two AGENT_APPLY versions"() {
        Map sourceBefore = sourceRow()
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, filterValues: ["M1088731"]])
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("APPLIED", out.status)
        List<Map> rows = filterRows()
        assertEquals([[fieldExpression: '$.records[*].memoOrigin', operator: "INCLUDE_IN", filterValues: "HOTWAX"],
                      [fieldExpression: NEW_FIELD, operator: "EXCLUDE_IN", filterValues: "M1088731"]], rows)
        assertEquals(rows, automationFilterRows(), "the automation was synced with its run")
        assertEquals(sourceBefore, sourceRow(), "the whole-run save resends the run's setup unchanged")
        List versions = asSystem { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).orderBy("versionSeq").useCache(false).list() }
        assertEquals(["AGENT_APPLY", "AGENT_APPLY"], versions*.changeSource)
        String reason = "Agent proposal ${p.proposalId} for ${USER}, approved by ${USER}".toString()
        assertEquals([reason, reason], versions*.reasonText)
        def row = proposal(p.proposalId)
        assertEquals("APPLIED", row.status)
        assertEquals(USER, row.approvedByUserId)
        assertNotNull(row.approvedDate)
        assertEquals(versions[0].configVersionId, row.beforeVersionId)
        assertEquals(versions[1].configVersionId, row.appliedVersionId)
        assertEquals(row.appliedVersionId, out.appliedVersionId)
        assertTrue(call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash]).hadError as boolean, "applied once only")
    }

    @Test
    void "apply refuses a stale plan"() {
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, filterValues: ["M1088731"]])
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        ec.service.sync().name("facade.ReconciliationFacadeServices.save#RuleSetRun").parameters([savedRunId: runId,
                runName: "Open credit memos", file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API",
                file1SourceConfigId: "TAC_Q", file1SourceConfigType: "NETSUITE_SUITEQL", file1PrimaryIdExpression: '$.records[*].tranId',
                file1ExcludeFilters: [[fieldExpression: '$.records[*].status', operator: "EXCLUDE_IN", values: ["VOIDED"]]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List<Map> direct = filterRows()
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("STALE", out.status)
        assertEquals("STALE", proposal(p.proposalId).status)
        assertEquals(direct, filterRows(), "the direct edit stands")
        assertEquals([[fieldExpression: '$.records[*].status', operator: "EXCLUDE_IN", filterValues: "VOIDED"]], direct)
        assertEquals(0L, versionCount(), "no snapshot for a stale plan")
    }

    @Test
    void "apply refuses a BLOCKED proposal"() {
        List<Map> before = filterRows()
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, filterValues: ["M7777777"]])
        assertEquals("BLOCKED", p.provenanceStatus, "the id came only from tool data")
        assertEquals(["M7777777"], p.blockedIds)
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertEquals("PLANNED", plan.status)
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("BLOCKED"), out.errorText as String)
        assertEquals(before, filterRows())
        assertEquals("PLANNED", proposal(p.proposalId).status)
        assertEquals(0L, versionCount())
    }

    @Test
    void "exclusion apply refuses automation drift"() {
        asSystem {
            upsert(AUTO_FILTER, [automationId: AUTO, fileSide: "FILE_1", sequenceNum: 2L, fieldExpression: '$.records[*].status',
                    operator: "EXCLUDE_IN", filterValues: "DRAFT", companyUserGroupId: MINE])
        }
        List<Map> before = filterRows()
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, filterValues: ["M1088731"]])
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("AUTOMATION_DRIFT: ${AUTO}"), out.errorText as String)
        assertEquals(before, filterRows())
        assertEquals(2, automationFilterRows().size(), "the automation's own filters are left as they were")
        assertEquals(0L, versionCount())
    }

    // ------------------------------------------------------------------ other levers

    @Test
    void "statement narrowing keeps graceDays"() {
        Map target = [auditId: "${MINE}_MC".toString(), checkCode: "credits-open", statementCode: "R1"]
        Map p = propose("STATEMENT_NARROWING", target, [conclusionCodes: ["CONC_TAC_A"]])
        assertEquals("PLANNED", p.status, p.statusMessage as String)
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        def check = asSystem { ec.entity.find("darpan.audit.AuditCheck").condition("checkId", "TAC_CHK").useCache(false).one() }
        assertEquals(1L, check.graceDays as Long, "graceDays is resent, not nulled")
        assertEquals("Memo {tranId} is open", check.headlineTemplate)
        assertEquals("CONC_TAC_A", link("R1").conclusionCodes)
        assertEquals("FINDING", link("R1").diffTypes)
        assertEquals("CONC_TAC_B", link("R2").conclusionCodes, "the other statement's link is untouched")

        Map unknown = propose("STATEMENT_NARROWING", target, [conclusionCodes: ["CONC_NOT_A_CODE"]])
        assertEquals("INVALID", unknown.status, "a code outside the tenant's catalog")
    }

    @Test
    void "fix text changes one cause's fix only"() {
        List<Map> before = tree()
        Map p = propose("FIX_TEXT", [savedRunId: runId, conclusionCode: "CONC_TAC_A"], [fixActionTemplate: "Re-import the memo for {tranId}"])
        assertEquals("PASS", p.provenanceStatus)
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        List<Map> after = tree()
        assertEquals("Re-import the memo for {tranId}", node(after, "CONC_TAC_A").fix)
        assertEquals("Ask the store", node(after, "CONC_TAC_B").fix)
        assertEquals(before.collect { it.subMap(["conclusion", "bucket", "conditions"]) }, after.collect { it.subMap(["conclusion", "bucket", "conditions"]) })
    }

    @Test
    void "conclusion rows replace a named row and add one"() {
        Map p = propose("CONCLUSION_ROWS", [savedRunId: runId], [rows: [
                [conclusionCode: "CONC_TAC_B", fixActionTemplate: "Ask the store again"],
                [conclusionCode: "CONC_TAC_C", bucket: "FINDING", conditions: [[fieldExpression: "status", operator: "IN", checkValue: "Voided"]]]]])
        assertEquals("PASS", p.provenanceStatus, "Voided is the human's word")
        assertEquals("PLANNED", p.status, p.statusMessage as String)
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertFalse(call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash]).hadError as boolean)
        List<Map> after = tree()
        assertEquals(["CONC_TAC_A", "CONC_TAC_B", "CONC_TAC_C"], after*.conclusion)
        assertEquals("Ask the store again", node(after, "CONC_TAC_B").fix)
        assertEquals(["memoOrigin"], (node(after, "CONC_TAC_B").conditions as List<Map>)*.field, "a row without conditions keeps them")
        Map c = (node(after, "CONC_TAC_C").conditions as List<Map>)[0]
        assertEquals([field: "status", operator: "IN", values: ["Voided"]], c.subMap(["field", "operator", "values"]))
        assertEquals("Re-sync the memo", node(after, "CONC_TAC_A").fix)
    }

    // ------------------------------------------------------------------ revert and tenants

    @Test
    void "revert restores the before value through a new proposal"() {
        Map p = propose("FIX_TEXT", [savedRunId: runId, conclusionCode: "CONC_TAC_A"], [fixActionTemplate: "Re-import the memo for {tranId}"])
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertFalse(call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash]).hadError as boolean)
        Map r = call("revert#AuditChange", [proposalId: p.proposalId])
        assertFalse(r.hadError as boolean, r.errorText as String)
        def revert = proposal(r.newProposalId)
        assertEquals("PLANNED", revert.status, revert.statusMessage as String)
        assertEquals("PASS", revert.provenanceStatus)
        assertEquals(p.proposalId, revert.revertOfProposalId)
        assertTrue(call("revert#AuditChange", [proposalId: p.proposalId]).hadError as boolean, "one open revert at a time")
        Map revertPlan = call("plan#AuditChange", [proposalId: r.newProposalId])
        Map out = call("apply#AuditChange", [proposalId: r.newProposalId, planHash: revertPlan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("Re-sync the memo", node(tree(), "CONC_TAC_A").fix)
        assertEquals("REVERTED", proposal(p.proposalId).status)
        assertEquals(3L, versionCount(), "before, applied, reverted: the revert's before is the apply's version, kept once")
        assertEquals(proposal(p.proposalId).appliedVersionId, proposal(r.newProposalId).beforeVersionId)
        assertEquals(r.planHash, revertPlan.planHash, "revert planned once; planning again gives the same hash")
    }

    @Test
    void "tenant B cannot plan or apply tenant A's proposal"() {
        List<Map> before = filterRows()
        Map p = propose("EXCLUSION_FILTER", [savedRunId: runId, fileSide: "FILE_1"], [fieldExpression: NEW_FIELD, filterValues: ["M1088731"]])
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        actAs(OTHER)
        Map otherPlan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertTrue(otherPlan.hadError as boolean)
        assertTrue((otherPlan.errorText as String).contains("was not found"), otherPlan.errorText as String)
        assertTrue(call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash]).hadError as boolean)
        assertTrue(call("reject#AuditChange", [proposalId: p.proposalId, reason: "no"]).hadError as boolean)
        assertTrue(call("revert#AuditChange", [proposalId: p.proposalId]).hadError as boolean)
        assertTrue(propose("FIX_TEXT", [savedRunId: runId, conclusionCode: "CONC_TAC_A"], [fixActionTemplate: "Do it"]).hadError as boolean,
                "tenant A's conversation is not B's")
        actAs(MINE)
        assertEquals("PLANNED", proposal(p.proposalId).status)
        assertEquals(before, filterRows())
        assertEquals(0L, versionCount())
    }

    @Test
    void "reject closes a proposal so it cannot be applied"() {
        Map p = propose("FIX_TEXT", [savedRunId: runId, conclusionCode: "CONC_TAC_A"], [fixActionTemplate: "Re-import the memo"])
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        Map out = call("reject#AuditChange", [proposalId: p.proposalId, reason: "Not this one"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("REJECTED", proposal(p.proposalId).status)
        assertTrue(call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash]).hadError as boolean)
        assertEquals("Re-sync the memo", node(tree(), "CONC_TAC_A").fix)
    }

    // ------------------------------------------------------------------ helpers

    private Map propose(String kind, Map target, Map after) {
        return call("create#AuditChangeProposal", [conversationId: CONV, kind: kind, target: target, after: after,
                reasonText: "test", evidence: [findings: []]])
    }

    private Map call(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearAll()
        return result
    }

    private def proposal(Object id) {
        return asSystem { ec.entity.find(PROPOSAL).condition("proposalId", id).useCache(false).one() }
    }

    private List<Map> filterRows() {
        return asSystem {
            ec.entity.find(FILTER).condition("compareScopeId", scopeId).condition("fileSide", "FILE_1").orderBy("sequenceNum")
                    .useCache(false).list().collect { [fieldExpression: it.fieldExpression, operator: it.operator, filterValues: it.filterValues] as Map }
        }
    }

    private Map sourceRow() {
        return asSystem {
            def s = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", scopeId).condition("fileSide", "FILE_1")
                    .useCache(false).one()
            ["systemEnumId", "sourceTypeEnumId", "systemMessageRemoteId", "nsRestletConfigId", "sourceConfigId", "sourceConfigType",
             "fileTypeEnumId", "schemaFileName", "recordRootExpression", "primaryIdExpression", "duplicateKeyHandling",
             "extractParametersJson"].collectEntries { [(it): s.get(it)] }
        }
    }

    private List<Map> automationFilterRows() {
        return asSystem {
            ec.entity.find(AUTO_FILTER).condition("automationId", AUTO).condition("fileSide", "FILE_1").orderBy("sequenceNum")
                    .useCache(false).list().collect { [fieldExpression: it.fieldExpression, operator: it.operator, filterValues: it.filterValues] as Map }
        }
    }

    private def link(String statementCode) {
        return asSystem {
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", "TAC_CHK")
                    .condition("statementId", "${MINE}_${statementCode}".toString()).useCache(false).list().find()
        }
    }

    private List<Map> tree() {
        Map out = ec.service.sync().name("facade.RunConclusionFacadeServices.get#RunConclusions").parameters([savedRunId: runId]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        ec.message.clearAll()
        return (List<Map>) out.conclusions.conclusions
    }

    private static Map node(List<Map> nodes, String code) {
        return nodes.find { it.conclusion == code }
    }

    private long versionCount() {
        return asSystem { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).count() }
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearAll()
    }

    /** Async pending marks from earlier saves land before a test looks. */
    private void settle() {
        sleep(1000)
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
