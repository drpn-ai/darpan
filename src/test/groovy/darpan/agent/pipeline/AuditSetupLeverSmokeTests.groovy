package darpan.agent.pipeline

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

/**
 * DAR-BE-220. AUDIT_SETTINGS and ADD_STATEMENT drafted changes plan, apply and revert through save#AuditSettings (which
 * writes every check's automation) and add#AuditStatement, each kept as a config version; their backtest is NOT_APPLICABLE.
 * DAR-BE-221. ADD_CHECK does the same through save#AuditCheck: the new check's automation joins the audit's schedule, and
 * revert pauses and removes it.
 * DAR-BE-242. STATEMENT_EDIT rewords a statement or turns it on or off through save#AuditSettings, and revert puts both back.
 * Tenants and rows are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
        asSystem {
            upsert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("moqui.security.UserGroupMember", [userGroupId: OTHER, userId: USER, fromDate: FROM])
            upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: OTHER, userId: USER,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
            upsert("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TAS_AUTH", companyUserGroupId: MINE, authType: "NONE", isActive: "Y"])
            upsert("darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "TAS_Q", nsAuthConfigId: "TAS_AUTH",
                    recordType: "CustCred", companyUserGroupId: MINE, isActive: "Y", description: "open credit memos"])
            [["tranId", "tranid"], ["status", "status"]].eachWithIndex { List f, int i ->
                upsert("darpan.reconciliation.NsSuiteQlSourceQueryField", [nsSuiteQlSourceQueryId: "TAS_Q", recordFieldName: f[0],
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
            asSystem {
                upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATIONS[i], automationName: name,
                        companyUserGroupId: MINE, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: runId, savedRunType: "ruleset",
                        ruleSetId: runId, compareScopeId: run.savedRun.compareScopeId, isActive: "N",
                        relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY", windowTimeZone: "UTC", scheduleExpr: "0 0 6 * * ?"])
                upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTOMATIONS[i], fileSide: "FILE_1",
                        companyUserGroupId: MINE, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                        safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"TAS_Q"}}'])
            }
        }
        asSystem {
            upsert("darpan.audit.Audit", [auditId: AUDIT_ID, companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            ["R1", "R2"].eachWithIndex { String code, int i ->
                upsert("darpan.audit.AuditStatement", [statementId: "${MINE}_${code}".toString(), companyUserGroupId: MINE,
                        auditId: AUDIT_ID, statementCode: code, categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM",
                        sequenceNum: (i + 1) as Long, statementText: "${code} text".toString(), isActive: "Y"])
                upsert("darpan.audit.AuditCheck", [checkId: "TAS_CHK${i + 1}".toString(), companyUserGroupId: MINE,
                        checkCode: "check-${i + 1}".toString(), label: "Check ${i + 1}".toString(), savedRunId: runIds[i],
                        automationId: AUTOMATIONS[i], headlineTemplate: "Memo {tranId} is open"])
                upsert("darpan.audit.AuditStatementCheck", [checkId: "TAS_CHK${i + 1}".toString(), statementId: "${MINE}_${code}".toString(),
                        companyUserGroupId: MINE, sequenceNum: 1L, diffTypes: "FINDING"])
            }
            upsert("darpan.agent.AgentConversation", [conversationId: CONV, companyUserGroupId: MINE, userId: USER,
                    auditId: AUDIT_ID, createdDate: FROM])
            upsert("darpan.agent.AgentConversation", [conversationId: CONV_OTHER, companyUserGroupId: OTHER, userId: USER,
                    createdDate: FROM])
            // The person names the run the new check reads (provenance: a run id must come from them or the config).
            upsert("darpan.agent.AgentMessage", [messageId: "TAS_MSG1", conversationId: CONV, companyUserGroupId: MINE, sequenceNum: 1L,
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
        asSystem {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                    .condition("tenantUserGroupId", MINE).condition("userId", USER).deleteAll()
            upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: MINE, userId: USER,
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
        List versions = asSystem { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).orderBy("versionSeq").useCache(false).list() }
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
        assertEquals(NEW_TEXT, asSystem { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "${AUDIT_ID}_R3").one().statementText })
        assertEquals(["AGENT_APPLY", "AGENT_APPLY"], asSystem {
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
            asSystem { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", was.statementId).one()
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
        asSystem {
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
        def check = asSystem { ec.entity.find("darpan.audit.AuditCheck").condition("checkCode", NEW_CODE).useCache(false).one() }
        assertEquals(runIds[2], check.savedRunId)
        List automations = runAutomations()
        assertEquals([check.automationId], automations*.automationId, "the check runs through one automation of its run")
        assertEquals(["0 30 7 * * ?"], automations*.scheduleExpr)
        assertEquals(["UTC"], automations*.windowTimeZone)
        assertEquals(["Y"], automations*.isActive)
        assertEquals(["AGENT_APPLY", "AGENT_APPLY"], asSystem {
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

    private List<String> automationField(String field) {
        return asSystem {
            AUTOMATIONS.collect { String id ->
                ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", id).useCache(false).one().get(field) as String
            }
        }
    }

    private List<String> checkCodes() {
        return asSystem {
            ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", MINE).orderBy("checkCode").useCache(false).list()*.checkCode
        } as List<String>
    }

    private List runAutomations() {
        return asSystem {
            ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("savedRunId", runIds[2]).useCache(false).list()
        } as List
    }

    private Map statementRow(String code) {
        return asSystem {
            ec.entity.find("darpan.audit.AuditStatement").condition("auditId", AUDIT_ID).condition("statementCode", code)
                    .useCache(false).list().find()?.getMap()
        } as Map
    }

    private List<String> statementCodes() {
        return asSystem {
            ec.entity.find("darpan.audit.AuditStatement").condition("auditId", AUDIT_ID).orderBy("sequenceNum").useCache(false).list()*.statementCode
        } as List<String>
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
