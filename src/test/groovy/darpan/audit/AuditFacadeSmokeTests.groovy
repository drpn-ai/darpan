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
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
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
            insert("darpan.audit.Audit", [auditId: "${OTHER}_ONLY".toString(), companyUserGroupId: OTHER, auditCode: "other-only",
                    auditName: "Other only", periodEnumId: "AUDP_MONTH"])
            myFinding = morning(MINE, "INV-K1")
            otherFinding = morning(OTHER, "INV-O1")
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private String morning(String tenant, String invoice) {
        Map base = [companyUserGroupId: tenant, checkId: "${tenant}_INV".toString(), reportDate: Date.valueOf("2026-10-08")]
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters(base + [sourceReads: [[systemEnumId: "NETSUITE_SUITEQL",
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

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
