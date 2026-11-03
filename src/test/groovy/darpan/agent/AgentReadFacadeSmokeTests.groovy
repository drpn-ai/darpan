package darpan.agent

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-159. The agent's read tools as services of the signed-in user's active tenant: look-alike findings, the brain
 * catalog, one-record inspection and knowledge search. Tenants and rows are synthetic; another tenant's rows never show.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentReadFacadeSmokeTests {

    private static final String FACADE = "facade.AgentReadFacadeServices"
    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String MINE = "KREWE"
    private static final String OTHER = "AGR_OTHER"

    private ExecutionContext ec
    private String f1, f2, f3, fNoCause, otherFinding

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-read-facade")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
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
                insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "${t}_AUTO".toString(), automationName: "${t}_AUTO".toString(),
                        companyUserGroupId: t, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "${t}_RUN".toString()])
                insert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: "${t}_AUTO".toString(), fileSide: "FILE_1",
                        companyUserGroupId: t, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL"])
                insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "${t}_EX".toString(),
                        automationId: "${t}_AUTO".toString(), companyUserGroupId: t, statusEnumId: "AUT_STAT_SUCCESS",
                        file1DataManagerPath: "reconciliation-runs/${t}/ns.json".toString(), file1RecordCount: 10L])
            }
            insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
            ["AGR_C1": "Backordered", "AGR_C2": "Voided"].each { String id, String label ->
                insert("moqui.basic.Enumeration", [enumId: id, enumTypeId: "DarpanConclusion", enumCode: id, description: label])
            }
            seedInspectFixtures()
            f1 = morning(MINE, ["INV-1", "INV-2", "INV-3", "INV-4"])
            def ids = findingIds(MINE)
            (f1, f2, f3, fNoCause) = ids
            conclude(f1, "AGR_C1"); conclude(f2, "AGR_C1"); conclude(f3, "AGR_C2")
            otherFinding = findingIds(OTHER, morning(OTHER, ["INV-O1", "INV-O2"]))[0]
            conclude(otherFinding, "AGR_C1")
            conclude(findingIds(OTHER)[1], "AGR_C2")
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private List<String> findingIds(String tenant, Object ignored = null) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenant).orderBy("recordKey").useCache(false).list()*.findingId
    }

    private String morning(String tenant, List<String> invoices) {
        Map base = [companyUserGroupId: tenant, checkId: "${tenant}_INV".toString(), reportDate: Date.valueOf("2026-10-08")]
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters(base + [automationExecutionId: "${tenant}_EX".toString(), sourceReads: [[systemEnumId: "NETSUITE_SUITEQL",
                statusEnumId: "AUDSR_COMPLETE", recordCount: 10L, readStamp: new java.sql.Timestamp(System.currentTimeMillis())]]]).call()
        ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters(base + [findings: invoices.collect { String inv -> [statementCode: "R2",
                recordKey: inv, headline: "Invoice ${inv} has no Etail id".toString(),
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "invoice", recordId: inv,
                           recordData: [status: "Billed", customerName: "Ann Lee", email: "ann@example.com", total: 12.5, tranDate: "2026-10-02"]]]] }]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return "ok"
    }

    private void conclude(String findingId, String code) {
        ec.entity.find("darpan.audit.AuditFinding").condition("findingId", findingId).one().set("conclusionEnumId", code).update()
    }

    private void insert(String entity, Map fields) { ec.entity.makeValue(entity).setAll(fields).createOrUpdate() }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void clearMessages() { ec.message.clearErrors() }

    private Map call(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    @Test
    void "lookalikes return same-cause and control findings of the active tenant only"() {
        Map out = call("find#AuditFindingLookalikes", [findingId: f1])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals([f2], (out.sameCause as List<Map>)*.findingId, "same check and conclusion, not itself, not the other tenant's")
        assertEquals(["INV-2"], (out.sameCause as List<Map>)*.recordKey)
        Set<String> controls = ((out.controls as List<Map>)*.findingId) as Set
        assertEquals([f3, fNoCause] as Set, controls, "same check, different conclusion (blank included)")
        Map row = (out.sameCause as List<Map>)[0]
        assertEquals("AGR_C1", row.conclusionCode)
        assertEquals("R2", row.statementCode)
        assertNotNull(row.state)
        assertTrue(row.conditionValues instanceof List)
        assertFalse(out.toString().contains(otherFinding), "nothing of the other tenant")

        Map foreign = call("find#AuditFindingLookalikes", [findingId: otherFinding])
        assertTrue(foreign.hadError as boolean, "another tenant's finding reads as not found")
        assertTrue((foreign.errorText as String).contains("was not found"), foreign.errorText as String)
    }

    @Test
    void "get#AgentFinding wraps the finding with customer data redacted"() {
        Map out = call("get#AgentFinding", [findingId: f1])
        assertFalse(out.hadError as boolean, out.errorText as String)
        String text = out.finding.toString()
        assertFalse(text.contains("ann@example.com"), text)
        assertFalse(text.contains("Ann Lee"), text)
        assertTrue(text.contains("Billed"), text)
        Map foreign = call("get#AgentFinding", [findingId: otherFinding])
        assertTrue(foreign.hadError as boolean)
    }

    @Test
    void "inspect#AgentRecord refuses an unknown readId"() {
        Map out = call("inspect#AgentRecord", [readId: "NO_SUCH_READ", key: "OR1"])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("NO_SUCH_READ"), out.errorText as String)
        assertNull(out.record)
    }

    // ---- inspect#AgentRecord over a made-up system (no system repo): one definition, one connector, one config per tenant

    static final String DOCS = '''query AgentDocs($filter: String!, $first: Int, $after: String) {
  docs(filter: $filter, first: $first, after: $after) { nodes { docId status total customerEmail note } page { more next } }
}'''
    static final List<Map> DOC_ROWS = [[docId: "D1", status: "PAID", total: 12.5, customerEmail: "ann@example.com", note: "please leave at the door"],
                                       [docId: "D2", status: "OPEN", total: 3, customerEmail: "bo@example.com", note: "x"]]

    private static Map docsDefinition() {
        return [connection: [urlTemplate: "{endpointUrl}/graphql", auth: [kindField: "authType", kinds: [TOKEN: [method: "BEARER", tokenFields: ["apiToken"]]]]],
                limits: [maxCostPerCall: 100],
                steps: [[id: "docs", kind: "query", document: DOCS, connectionAt: "docs", recordsAt: "nodes[*]", hasNextAt: "page.more", cursorAt: "page.next",
                         bind: [ids: [chunk: 5, separator: ",", variables: [filter: "{keyField} in ({ids})"], from: "LOOKUP_KEYS", defaultKeyField: "docId", idPattern: "[A-Z0-9]{1,10}"]],
                         paging: [firstVariable: "first", afterVariable: "after", pageSize: 5], reservation: 10]],
                record: [fields: ["docId", "status", "total", "customerEmail", "note"], keepRecordFields: true]]
    }

    private void seedInspectFixtures() {
        insert("darpan.datasource.DataSourceDefinition", [dataSourceDefinitionId: "AGR_DOCS_V1", definitionName: "Agent docs", protocol: "GRAPHQL",
                definitionVersion: 1L, statusId: "ACTIVE", definitionJson: groovy.json.JsonOutput.toJson(docsDefinition())])
        insert("darpan.reconciliation.SourceSystemConnector", [systemEnumId: "AGR_DOCS", extractServiceName: darpan.datasource.DataDefinedExtract.SERVICE_NAME,
                expectedSourceConfigType: "SCFG_NS_RESTLET", configParameterName: "nsRestletConfigId", configEntityName: "darpan.reconciliation.NsRestletConfig",
                dataSourceDefinitionId: "AGR_DOCS_V1", enabled: "Y"])
        insert("darpan.agent.AgentReadCatalog", [readId: "AGR_DOC_BY_ID", definitionId: "AGR_DOCS_V1", system: "NETSUITE", recordKind: "doc",
                lookupKeyField: "docId", allowedFields: "docId,status,total,note", description: "test"])
        insert("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "AGR_CFG_MINE", companyUserGroupId: MINE, endpointUrl: "https://acme.test",
                authType: "TOKEN", apiToken: "t", isActive: "Y"])
        insert("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "AGR_CFG_OTHER", companyUserGroupId: OTHER, endpointUrl: "https://other.test",
                authType: "TOKEN", apiToken: "t", isActive: "Y"])
    }

    @Test
    void "inspect#AgentRecord reads one key through the catalog definition, drops other fields and fences free text"() {
        def previous = darpan.datasource.DataDefinedExtract.transportFactory
        List<Map> calls = []
        darpan.datasource.DataDefinedExtract.transportFactory = { Map d, Map conn ->
            [execute: { String doc, Map vars, int reservation ->
                calls << [filter: vars.filter, endpoint: conn.endpointUrl]
                List<Map> rows = DOC_ROWS.findAll { vars.filter.toString().contains(it.docId.toString()) }.collect { new LinkedHashMap(it) }
                [docs: [nodes: rows, page: [more: false, next: null]]]
            }]
        }
        try {
            Map out = call("inspect#AgentRecord", [readId: "AGR_DOC_BY_ID", key: "D1"])
            assertFalse(out.hadError as boolean, out.errorText as String)
            assertEquals(true, out.found)
            assertEquals([docId: "D1", status: "PAID", total: 12.5, note: "[untrusted_customer_text: please leave at the door]"], out.record)
            assertEquals([[filter: "docId in (D1)", endpoint: "https://acme.test"]], calls, "the signed-in tenant's own config, one call")
            assertEquals([], ec.entity.find("darpan.agent.AgentTurn").list(), "nothing written")
            Map missing = call("inspect#AgentRecord", [readId: "AGR_DOC_BY_ID", key: "D9"])
            assertFalse(missing.hadError as boolean)
            assertEquals(false, missing.found)
            Map bad = call("inspect#AgentRecord", [readId: "AGR_DOC_BY_ID", key: "d 1; drop"])
            assertTrue(bad.hadError as boolean, "a key outside the definition's id pattern is refused")
        } finally {
            darpan.datasource.DataDefinedExtract.transportFactory = previous
        }
    }

    @Test
    void "get#AgentBrainCatalog is byte-identical across two calls"() {
        Map a = call("get#AgentBrainCatalog")
        Map b = call("get#AgentBrainCatalog")
        assertFalse(a.hadError as boolean, a.errorText as String)
        String ja = groovy.json.JsonOutput.toJson([a.conclusions, a.rules, a.statements])
        assertEquals(ja, groovy.json.JsonOutput.toJson([b.conclusions, b.rules, b.statements]))
        assertTrue((a.conclusions as List<Map>)*.id.containsAll(["AGR_C1", "AGR_C2"]))
        assertFalse((a.rules as List).isEmpty(), "the brain's rules")
        assertEquals([[statementCode: "R2", audit: "month-close", text: "Every invoice carries the Etail id.", checkCodes: ["invoices-without-etail-id"]]],
                a.statements, "this tenant's statements only")
    }

    @Test
    void "search#AgentKnowledge answers hits and caps the limit"() {
        Map out = call("search#AgentKnowledge", [words: "returns", limit: 3])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertTrue(out.hits instanceof List)
        assertTrue((out.hits as List).size() <= 3)
    }
}
