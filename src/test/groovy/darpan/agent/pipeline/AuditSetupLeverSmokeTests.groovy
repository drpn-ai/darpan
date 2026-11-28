package darpan.agent.pipeline

import darpan.reconciliation.support.SharedMoqui
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
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.asSystem
import static darpan.reconciliation.support.TestHelpers.upsert

/**
 * DAR-BE-220. AUDIT_SETTINGS and ADD_STATEMENT drafted changes plan, apply and revert through save#AuditSettings (which
 * writes every check's automation) and add#AuditStatement, each kept as a config version; their backtest is NOT_APPLICABLE.
 * DAR-BE-221. ADD_CHECK does the same through save#AuditCheck: the new check's automation joins the audit's schedule, and
 * revert pauses and removes it.
 * DAR-BE-242. STATEMENT_EDIT rewords a statement or turns it on or off through save#AuditSettings, and revert puts both back.
 * Tenants and rows are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class AuditSetupLeverSmokeTests {

    private static final String FACADE = "facade.AgentChangeFacadeServices"
    private static final String PROPOSAL = "darpan.agent.AuditChangeProposal"
    private static final String VERSION = "darpan.config.TenantConfigVersion"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TAC_OTHER"
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String USERNAME = "test.customer"
    private static final String CONV = "TAS_CONV"
    private static final String CONV_OTHER = "TAS_CONV_OTHER"
    private static final String AUDIT_ID = "KREWE_MC"
    private static final List<String> AUTOMATIONS = ["TAS_AUTO1", "TAS_AUTO2"]
    private static final String NEW_TEXT = "Every return is credited within a week"
    private static final String NEW_CHECK = "Vendor memos open"
    private static final String NEW_CODE = "vendor-memos-open"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")

    private ExecutionContext ec
    private List<String> runIds = []

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-setup-lever")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/SystemMessageRemoteSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem(ec) {
            upsert(ec, "moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert(ec, "moqui.security.UserGroupMember", [userGroupId: OTHER, userId: USER, fromDate: FROM])
            upsert(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: OTHER, userId: USER,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
            upsert(ec, "darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TAS_AUTH", companyUserGroupId: MINE, authType: "NONE", isActive: "Y"])
            upsert(ec, "darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "TAS_Q", nsAuthConfigId: "TAS_AUTH",
                    recordType: "CustCred", companyUserGroupId: MINE, isActive: "Y", description: "open credit memos"])
            [["tranId", "tranid"], ["status", "status"]].eachWithIndex { List f, int i ->
                upsert(ec, "darpan.reconciliation.NsSuiteQlSourceQueryField", [nsSuiteQlSourceQueryId: "TAS_Q", recordFieldName: f[0],
                        sourceColumn: f[1], sequenceNum: (i + 1) as Long])
            }
        }
        // The third run has no check yet: ADD_CHECK puts one on it.
        ["Open credit memos", "Open debit memos", "Open vendor memos"].each { String name ->
            Map run = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                    runName: name, scopeMode: "EVALUATE", file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API",
                    file1SourceConfigId: "TAS_Q", file1SourceConfigType: "NETSUITE_SUITEQL",
                    file1PrimaryIdExpression: '$.records[*].tranId', rules: []]).call()
            assertFalse(ec.message.hasError(), ec.message.errorsString)
            String runId = run.savedRun.savedRunId as String
            runIds << runId
            int i = runIds.size() - 1
            if (i >= AUTOMATIONS.size()) return
            asSystem(ec) {
                upsert(ec, "darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATIONS[i], automationName: name,
                        companyUserGroupId: MINE, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: runId, savedRunType: "ruleset",
                        ruleSetId: runId, compareScopeId: run.savedRun.compareScopeId, isActive: "N",
                        relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY", windowTimeZone: "UTC", scheduleExpr: "0 0 6 * * ?"])
                upsert(ec, "darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTOMATIONS[i], fileSide: "FILE_1",
                        companyUserGroupId: MINE, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                        safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"TAS_Q"}}'])
            }
        }
        asSystem(ec) {
            upsert(ec, "darpan.audit.Audit", [auditId: AUDIT_ID, companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            ["R1", "R2"].eachWithIndex { String code, int i ->
                upsert(ec, "darpan.audit.AuditStatement", [statementId: "${MINE}_${code}".toString(), companyUserGroupId: MINE,
                        auditId: AUDIT_ID, statementCode: code, categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM",
                        sequenceNum: (i + 1) as Long, statementText: "${code} text".toString(), isActive: "Y"])
                upsert(ec, "darpan.audit.AuditCheck", [checkId: "TAS_CHK${i + 1}".toString(), companyUserGroupId: MINE,
                        checkCode: "check-${i + 1}".toString(), label: "Check ${i + 1}".toString(), savedRunId: runIds[i],
                        automationId: AUTOMATIONS[i], headlineTemplate: "Memo {tranId} is open"])
                upsert(ec, "darpan.audit.AuditStatementCheck", [checkId: "TAS_CHK${i + 1}".toString(), statementId: "${MINE}_${code}".toString(),
                        companyUserGroupId: MINE, sequenceNum: 1L, diffTypes: "FINDING"])
            }
            upsert(ec, "darpan.agent.AgentConversation", [conversationId: CONV, companyUserGroupId: MINE, userId: USER,
                    auditId: AUDIT_ID, createdDate: FROM])
            upsert(ec, "darpan.agent.AgentConversation", [conversationId: CONV_OTHER, companyUserGroupId: OTHER, userId: USER,
                    createdDate: FROM])
            // The person names the run the new check reads (provenance: a run id must come from them or the config).
            upsert(ec, "darpan.agent.AgentMessage", [messageId: "TAS_MSG1", conversationId: CONV, companyUserGroupId: MINE, sequenceNum: 1L,
                    kind: "USER", contentJson: JsonOutput.toJson("Add a check on run ${runIds[2]} to the first statement".toString())])
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        actAs(MINE)
        asSystem(ec) {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                    .condition("tenantUserGroupId", MINE).condition("userId", USER).deleteAll()
            upsert(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: MINE, userId: USER,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
            ec.entity.find(PROPOSAL).deleteAll()
            ec.entity.find(VERSION).deleteAll()
            ec.entity.find("darpan.audit.AuditStatement").condition("auditId", AUDIT_ID)
                    .condition("statementCode", "not-in", ["R1", "R2"]).deleteAll()
            ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", MINE)
                    .condition("checkCode", "not-in", ["check-1", "check-2"]).list().each { check ->
                ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", check.checkId).deleteAll()
                check.delete()
            }
            ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("savedRunId", runIds[2]).list().each { automation ->
                ["darpan.reconciliation.ReconciliationAutomationSourceFilter", "darpan.reconciliation.ReconciliationAutomationSource"].each { String name ->
                    ec.entity.find(name).condition("automationId", automation.automationId).deleteAll()
                }
                automation.delete()
            }
            ec.entity.find("darpan.audit.Audit").condition("auditId", AUDIT_ID).one()
                    .setAll([scheduleTime: null, scheduleTimeZone: null, scheduleActive: null]).update()
            AUTOMATIONS.each { String id ->
                ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", id).one()
                        .setAll([scheduleExpr: "0 0 6 * * ?", windowTimeZone: "UTC", isActive: "N"]).update()
            }
        }
        ec.message.clearAll()
    }

    // ------------------------------------------------------------------ AUDIT_SETTINGS

    @Test
    void "apply changes the schedule on every automation of the audit and revert restores it"() {
        Map p = propose("AUDIT_SETTINGS", [auditId: "month-close"], [time: "07:30", timeZone: "UTC", active: true])
        assertFalse(p.hadError as boolean, p.errorText as String)
        assertEquals("PLANNED", p.status, p.statusMessage as String)
        assertEquals("PASS", p.provenanceStatus)
        assertEquals(false, p.backtest.supported)
        assertEquals("NOT_APPLICABLE", p.backtest.reason)
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertFalse(plan.hadError as boolean, plan.errorText as String)
        assertEquals(["0 0 6 * * ?", "0 0 6 * * ?"], automationField("scheduleExpr"), "planning writes nothing")

        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("APPLIED", out.status)
        assertEquals(["0 30 7 * * ?", "0 30 7 * * ?"], automationField("scheduleExpr"))
        assertEquals(["Y", "Y"], automationField("isActive"))
        List versions = asSystem(ec) { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).orderBy("versionSeq").useCache(false).list() }
        assertEquals(["AGENT_APPLY", "AGENT_APPLY"], versions*.changeSource)

        Map r = call("revert#AuditChange", [proposalId: p.proposalId])
        assertFalse(r.hadError as boolean, r.errorText as String)
        assertEquals("PLANNED", r.status, r.statusMessage as String)
        Map back = call("apply#AuditChange", [proposalId: r.newProposalId, planHash: r.planHash])
        assertFalse(back.hadError as boolean, back.errorText as String)
        assertEquals(["0 0 6 * * ?", "0 0 6 * * ?"], automationField("scheduleExpr"))
        assertEquals(["N", "N"], automationField("isActive"))
        assertEquals("REVERTED", proposal(p.proposalId).status)
    }

    @Test
    void "a schedule the audit already runs on is INVALID NO_CHANGE"() {
        Map p = propose("AUDIT_SETTINGS", [auditId: "month-close"], [time: "06:00", active: false])
        assertEquals("INVALID", p.status)
        assertEquals(AuditChangeLevers.NO_CHANGE_SCHEDULE_MESSAGE, p.statusMessage)
        assertTrue(propose("AUDIT_SETTINGS", [auditId: "month-close"], [time: "25:00"]).hadError as boolean, "off the contract")
    }

    // ------------------------------------------------------------------ ADD_STATEMENT

    @Test
    void "a statement is added and revert removes it"() {
        Map p = propose("ADD_STATEMENT", [auditId: "month-close"], [text: NEW_TEXT, category: "returns"])
        assertFalse(p.hadError as boolean, p.errorText as String)
        assertEquals("PLANNED", p.status, p.statusMessage as String)
        assertEquals("NOT_APPLICABLE", p.backtest.reason)
        assertEquals(["R1", "R2"], statementCodes(), "planning adds nothing")
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals(["R1", "R2", "R3"], statementCodes())
        assertEquals(NEW_TEXT, asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "${AUDIT_ID}_R3").one().statementText })
        assertEquals(["AGENT_APPLY", "AGENT_APPLY"], asSystem(ec) {
            ec.entity.find(VERSION).condition("companyUserGroupId", MINE).orderBy("versionSeq").useCache(false).list()*.changeSource })

        Map again = propose("ADD_STATEMENT", [auditId: "month-close"], [text: NEW_TEXT, category: "returns"])
        assertEquals("INVALID", again.status, "the same text twice")
        assertEquals(AuditChangeLevers.NO_CHANGE_STATEMENT_MESSAGE, again.statusMessage)

        Map r = call("revert#AuditChange", [proposalId: p.proposalId])
        assertFalse(r.hadError as boolean, r.errorText as String)
        assertEquals("PLANNED", r.status, r.statusMessage as String)
        Map back = call("apply#AuditChange", [proposalId: r.newProposalId, planHash: r.planHash])
        assertFalse(back.hadError as boolean, back.errorText as String)
        assertEquals(["R1", "R2"], statementCodes())
        assertEquals("REVERTED", proposal(p.proposalId).status)
    }

    // ------------------------------------------------------------------ STATEMENT_EDIT

    @Test
    void "a statement is reworded and turned off, and revert puts both back"() {
        Map was = statementRow("R1")
        String reworded = "${was.statementText} within a week".toString()
        try {
            Map p = propose("STATEMENT_EDIT", [auditId: "month-close", statementCode: "R1"], [text: reworded, active: false])
            assertFalse(p.hadError as boolean, p.errorText as String)
            assertEquals("PLANNED", p.status, p.statusMessage as String)
            assertEquals("NOT_APPLICABLE", p.backtest.reason, "on/off counts from the next run, not landed data")
            assertEquals(was.statementText, statementRow("R1").statementText, "planning writes nothing")
            Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
            Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
            assertFalse(out.hadError as boolean, out.errorText as String)
            assertEquals("APPLIED", out.status)
            Map now = statementRow("R1")
            assertEquals(reworded, now.statementText)
            assertEquals("N", now.isActive)
            assertEquals("R2 text", statementRow("R2").statementText, "only the named statement moves")
            assertEquals("Y", statementRow("R2").isActive)

            Map r = call("revert#AuditChange", [proposalId: p.proposalId])
            assertFalse(r.hadError as boolean, r.errorText as String)
            assertEquals("PLANNED", r.status, r.statusMessage as String)
            Map back = call("apply#AuditChange", [proposalId: r.newProposalId, planHash: r.planHash])
            assertFalse(back.hadError as boolean, back.errorText as String)
            now = statementRow("R1")
            assertEquals(was.statementText, now.statementText)
            assertEquals((was.isActive ?: "Y") != "N", (now.isActive ?: "Y") != "N")
            assertEquals("REVERTED", proposal(p.proposalId).status)
        } finally {
            asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", was.statementId).one()
                    .setAll([statementText: was.statementText, isActive: was.isActive]).update() }
        }
    }

    @Test
    void "wording alone is display only, and no change or another statement's text is refused"() {
        Map r1 = statementRow("R1"), r2 = statementRow("R2")
        Map target = [auditId: "month-close", statementCode: "R1"]
        Map p = propose("STATEMENT_EDIT", target, [text: "${r1.statementText} today".toString()])
        assertEquals("PLANNED", p.status, p.statusMessage as String)
        assertEquals("DISPLAY_ONLY", p.backtest.reason)
        assertEquals(r1.statementText, statementRow("R1").statementText, "a draft writes nothing")

        Map same = propose("STATEMENT_EDIT", target, [text: r1.statementText, active: true])
        assertEquals("INVALID", same.status)
        assertEquals(AuditChangeLevers.NO_CHANGE_STATEMENT_EDIT_MESSAGE, same.statusMessage)
        Map dup = propose("STATEMENT_EDIT", target, [text: r2.statementText])
        assertEquals("INVALID", dup.status)
        assertEquals(AuditChangeLevers.DUPLICATE_STATEMENT_TEXT_MESSAGE, dup.statusMessage)
        Map missing = propose("STATEMENT_EDIT", [auditId: "month-close", statementCode: "R9"], [active: false])
        assertEquals("INVALID", missing.status, "a statement the audit does not have")
        assertTrue(propose("STATEMENT_EDIT", target, [:]).hadError as boolean, "off the contract: neither text nor active")
    }

    // ------------------------------------------------------------------ ADD_CHECK

    @Test
    void "a check is added on the audit's schedule and revert pauses and removes it"() {
        asSystem(ec) {
            ec.entity.find("darpan.audit.Audit").condition("auditId", AUDIT_ID).one()
                    .setAll([scheduleTime: "07:30", scheduleTimeZone: "UTC", scheduleActive: "Y"]).update()
        }
        Map p = propose("ADD_CHECK", [auditId: "month-close"], [label: NEW_CHECK, savedRunId: runIds[2], statementCode: "R1"])
        assertFalse(p.hadError as boolean, p.errorText as String)
        assertEquals("PLANNED", p.status, p.statusMessage as String)
        assertEquals("PASS", p.provenanceStatus, p.blockedIds as String)
        assertEquals("NOT_APPLICABLE", p.backtest.reason)
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertFalse(plan.hadError as boolean, plan.errorText as String)
        assertEquals(["check-1", "check-2"], checkCodes(), "no check before apply")
        assertEquals(0, runAutomations().size(), "planning makes no automation")

        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("APPLIED", out.status)
        assertEquals(["check-1", "check-2", NEW_CODE], checkCodes())
        def check = asSystem(ec) { ec.entity.find("darpan.audit.AuditCheck").condition("checkCode", NEW_CODE).useCache(false).one() }
        assertEquals(runIds[2], check.savedRunId)
        List automations = runAutomations()
        assertEquals([check.automationId], automations*.automationId, "the check runs through one automation of its run")
        assertEquals(["0 30 7 * * ?"], automations*.scheduleExpr)
        assertEquals(["UTC"], automations*.windowTimeZone)
        assertEquals(["Y"], automations*.isActive)
        assertEquals(["AGENT_APPLY", "AGENT_APPLY"], asSystem(ec) {
            ec.entity.find(VERSION).condition("companyUserGroupId", MINE).orderBy("versionSeq").useCache(false).list()*.changeSource })

        Map again = propose("ADD_CHECK", [auditId: "month-close"], [label: NEW_CHECK, savedRunId: runIds[2], statementCode: "R1"])
        assertEquals("INVALID", again.status, "the same check twice")
        assertEquals(AuditChangeLevers.NO_CHANGE_CHECK_MESSAGE, again.statusMessage)

        Map r = call("revert#AuditChange", [proposalId: p.proposalId])
        assertFalse(r.hadError as boolean, r.errorText as String)
        assertEquals("PLANNED", r.status, r.statusMessage as String)
        Map back = call("apply#AuditChange", [proposalId: r.newProposalId, planHash: r.planHash])
        assertFalse(back.hadError as boolean, back.errorText as String)
        assertEquals(["check-1", "check-2"], checkCodes(), "no active check is left")
        assertEquals(["N"], runAutomations()*.isActive, "its automation is paused")
        assertEquals("REVERTED", proposal(p.proposalId).status)
    }

    @Test
    void "a check on a statement the audit does not have is refused"() {
        Map p = propose("ADD_CHECK", [auditId: "month-close"], [label: NEW_CHECK, savedRunId: runIds[2], statementCode: "R9"])
        assertEquals("INVALID", p.status)
        assertTrue((p.statusMessage as String).contains("not an active statement"), p.statusMessage as String)
        assertEquals(["check-1", "check-2"], checkCodes())
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void "apply refuses a stale plan"() {
        Map p = propose("AUDIT_SETTINGS", [auditId: "month-close"], [time: "07:30", active: true])
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        ec.service.sync().name("facade.AuditFacadeServices.save#AuditSettings").parameters([audit: "month-close",
                schedule: [time: "08:15", active: true]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List<String> direct = automationField("scheduleExpr")
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("STALE", out.status)
        assertEquals("STALE", proposal(p.proposalId).status)
        assertEquals(direct, automationField("scheduleExpr"), "the direct edit stands")
        assertEquals(["0 15 8 * * ?", "0 15 8 * * ?"], direct)
    }

    @Test
    void "another tenant's audit is refused"() {
        actAs(OTHER)
        assertNull(AuditChangeLevers.current(ec, "AUDIT_SETTINGS", [auditId: "month-close"]))
        assertTrue(ec.message.errorsString.contains("not found"), ec.message.errorsString)
        ec.message.clearAll()
        assertNull(AuditChangeLevers.current(ec, "ADD_STATEMENT", [auditId: AUDIT_ID]))
        ec.message.clearAll()
        assertNull(AuditChangeLevers.current(ec, "ADD_CHECK", [auditId: AUDIT_ID]))
        ec.message.clearAll()
        ["AUDIT_SETTINGS": [time: "07:30"], "ADD_STATEMENT": [text: NEW_TEXT, category: "returns"],
         "ADD_CHECK": [label: NEW_CHECK, savedRunId: runIds[2], statementCode: "R1"]].each { String kind, Map after ->
            Map p = this.call("create#AuditChangeProposal", [conversationId: CONV_OTHER, kind: kind, target: [auditId: AUDIT_ID],
                    after: after, reasonText: "test"])
            assertFalse(p.hadError as boolean, p.errorText as String)
            assertEquals("INVALID", p.status, kind)
            assertTrue((p.statusMessage as String).contains("not found"), p.statusMessage as String)
            Map out = this.call("apply#AuditChange", [proposalId: p.proposalId, planHash: "x"])
            assertTrue(out.hadError as boolean, "an invalid proposal is not applied")
        }
        Map edit = this.call("create#AuditChangeProposal", [conversationId: CONV_OTHER, kind: "STATEMENT_EDIT",
                target: [auditId: AUDIT_ID, statementCode: "R1"], after: [active: false], reasonText: "test"])
        assertEquals("INVALID", edit.status, "STATEMENT_EDIT")
        assertTrue((edit.statusMessage as String).contains("not found"), edit.statusMessage as String)
        actAs(MINE)
        assertEquals(["0 0 6 * * ?", "0 0 6 * * ?"], automationField("scheduleExpr"))
        assertEquals(["R1", "R2"], statementCodes())
        assertNotEquals("N", statementRow("R1").isActive, "the other tenant's draft turned nothing off")
        assertEquals(["check-1", "check-2"], checkCodes())
    }

    // ------------------------------------------------------------------ priorities (DAR-BE-348)

    @Test
    void "statementEditSetsPriority and revert puts back the exact old level, unset included"() {
        Map was = statementRow("R1")
        Map target = [auditId: "month-close", statementCode: "R1"]
        try {
            asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", was.statementId).one().set("priorityEnumId", null).update() }
            // unset -> P0 (a level alone is a whole proposal), reverted to unset
            Map p = propose("STATEMENT_EDIT", target, [priority: "P0"])
            assertEquals("PLANNED", p.status, p.statusMessage as String)
            assertEquals("DISPLAY_ONLY", p.backtest.reason)
            assertNull(statementRow("R1").priorityEnumId, "planning writes nothing")
            applyPlanned(p)
            assertEquals("AUDP_P0", statementRow("R1").priorityEnumId)
            applyRevert(p)
            assertNull(statementRow("R1").priorityEnumId, "unset stays unset, not P2")

            // P1 -> KNOWN, reverted to P1
            asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", was.statementId).one().set("priorityEnumId", "AUDP_P1").update() }
            Map k = propose("STATEMENT_EDIT", target, [priority: "KNOWN"])
            applyPlanned(k)
            assertEquals("AUDP_KNOWN", statementRow("R1").priorityEnumId)
            applyRevert(k)
            assertEquals("AUDP_P1", statementRow("R1").priorityEnumId)

            // a null clears the level, and the revert gives it back
            Map c = propose("STATEMENT_EDIT", target, [priority: null])
            assertEquals("PLANNED", c.status, c.statusMessage as String)
            applyPlanned(c)
            assertNull(statementRow("R1").priorityEnumId)
            applyRevert(c)
            assertEquals("AUDP_P1", statementRow("R1").priorityEnumId)

            // the same level, or a level that reads the same, is no change; a bad level is off the contract
            assertEquals("INVALID", propose("STATEMENT_EDIT", target, [priority: "P1"]).status)
            assertTrue(propose("STATEMENT_EDIT", target, [priority: "URGENT"]).hadError as boolean)
            assertEquals("AUDP_P1", statementRow("R1").priorityEnumId)
        } finally {
            asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", was.statementId).one()
                    .set("priorityEnumId", was.priorityEnumId).update() }
        }
    }

    @Test
    void "reasonPriorityAppliesAndReverts, with no row or a previous override put back as it was"() {
        String statementId = "${MINE}_R1".toString()
        Map target = [auditId: "month-close", statementCode: "R1", conclusionCode: "TAS_C_OPEN"]
        reasonFixtures()
        try {
            // no row -> P1: the revert deletes the row
            Map p = propose("REASON_PRIORITY", target, [priority: "P1"])
            assertEquals("PLANNED", p.status, p.statusMessage as String)
            assertEquals("DISPLAY_ONLY", p.backtest.reason)
            assertEquals([], reasonLevels(statementId, "TAS_C_OPEN"), "planning writes nothing")
            applyPlanned(p)
            assertEquals(["AUDP_P1"], reasonLevels(statementId, "TAS_C_OPEN"))
            applyRevert(p)
            assertEquals([], reasonLevels(statementId, "TAS_C_OPEN"), "no row before, no row after")

            // P0 -> KNOWN: the revert restores P0
            asSystem(ec) { upsert(ec, "darpan.audit.AuditReasonPriority", [reasonPriorityId: "TAS_RP1", companyUserGroupId: MINE,
                    statementId: statementId, conclusionEnumId: "TAS_C_OPEN", priorityEnumId: "AUDP_P0"]) }
            Map k = propose("REASON_PRIORITY", target, [priority: "KNOWN"])
            applyPlanned(k)
            assertEquals(["AUDP_KNOWN"], reasonLevels(statementId, "TAS_C_OPEN"))
            applyRevert(k)
            assertEquals(["AUDP_P0"], reasonLevels(statementId, "TAS_C_OPEN"))

            // null deletes the override, and the revert brings it back
            Map c = propose("REASON_PRIORITY", target, [priority: null])
            assertEquals("PLANNED", c.status, c.statusMessage as String)
            applyPlanned(c)
            assertEquals([], reasonLevels(statementId, "TAS_C_OPEN"))
            applyRevert(c)
            assertEquals(["AUDP_P0"], reasonLevels(statementId, "TAS_C_OPEN"))

            assertEquals("INVALID", propose("REASON_PRIORITY", target, [priority: "P0"]).status, "already this level")
            assertTrue(propose("REASON_PRIORITY", target, [:]).hadError as boolean, "the level is required, null clears")
            assertTrue(propose("REASON_PRIORITY", target, [priority: "P3"]).hadError as boolean)
            asSystem(ec) { ec.entity.find("darpan.audit.AuditReasonPriority").condition("statementId", statementId).deleteAll() }
            Map none = propose("REASON_PRIORITY", target, [priority: null])
            assertEquals("INVALID", none.status, "nothing to clear")
            assertEquals(AuditChangeLevers.NO_CHANGE_PRIORITY_MESSAGE, none.statusMessage)
        } finally {
            asSystem(ec) {
                ec.entity.find("darpan.audit.AuditReasonPriority").condition("statementId", statementId).deleteAll()
                ec.entity.find("darpan.rule.RuleSetConclusionRule").condition("conclusionEnumId", "in", ["TAS_C_OPEN", "TAS_C_ALL"]).deleteAll()
            }
        }
    }

    @Test
    void "a reason that closes on every check, Unexplained or a non-reason takes no level"() {
        String statementId = "${MINE}_R1".toString()
        reasonFixtures()
        try {
            Map all = propose("REASON_PRIORITY", [auditId: "month-close", statementCode: "R1", conclusionCode: "TAS_C_ALL"], [priority: "P0"])
            assertEquals("INVALID", all.status)
            assertTrue((all.statusMessage as String).contains("closes every finding on R1's checks, so it takes no level"), all.statusMessage as String)
            assertEquals("INVALID", propose("REASON_PRIORITY", [auditId: "month-close", statementCode: "R1", conclusionCode: "UNEXPLAINED"],
                    [priority: "P0"]).status)
            assertEquals("INVALID", propose("REASON_PRIORITY", [auditId: "month-close", statementCode: "R1", conclusionCode: "AUDP_P0"],
                    [priority: "P0"]).status, "an enumeration that is no conclusion")
            assertEquals("INVALID", propose("REASON_PRIORITY", [auditId: "month-close", statementCode: "R9", conclusionCode: "TAS_C_OPEN"],
                    [priority: "P0"]).status, "a statement the audit does not have")
            assertEquals([], reasonLevels(statementId, "TAS_C_ALL"))
        } finally {
            asSystem(ec) { ec.entity.find("darpan.rule.RuleSetConclusionRule").condition("conclusionEnumId", "in", ["TAS_C_OPEN", "TAS_C_ALL"]).deleteAll() }
        }
    }

    @Test
    void "the two priority services refuse another tenant's statement and write nothing"() {
        String foreign = "${OTHER}_R1".toString()
        asSystem(ec) {
            upsert(ec, "darpan.audit.Audit", [auditId: "TAS_OTHER_AUDIT", companyUserGroupId: OTHER, auditCode: "other-audit",
                    auditName: "Other audit", periodEnumId: "AUDP_MONTH"])
            upsert(ec, "darpan.audit.AuditStatement", [statementId: foreign, companyUserGroupId: OTHER, auditId: "TAS_OTHER_AUDIT",
                    statementCode: "R1", categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM", sequenceNum: 1L,
                    statementText: "other R1", isActive: "Y", priorityEnumId: "AUDP_P1"])
            upsert(ec, "darpan.audit.AuditReasonPriority", [reasonPriorityId: "TAS_RP_OTHER", companyUserGroupId: OTHER,
                    statementId: foreign, conclusionEnumId: "TAS_C_OPEN", priorityEnumId: "AUDP_KNOWN"])
        }
        try {
            Map statement = ec.service.sync().name("facade.AuditFacadeServices.set#AuditStatementPriority")
                    .parameters([companyUserGroupId: MINE, statementId: foreign, priorityEnumId: "AUDP_P0"]).call() ?: [:]
            assertTrue(ec.message.hasError(), "a statement of another tenant reads as not found")
            ec.message.clearAll()
            ec.service.sync().name("facade.AuditFacadeServices.set#AuditReasonPriority")
                    .parameters([companyUserGroupId: MINE, statementId: foreign, conclusionEnumId: "TAS_C_OPEN", priorityEnumId: "AUDP_P0"]).call()
            assertTrue(ec.message.hasError())
            ec.message.clearAll()
            ec.service.sync().name("facade.AuditFacadeServices.set#AuditReasonPriority")
                    .parameters([companyUserGroupId: MINE, statementId: foreign, conclusionEnumId: "TAS_C_OPEN"]).call()
            assertTrue(ec.message.hasError(), "clearing reaches nothing either")
            ec.message.clearAll()
            assertEquals("AUDP_P1", asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", foreign).useCache(false).one().priorityEnumId })
            assertEquals(["AUDP_KNOWN"], reasonLevels(foreign, "TAS_C_OPEN"), "the other tenant's override is untouched")
            assertEquals(1, asSystem(ec) { ec.entity.find("darpan.audit.AuditReasonPriority").condition("statementId", foreign).useCache(false).count() })
        } finally {
            asSystem(ec) {
                ec.entity.find("darpan.audit.AuditReasonPriority").condition("statementId", foreign).deleteAll()
                ec.entity.find("darpan.audit.AuditStatement").condition("statementId", foreign).deleteAll()
                ec.entity.find("darpan.audit.Audit").condition("auditId", "TAS_OTHER_AUDIT").deleteAll()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void applyPlanned(Map p) {
        Map plan = call("plan#AuditChange", [proposalId: p.proposalId])
        assertFalse(plan.hadError as boolean, plan.errorText as String)
        Map out = call("apply#AuditChange", [proposalId: p.proposalId, planHash: plan.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals("APPLIED", out.status)
    }

    private void applyRevert(Map p) {
        Map r = call("revert#AuditChange", [proposalId: p.proposalId])
        assertFalse(r.hadError as boolean, r.errorText as String)
        assertEquals("PLANNED", r.status, r.statusMessage as String)
        Map back = call("apply#AuditChange", [proposalId: r.newProposalId, planHash: r.planHash])
        assertFalse(back.hadError as boolean, back.errorText as String)
        assertEquals("REVERTED", proposal(p.proposalId).status)
    }

    private List<String> reasonLevels(String statementId, String conclusion) {
        return asSystem(ec) {
            ec.entity.find("darpan.audit.AuditReasonPriority").condition("statementId", statementId)
                    .condition("conclusionEnumId", conclusion).useCache(false).list()*.priorityEnumId
        } as List<String>
    }

    /** Two reasons on R1's run: TAS_C_OPEN is expected nowhere (it can take a level), TAS_C_ALL is expected on every check. */
    private void reasonFixtures() {
        asSystem(ec) {
            upsert(ec, "moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
            [TAS_C_OPEN: "Still open", TAS_C_ALL: "Recorded late"].each { String id, String label ->
                upsert(ec, "moqui.basic.Enumeration", [enumId: id, enumTypeId: "DarpanConclusion", enumCode: id, description: label])
            }
            String scopeId = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", runIds[0]).useCache(false).list().first().compareScopeId
            [[1L, "TAS_C_OPEN", null], [2L, "TAS_C_ALL", "EXPECTED"]].each { List r ->
                ec.entity.find("darpan.rule.RuleSetConclusionRule").condition("compareScopeId", scopeId).condition("sequenceNum", r[0]).deleteAll()
                upsert(ec, "darpan.rule.RuleSetConclusionRule", [compareScopeId: scopeId, sequenceNum: r[0], conclusionEnumId: r[1],
                        outcome: r[2], companyUserGroupId: MINE])
            }
        }
    }

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
        return asSystem(ec) { ec.entity.find(PROPOSAL).condition("proposalId", id).useCache(false).one() }
    }

    private List<String> automationField(String field) {
        return asSystem(ec) {
            AUTOMATIONS.collect { String id ->
                ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", id).useCache(false).one().get(field) as String
            }
        }
    }

    private List<String> checkCodes() {
        return asSystem(ec) {
            ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", MINE).orderBy("checkCode").useCache(false).list()*.checkCode
        } as List<String>
    }

    private List runAutomations() {
        return asSystem(ec) {
            ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("savedRunId", runIds[2]).useCache(false).list()
        } as List
    }

    private Map statementRow(String code) {
        return asSystem(ec) {
            ec.entity.find("darpan.audit.AuditStatement").condition("auditId", AUDIT_ID).condition("statementCode", code)
                    .useCache(false).list().find()?.getMap()
        } as Map
    }

    private List<String> statementCodes() {
        return asSystem(ec) {
            ec.entity.find("darpan.audit.AuditStatement").condition("auditId", AUDIT_ID).orderBy("sequenceNum").useCache(false).list()*.statementCode
        } as List<String>
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearAll()
    }
}
