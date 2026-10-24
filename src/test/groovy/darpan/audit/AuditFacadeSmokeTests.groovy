package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-058 — the app's facade over the audit run: the ACTIVE tenant's audits, report and finding, as the assembler's
 * JSON unchanged (the same document GraphQL and the CLI read). Another tenant's audit or finding reads as not found.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditFacadeSmokeTests {

    private static final String FACADE = "facade.AuditFacadeServices"
    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TFAC_OTHER"

    private ExecutionContext ec
    private String myFinding
    private String otherFinding

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-facade")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant"])
            [MINE, OTHER].each { String t ->
                insert("darpan.auth.TenantSetting", [companyUserGroupId: t, timeZone: "America/Los_Angeles"])
                insert("darpan.audit.Audit", [auditId: "${t}_MC".toString(), companyUserGroupId: t, auditCode: "month-close",
                        auditName: "Month close", periodEnumId: "AUDP_MONTH"])
                insert("darpan.audit.AuditStatement", [statementId: "${t}_R2".toString(), companyUserGroupId: t, auditId: "${t}_MC".toString(),
                        statementCode: "R2", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 2L,
                        statementText: "Every invoice carries the Etail id."])
                insert("darpan.audit.AuditCheck", [checkId: "${t}_INV".toString(), companyUserGroupId: t, checkCode: "invoices-without-etail-id",
                        label: "Invoices with no Etail id", graceDays: 1L])
                insert("darpan.audit.AuditStatementCheck", [checkId: "${t}_INV".toString(), statementId: "${t}_R2".toString(),
                        companyUserGroupId: t, sequenceNum: 1L])
            }
            // DAR-UI-072. Each tenant's morning ran in an automation execution that kept one extract per side.
            [MINE, OTHER].each { String t ->
                String automationId = "${t}_AUTO".toString()
                insert("darpan.reconciliation.ReconciliationAutomation", [automationId: automationId, automationName: automationId,
                        companyUserGroupId: t, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "${t}_RUN".toString()])
                insert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: automationId, fileSide: "FILE_1",
                        companyUserGroupId: t, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL"])
                insert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: automationId, fileSide: "FILE_2",
                        companyUserGroupId: t, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "OMS_RETURNS_GQL"])
                insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "${t}_EX".toString(),
                        automationId: automationId, companyUserGroupId: t, statusEnumId: "AUT_STAT_SUCCESS",
                        file1DataManagerPath: "reconciliation-runs/${t}/ns.json".toString(), file1RecordCount: 10L,
                        file2DataManagerPath: "reconciliation-runs/${t}/oms.json".toString(), file2RecordCount: 9L])
            }
            insert("darpan.audit.Audit", [auditId: "${OTHER}_ONLY".toString(), companyUserGroupId: OTHER, auditCode: "other-only",
                    auditName: "Other only", periodEnumId: "AUDP_MONTH"])
            myFinding = morning(MINE, "INV-K1")
            // DAR-UI-073. A cause explains KREWE's finding, so the findings page can filter by it.
            insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
            insert("moqui.basic.Enumeration", [enumId: "TFAC_CONC", enumTypeId: "DarpanConclusion", enumCode: "TFAC_CONC",
                    description: "Backordered in NetSuite"])
            ec.entity.find("darpan.audit.AuditFinding").condition("findingId", myFinding).one().set("conclusionEnumId", "TFAC_CONC").update()
            otherFinding = morning(OTHER, "INV-O1")
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private String morning(String tenant, String invoice) {
        Map base = [companyUserGroupId: tenant, checkId: "${tenant}_INV".toString(), reportDate: Date.valueOf("2026-10-08")]
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters(base + [automationExecutionId: "${tenant}_EX".toString(), sourceReads: [[systemEnumId: "NETSUITE_SUITEQL",
                statusEnumId: "AUDSR_COMPLETE", recordCount: 10L, readStamp: new java.sql.Timestamp(System.currentTimeMillis())]]]).call()
        Map found = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters(base + [findings: [[statementCode: "R2",
                recordKey: invoice, headline: "Invoice ${invoice} has no Etail id".toString(),
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "invoice", recordId: invoice]]]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return (found.findingIds as List)[0] as String
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    private Map call(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    @Test
    void theActiveTenantsAuditsAreListed() {
        Map out = call("get#Audits")
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals([[id: "month-close", name: "Month close", period: "month"]], out.audits, "only KREWE's, by code")
    }

    @Test
    void theReportIsTheAssemblersJsonUnchanged() {
        Map out = call("get#AuditReport", [audit: "month-close", date: "2026-10-08"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map doc = (Map) out.report
        assertEquals("darpan.audit.report/v1", doc.schema)
        assertEquals(MINE, doc.report.tenant.id)
        assertTrue(doc.categories instanceof Map, "keyed, as the JSON contract has it (GraphQL turns these into lists)")
        Map r2 = (Map) ((Map) ((Map) doc.categories).orders.statements).R2
        assertEquals(["Invoice INV-K1 has no Etail id"], (r2.findings as List<Map>)*.headline)
        assertFalse(doc.toString().contains("INV-O1"), "nothing of the other tenant")
    }

    @Test
    void anotherTenantsAuditOrFindingReadsAsNotFound() {
        Map audit = call("get#AuditReport", [audit: "other-only"])
        assertTrue(audit.hadError as boolean)
        assertTrue((audit.errorText as String).contains("Audit other-only was not found"), audit.errorText as String)
        assertNull(audit.report)

        Map finding = call("get#AuditFinding", [findingId: otherFinding])
        assertTrue(finding.hadError as boolean)
        assertTrue((finding.errorText as String).contains("not found"), finding.errorText as String)
        assertNull(finding.finding)
    }

    // DAR-BE-123. Resolve re-checks only the active tenant's finding, and only through its check's automation.
    @Test
    void aFindingIsVerifiedOnlyForItsTenantAndThroughItsChecksAutomation() {
        Map other = call("verify#AuditFinding", [findingId: otherFinding])
        assertTrue(other.hadError as boolean)
        assertTrue((other.errorText as String).contains("not found"), other.errorText as String)

        Map mine = call("verify#AuditFinding", [findingId: myFinding])
        assertTrue(mine.hadError as boolean)
        assertTrue((mine.errorText as String).contains("its check has no automation yet"), mine.errorText as String)
        assertFalse(mine.submitted as boolean)
    }

    @Test
    void aFindingIsTheAssemblersJsonUnchanged() {
        Map out = call("get#AuditFinding", [findingId: myFinding])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map doc = (Map) out.finding
        assertEquals("darpan.audit.finding/v1", doc.schema)
        assertEquals([invoice: [[id: "INV-K1", internalId: null, status: null]]], doc.finding.records.NetSuite,
                "records keyed by system and type, as the JSON contract has it")
    }

    @Test
    void aMalformedDateIsRefusedInWords() {
        Map out = call("get#AuditReport", [audit: "month-close", date: "2026-13-45"])
        assertTrue((out.errorText as String).contains("date must be YYYY-MM-DD"), out.errorText as String)
    }

    // DAR-UI-070. The board's "N more": a statement's open and pending findings past the report's 50, a page at a time.
    @Test
    void aStatementsFindingsPageIsTheActiveTenantsOnly() {
        Map out = call("get#AuditFindings", [audit: "month-close", statement: "R2", period: "2026-10"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals([myFinding], (out.findings as List<Map>)*.id, "KREWE's R2 finding, not the other tenant's")
        assertEquals("Invoice INV-K1 has no Etail id", (out.findings as List<Map>)[0].headline)
        assertTrue((out.findings as List<Map>)[0].records instanceof Map, "records keyed, as the report's findings are")
        assertFalse(out.hasNextPage as boolean)
        assertTrue(out.endCursor as boolean, "a cursor to ask for the page after")

        assertEquals([], call("get#AuditFindings", [audit: "month-close", statement: "R9", period: "2026-10"]).findings)
        assertEquals([], call("get#AuditFindings", [audit: "other-only", statement: "R2"]).findings, "another tenant's audit")
    }

    // DAR-UI-073. The board's filters read the whole audit's findings for the period, each with its cause, by state.
    @Test
    void aFindingsPageSpansTheAuditCarriesTheCauseAndNarrowsByState() {
        Map all = call("get#AuditFindings", [audit: "month-close", period: "2026-10"])
        assertFalse(all.hadError as boolean, all.errorText as String)
        assertEquals([[id: myFinding, statement: "R2", cause: "Backordered in NetSuite"]],
                (all.findings as List<Map>).collect { [id: it.id, statement: it.statement, cause: it.cause] })
        // Found on Oct 8 with a day's grace, the finding is pending, not open.
        assertEquals([myFinding], (call("get#AuditFindings", [audit: "month-close", period: "2026-10", state: "pending"]).findings as List<Map>)*.id)
        assertEquals([], call("get#AuditFindings", [audit: "month-close", period: "2026-10", state: "open"]).findings)
    }

    // DAR-UI-075. The board's "more", filters and export read the findings as the report lists them: one per order.
    @Test
    void theFindingsByOrderAreTheReportsUnitsForAStatementOrTheWholeAudit() {
        // Its own audit, so the other tests' statements and audit-wide reads stay as they are.
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("darpan.audit.Audit", [auditId: "${MINE}_BYO".toString(), companyUserGroupId: MINE, auditCode: "by-order",
                    auditName: "By order", periodEnumId: "AUDP_MONTH"])
            insert("darpan.audit.AuditStatement", [statementId: "${MINE}_R7".toString(), companyUserGroupId: MINE, auditId: "${MINE}_BYO".toString(),
                    statementCode: "R7", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 7L, statementText: "Every line is billed."])
            insert("darpan.audit.AuditCheck", [checkId: "${MINE}_BYO_C".toString(), companyUserGroupId: MINE, checkCode: "lines-billed", label: "Lines billed"])
            insert("darpan.audit.AuditStatementCheck", [checkId: "${MINE}_BYO_C".toString(), statementId: "${MINE}_R7".toString(),
                    companyUserGroupId: MINE, sequenceNum: 1L])
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        Closure<Map> line = { String item ->
            [statementCode: "R7", recordKey: item, headline: "Item ${item} is not billed".toString(),
             records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: item]]]
        }
        Map found = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: MINE,
                checkId: "${MINE}_BYO_C".toString(), reportDate: Date.valueOf("2026-10-08"),
                findings: [line("M7/01"), line("M9/01"), line("M9/02")]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List<String> ids = found.findingIds as List<String>

        Map out = call("get#AuditFindingsByOrder", [audit: "by-order", statement: "R7", period: "2026-10"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        List<Map> units = out.findings as List<Map>
        assertEquals(["M7", "M9"], units*.id, "M7's one line, then order M9's two lines as one: each an order")
        assertEquals(["order", "order"], units*.kind)
        assertEquals(ids[1..2], (units[1].lines as List<Map>)*.id)
        assertEquals([["R7", "orders"], ["R7", "orders"]], units.collect { [it.statement, it.category] })
        assertEquals(units*.id, (call("get#AuditFindingsByOrder", [audit: "by-order", period: "2026-10"]).findings as List<Map>)*.id,
                "the whole audit: every statement's units")
        // No grace on this check: every line is open, so both units are open and none pending.
        assertEquals(units*.id, (call("get#AuditFindingsByOrder", [audit: "by-order", period: "2026-10", state: "open"]).findings as List<Map>)*.id)
        assertEquals([], call("get#AuditFindingsByOrder", [audit: "by-order", period: "2026-10", state: "pending"]).findings)
        Map other = call("get#AuditFindingsByOrder", [audit: "other-only", period: "2026-10"])
        assertTrue((other.errorText as String).contains("Audit other-only was not found"), other.errorText as String)
    }

    @Test
    void aFindingsPageRefusesABadCursorInWords() {
        Map out = call("get#AuditFindings", [audit: "month-close", statement: "R2", after: "not-a-cursor"])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("after is not a cursor"), out.errorText as String)
    }

    // DAR-UI-072. What each system's read wrote for a check's morning, to download beside the report.
    @Test
    void aChecksExtractsAreWhatItsAutomationRunReadPerSystem() {
        Map out = call("get#AuditCheckExtracts", [audit: "month-close", check: "invoices-without-etail-id", date: "2026-10-08", period: "2026-10"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals([[system: "NetSuite", records: 10L, filePath: "reconciliation-runs/KREWE/ns.json"],
                      [system: "OMS", records: 9L, filePath: "reconciliation-runs/KREWE/oms.json"]],
                (out.extracts as List<Map>).collect { [system: it.system, records: it.records as Long, filePath: it.filePath] })

        assertEquals([], call("get#AuditCheckExtracts", [audit: "month-close", check: "invoices-without-etail-id", date: "2026-10-09", period: "2026-10"]).extracts,
                "no run that morning")
        Map other = call("get#AuditCheckExtracts", [audit: "other-only", check: "invoices-without-etail-id", date: "2026-10-08", period: "2026-10"])
        assertTrue((other.errorText as String).contains("Audit other-only was not found"), other.errorText as String)
        assertFalse(call("get#AuditCheckExtracts", [audit: "month-close", check: "invoices-without-etail-id", date: "2026-10-08", period: "2026-10"])
                .toString().contains("TFAC_OTHER"), "nothing of the other tenant")
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
