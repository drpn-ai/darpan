package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-064 — adding a check to an audit through the facade, in the ACTIVE tenant (KREWE). A save is all or nothing;
 * another tenant's audit, run or rule reads as not found; deleting keeps a check that findings already name.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation)
class AuditCheckAuthoringSmokeTests {

    private static final String FACADE = "facade.AuditFacadeServices"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TCHK_OTHER"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-check-authoring")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/MappingSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant"])
            [MINE, OTHER].each { String t ->
                insert("darpan.audit.Audit", [auditId: "${t}_MC".toString(), companyUserGroupId: t, auditCode: "month-close-t",
                        auditName: "Month close", periodEnumId: "AUDP_MONTH"])
                ["R4", "R7"].eachWithIndex { String code, int i ->
                    insert("darpan.audit.AuditStatement", [statementId: "${t}_${code}".toString(), companyUserGroupId: t,
                            auditId: "${t}_MC".toString(), statementCode: code, categoryEnumId: "AUDC_RETURNS",
                            weightEnumId: "AUDSW_FIRM", sequenceNum: (i + 1) as Long, statementText: "${code} text".toString()])
                }
            }
            insert("darpan.audit.Audit", [auditId: "${OTHER}_ONLY".toString(), companyUserGroupId: OTHER, auditCode: "other-only",
                    auditName: "Other only", periodEnumId: "AUDP_MONTH"])
            run("TCHK_RS1", MINE, "TCHK_RULE_A")
            run("TCHK_RS2", MINE, "TCHK_RULE_B")
            // DAR-UI-078: live has runs with no compare scope yet, and multi-word objects.
            insert("darpan.rule.RuleSet", [ruleSetId: "TCHK_RS_BARE", ruleSetName: "bare run", version: "1.0", companyUserGroupId: MINE])
            insert("darpan.rule.RuleSet", [ruleSetId: "TCHK_RS_UNITS", ruleSetName: "units run", version: "1.0", companyUserGroupId: MINE])
            insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "TCHK_RS_UNITS_SCOPE", ruleSetId: "TCHK_RS_UNITS",
                    scopeMode: "COMPARE", objectType: "ORDER_LINE_UNIT", companyUserGroupId: MINE])
            // DAR-UI-078: a run's sides, so the editor names the systems and fills what it can.
            insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "TCHK_RS1_SCOPE", fileSide: "FILE_1", systemEnumId: "NETSUITE_SUITEQL",
                    sourceTypeEnumId: "AUT_SRC_API", fileTypeEnumId: "DftJson", recordRootExpression: "records",
                    primaryIdExpression: "tranId", sourceConfigId: "cfg", companyUserGroupId: MINE])
            run("TCHK_RS_OTHER", OTHER, "TCHK_RULE_O")
            insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Conclusion"])
            insert("moqui.basic.Enumeration", [enumId: "CONC_TCHK_OPEN", enumTypeId: "DarpanConclusion", enumCode: "CONC_TCHK_OPEN",
                    description: "Memo left open"])
            insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "TCHK_RS1_SCOPE", sequenceNum: 10L,
                    conclusionEnumId: "CONC_TCHK_OPEN", appliesToBucket: "FINDING", companyUserGroupId: MINE])
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    @Test
    @Order(1)
    void optionsShowOnlyThisTenantsStatementsAndRuns() {
        Map out = facade("get#AuditCheckOptions", [audit: "month-close-t"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map options = (Map) out.options
        assertEquals(["R4", "R7"], options.statements*.id)
        List runs = (List) options.runs
        assertTrue(runs*.savedRunId.containsAll(["TCHK_RS1", "TCHK_RS2"]))
        assertFalse(runs*.savedRunId.contains("TCHK_RS_OTHER"), "another tenant's run is not offered")
        Map rs1 = (Map) runs.find { it.savedRunId == "TCHK_RS1" }
        assertEquals(["TCHK_RULE_A"], rs1.rules*.ruleId)
        assertEquals([[code: "CONC_TCHK_OPEN", label: "Memo left open"]], rs1.conclusions)
        assertEquals("return", rs1.recordType, "the run's object, as a record type")
        assertEquals([[side: "FILE_1", system: "NETSUITE", key: "tranId"]], rs1.sides, "each side's root system and key")
        assertEquals([null, []], runs.find { it.savedRunId == "TCHK_RS_BARE" }.subMap(["recordType", "sides"]).values() as List)
        assertEquals("orderLineUnit", runs.find { it.savedRunId == "TCHK_RS_UNITS" }.recordType)
        assertTrue(facade("get#AuditCheckOptions", [audit: "other-only"]).hadError as boolean, "another tenant's audit is not found")
    }

    @Test
    @Order(2)
    void savesACheckWithItsStatementLinks() {
        Map out = facade("save#AuditCheck", [audit: "month-close-t",
                check: [id: "credit-memos-left-open", label: "Credit memos left open", savedRunId: "TCHK_RS1",
                        file1RecordType: "creditMemo", graceDays: "1", fixOwner: "HotWax Support", fixWhereEnumId: "NETSUITE",
                        headlineTemplate: "Credit memo {id} is open"],
                statements: [[id: "R4", diffTypes: ["FINDING"], conclusionCodes: ["CONC_TCHK_OPEN"]],
                             [id: "R7", diffTypes: ["FIELD_MISMATCH"], ruleIds: ["TCHK_RULE_A"]]]])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map saved = (Map) out.savedCheck
        assertEquals("credit-memos-left-open", saved.id)
        assertEquals([[id: "R4", diffTypes: ["FINDING"], conclusionCodes: ["CONC_TCHK_OPEN"], ruleIds: []],
                      [id: "R7", diffTypes: ["FIELD_MISMATCH"], conclusionCodes: [], ruleIds: ["TCHK_RULE_A"]]], saved.statements)
        def row = checkRow("credit-memos-left-open")
        assertEquals(MINE, row.companyUserGroupId)
        assertEquals(1L, row.graceDays as Long)
        assertEquals("FINDING", link(row.checkId, "${MINE}_R4").diffTypes)
        assertEquals("CONC_TCHK_OPEN", link(row.checkId, "${MINE}_R4").conclusionCodes)

        assertTrue((facade("get#AuditCheckOptions", [audit: "month-close-t"]).options as Map).checks*.id.contains("credit-memos-left-open"))
    }

    @Test
    @Order(3)
    void aRefusedSaveWritesNothing() {
        Map base = [id: "refused-check", label: "Refused", savedRunId: "TCHK_RS1"]
        [
                [check: base, statements: [[id: "R9", diffTypes: ["FINDING"]]], says: "not in this audit"],
                [check: base + [savedRunId: "TCHK_RS_OTHER"], statements: [[id: "R4"]], says: "was not found"],
                [check: base, statements: [[id: "R4", ruleIds: ["TCHK_RULE_B"]]], says: "is not in run"],
                [check: base, statements: [[id: "R4", diffTypes: ["DROP_TABLE"]]], says: "not a row type"],
                [check: base + [id: "Bad Code"], statements: [[id: "R4"]], says: "lower-case words"],
                [check: base + [fixWhereEnumId: "OMS_RETURNS"], statements: [[id: "R4"]], says: "Fix where"],
                [check: base, statements: [], says: "at least one statement"],
                [check: base + [graceDays: "999"], statements: [[id: "R4"]], says: "Grace days"],
        ].each { Map c ->
            Map out = facade("save#AuditCheck", [audit: "month-close-t", check: c.check, statements: c.statements])
            assertTrue(out.hadError as boolean, "should refuse: ${c.says}")
            assertTrue((out.errorText as String).contains(c.says as String), out.errorText as String)
            assertNull(checkRow(c.check.id as String), "nothing written for: ${c.says}")
        }
        assertTrue(facade("save#AuditCheck", [audit: "other-only", check: base, statements: [[id: "R4"]]]).hadError as boolean)
        Map taken = facade("save#AuditCheck", [audit: "month-close-t", check: [id: "credit-memos-left-open", label: "Another",
                savedRunId: "TCHK_RS1", create: true], statements: [[id: "R7"]]])
        assertTrue(taken.hadError as boolean, "a new check may not take over an existing code")
        assertTrue((taken.errorText as String).contains("already used"), taken.errorText as String)
        assertEquals("Credit memos left open", checkRow("credit-memos-left-open").label, "the existing check is untouched")
    }

    @Test
    @Order(4)
    void changingTheRunClearsTheAutomationAndRelinks() {
        def row = checkRow("credit-memos-left-open")
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "TCHK_AUTO", automationName: "x",
                    companyUserGroupId: MINE, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "TCHK_RS1"])
            row.set("automationId", "TCHK_AUTO"); row.update()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        Map same = facade("save#AuditCheck", [audit: "month-close-t", check: [id: "credit-memos-left-open", label: "Renamed",
                savedRunId: "TCHK_RS1"], statements: [[id: "R4", diffTypes: ["FINDING"]]]])
        assertFalse(same.hadError as boolean, same.errorText as String)
        assertEquals("TCHK_AUTO", checkRow("credit-memos-left-open").automationId, "same run keeps its automation")
        assertNull(link(row.checkId, "${MINE}_R7"), "R7 unlinked: links are replaced for this audit")

        Map moved = facade("save#AuditCheck", [audit: "month-close-t", check: [id: "credit-memos-left-open", label: "Renamed",
                savedRunId: "TCHK_RS2"], statements: [[id: "R4"]]])
        assertFalse(moved.hadError as boolean, moved.errorText as String)
        assertNull(checkRow("credit-memos-left-open").automationId, "a new run needs a new automation")
    }

    private Map facade(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private void run(String ruleSetId, String tenant, String ruleId) {
        insert("darpan.rule.RuleSet", [ruleSetId: ruleSetId, ruleSetName: "${ruleSetId} run".toString(), version: "1.0", companyUserGroupId: tenant])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "${ruleSetId}_SCOPE".toString(), ruleSetId: ruleSetId,
                scopeMode: "EVALUATE", objectType: "RETURN", companyUserGroupId: tenant])
        insert("darpan.rule.Rule", [ruleId: ruleId, ruleSetId: ruleSetId, sequenceNum: 10L, ruleText: "a = b", enabled: "Y",
                ruleType: "FIELD_COMPARISON", companyUserGroupId: tenant])
    }

    private def checkRow(String code) {
        return ec.entity.find("darpan.audit.AuditCheck").condition("checkCode", code).disableAuthz().useCache(false).list().find()
    }

    private def link(Object checkId, String statementId) {
        return ec.entity.find("darpan.audit.AuditStatementCheck").condition([checkId: checkId, statementId: statementId])
                .disableAuthz().useCache(false).one()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields)
    }
}
