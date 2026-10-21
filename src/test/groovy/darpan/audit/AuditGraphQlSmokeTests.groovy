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
 * DAR-BE-098 — the audit run over GraphQL (moqui-gql, graphql/DarpanSchema.gql.xml), read-only, through the engine's
 * PUBLIC realm: the tenant is pinned the way an API key pins it (authOverride.scopeTenantId), never the session's.
 *
 * Two tenants own an audit with the SAME code and a statement R2 each, so every isolation assertion is about the tenant,
 * not about a name that happens to differ.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditGraphQlSmokeTests {

    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String A = "TGQL_A"
    private static final String B = "TGQL_B"
    private static final String REPORT = """{ auditReport(audit: "month-close", date: "2026-10-08") {
        schema
        report { date period running tenant { id name timeZone } audit { id name period } window { from thru } }
        health { complete summary notCovered sources { system read records readAt reason affects } checks { ran partial notRun } }
        summary { open pending newToday fixedToday oldestOpen { id statement firstFound ageDays }
                  byCategory { category open pending newToday fixedToday } }
        categories { id label counts { open pending newToday fixedToday }
          statements { id text weight status reason findingsOmitted counts { open pending }
            checks { id label status reason matchedBy graceDays compared { system count } }
            findings { id headline state firstFound ageDays next records { system recordType id internalId status }
                       difference { amount currency } } } } } }"""

    private ExecutionContext ec
    private String bucketBefore
    private String findingA

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-graphql")
        // The engine's per-caller throttle bucket would otherwise run dry across a class's queries.
        bucketBefore = System.getProperty("gql.throttle.bucketSize")
        System.setProperty("gql.throttle.bucketSize", "1000000000")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        [(A): "Tenant A", (B): "Tenant B"].each { String id, String name ->
            insert("moqui.security.UserGroup", [userGroupId: id, description: name])
            insert("darpan.auth.TenantSetting", [companyUserGroupId: id, timeZone: "America/Los_Angeles"])
            insert("darpan.audit.Audit", [auditId: "${id}_MC".toString(), companyUserGroupId: id, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            insert("darpan.audit.AuditStatement", [statementId: "${id}_R2".toString(), companyUserGroupId: id, auditId: "${id}_MC".toString(),
                    statementCode: "R2", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 2L,
                    statementText: "Every invoice and credit memo carries the Shopify order id (the Etail id)."])
            insert("darpan.audit.AuditCheck", [checkId: "${id}_INV".toString(), companyUserGroupId: id, checkCode: "invoices-without-etail-id",
                    label: "NetSuite invoices with no Etail id", graceDays: 1L])
            insert("darpan.audit.AuditStatementCheck", [checkId: "${id}_INV".toString(), statementId: "${id}_R2".toString(),
                    companyUserGroupId: id, sequenceNum: 1L])
        }
        findingA = morning(A, "INV-A1")
        morning(B, "INV-B1")
    }

    /** One morning's run of the invoice check for a tenant, finding one invoice; answers its finding id. */
    private String morning(String tenant, String invoice) {
        Map base = [companyUserGroupId: tenant, checkId: "${tenant}_INV".toString(), reportDate: Date.valueOf("2026-10-08")]
        Map run = ec.service.sync().name("${LEDGER}.record#CheckRun").parameters(base + [sourceReads: [[systemEnumId: "NETSUITE_SUITEQL",
                statusEnumId: "AUDSR_COMPLETE", recordCount: 3311L, readStamp: new java.sql.Timestamp(System.currentTimeMillis())]]]).call()
        Map found = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters(base + [findings: [[statementCode: "R2",
                recordKey: invoice, headline: "Invoice ${invoice} has no Etail id".toString(),
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "invoice", recordId: invoice, internalId: "51000940"]]]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals("AUDCS_RAN", run.checkStatusEnumId)
        return (found.findingIds as List)[0] as String
    }

    @AfterAll
    void cleanup() {
        if (bucketBefore == null) System.clearProperty("gql.throttle.bucketSize") else System.setProperty("gql.throttle.bucketSize", bucketBefore)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    /** One request through the engine's public realm, pinned to a tenant as an API key pins it. */
    private Map gql(String tenant, String query) {
        // moqui-gql loads from its component jar, through Moqui's own classloader, not the test classpath.
        assert ec.factory.getToolFactory("GraphQL") != null: "the moqui-gql component did not load"
        def engineClass = ec.factory.getClassLoader().loadClass("org.moqui.gql.GqlEngine")
        def engine = engineClass.getDeclaredConstructor(ExecutionContext).newInstance(ec)
        return (Map) engine.execute(query, null, null, [scopeTenantId: tenant, callerId: "test-${tenant}".toString()])
    }

    private static Map statementR2(Map data) {
        Map orders = ((List<Map>) data.auditReport.categories).find { it.id == "orders" }
        return ((List<Map>) orders.statements).find { it.id == "R2" }
    }

    @Test
    void theReportComesBackListShapedForTheKeysTenant() {
        Map out = gql(A, REPORT)
        assertEquals([], out.errors, "errors")
        Map doc = (Map) out.data.auditReport
        assertEquals("darpan.audit.report/v1", doc.schema)
        assertEquals([id: A, name: "Tenant A", timeZone: "America/Los_Angeles"], doc.report.tenant)
        assertEquals("2026-10-08", doc.report.date)
        assertEquals(["orders", "refundsAndCredits", "returns", "exchangesAndWarranty"], (doc.categories as List<Map>)*.id,
                "every category, in order, as a list")
        assertEquals([[system: "NetSuite", read: "complete", records: 3311]], (doc.health.sources as List<Map>).collect {
            [system: it.system, read: it.read, records: it.records] })
        assertEquals(["orders", "refundsAndCredits", "returns", "exchangesAndWarranty"], (doc.summary.byCategory as List<Map>)*.category)
        Map r2 = statementR2((Map) out.data)
        assertEquals(["invoices-without-etail-id"], (r2.checks as List<Map>)*.id)
        assertEquals([[system: "NetSuite", count: 3311]], r2.checks[0].compared)
        Map finding = (r2.findings as List<Map>)[0]
        assertEquals([findingA, "Invoice INV-A1 has no Etail id", "pending"], [finding.id, finding.headline, finding.state])
        assertEquals([[system: "NetSuite", recordType: "invoice", id: "INV-A1", internalId: "51000940", status: null]], finding.records)
        assertTrue(doc.health.notCovered instanceof List)
    }

    @Test
    void anotherKeyWithTheSameAuditCodeSeesOnlyItsOwnTenant() {
        Map b = statementR2((Map) gql(B, REPORT).data)
        assertEquals(["Invoice INV-B1 has no Etail id"], (b.findings as List<Map>)*.headline)
        // A's finding by id, asked with B's key: not found, and nothing of A's comes back.
        Map out = gql(B, """{ auditFinding(id: "${findingA}") { finding { id headline } } }""")
        assertNull(out.data?.auditFinding)
        assertTrue(out.errors.toString().contains("not found"), out.errors.toString())
        assertFalse(out.toString().contains("INV-A1"))
    }

    @Test
    void aFindingReadsWithItsRecordsAsAList() {
        Map out = gql(A, """{ auditFinding(id: "${findingA}") { schema finding { id headline period
            audit { id } category { id } statement { id weight } check { id }
            status { state firstFound timesSeen } records { system recordType id internalId }
            fix { darpanChanges verify { by passesWhen } } history { event by { type id } } status { closedBy { type } } } access { mode } } }""")
        assertEquals([], out.errors, "errors")
        Map f = (Map) out.data.auditFinding.finding
        assertEquals([findingA, "R2", "orders", "pending"], [f.id, f.statement.id, f.category.id, f.status.state])
        assertEquals([[system: "NetSuite", recordType: "invoice", id: "INV-A1", internalId: "51000940"]], f.records)
        assertEquals(["found"], (f.history as List<Map>)*.event)
        assertEquals("read-only", out.data.auditFinding.access.mode)
        assertNull(f.status.closedBy, "open finding: no closer")
    }

    @Test
    void noTenantMeansNoServiceCallAndNoData() {
        Map out = gql("", REPORT)
        assertNull(out.data?.auditReport)
        // Not even a "not found": with no tenant the service is never called.
        assertEquals([], out.errors)
    }

    @Test
    void aCallerCannotNameTheTenant() {
        Map out = gql(A, """{ auditReport(audit: "month-close", companyUserGroupId: "${B}") { schema } }""")
        assertNull(out.data?.auditReport)
        assertTrue(out.errors.toString().contains("companyUserGroupId"), out.errors.toString())
    }

    @Test
    void aMutationIsRejected() {
        Map out = gql(A, """mutation { auditReport(audit: "month-close") { schema } }""")
        assertFalse(((List) out.errors).isEmpty(), "a mutation must not run")
        assertNull(out.data)
    }

    @Test
    void theFullReportQueryStaysUnderTheCostCeiling() {
        Map out = gql(A, REPORT)
        long cost = out.extensions.cost.requestedQueryCost as long
        assertTrue(cost < (System.getProperty("gql.maxCost", "1000") as long), "cost ${cost}")
        assertFalse(out.errors.toString().contains("COST_EXCEEDED") || out.errors.toString().contains("DEPTH_EXCEEDED"), out.errors.toString())
    }

    @Test
    void anUnknownAuditIsAnErrorNotANull() {
        Map out = gql(A, """{ auditReport(audit: "no-such-audit") { schema } }""")
        assertTrue(out.errors.toString().contains("Audit no-such-audit was not found"), out.errors.toString())
    }

    @Test
    void anInternalErrorIsGenericButAPublicOneIsWordForWord() {
        // An empty id passes GraphQL (ID!) but fails the service's own required-parameter check: a framework message,
        // which must not reach a public caller.
        Map out = gql(A, '{ auditFinding(id: "") { schema } }')
        String errors = out.errors.toString()
        assertTrue(errors.contains("could not be completed"), errors)
        assertFalse(errors.toLowerCase().contains("required") || errors.contains("findingId") || errors.contains("Field"), errors)
        // A message the service marks as public reads as written.
        assertTrue(gql(A, '{ auditReport(audit: "month-close", date: "2026-13-45") { schema } }').errors.toString()
                .contains("date must be YYYY-MM-DD"))
    }

    @Test
    void anErrorAlreadyOnTheContextIsNotBlamedOnTheQuery() {
        ec.message.addError("an earlier caller's error")
        Map out = gql(A, '{ auditReport(audit: "month-close", date: "2026-10-08") { schema } }')
        assertEquals([], out.errors)
        assertEquals("darpan.audit.report/v1", out.data.auditReport.schema)
        assertEquals(["an earlier caller's error"], ec.message.errors as List)
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
