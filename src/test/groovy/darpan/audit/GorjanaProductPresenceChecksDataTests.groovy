package darpan.audit

import darpan.facade.reconciliation.RunConclusionStep
import darpan.reconciliation.conclusion.ConclusionTreeEngine
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * DAR-BE-157, R22: every active product is in both NetSuite and HotWax. The check compares NetSuite's ACTIVE_ITEMS read
 * (every non-inactive stock item and kit, live or quiet) with HotWax's sellable products keyed by their NetSuite id, and
 * names why each gap exists. Quiet NetSuite items are excluded on FILE_1, so a quiet item HotWax does carry closes as
 * expected ("Quiet in NetSuite") instead of reading as missing in NetSuite (1,078 such on gorjana, 2026-10-28).
 * Pure: the data file and the production conclusion engine, no Moqui.
 */
class GorjanaProductPresenceChecksDataTests {

    private static final String FILE = "GorjanaProductPresenceChecksData.xml"
    private static final String SCOPE = "GORJANA_PRODUCT_PRESENCE_SCOPE"

    private static Path dataDir() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data")
    }

    private static List<Node> rows(String file, String elementName) {
        Node doc = new XmlParser(false, false).parse(dataDir().resolve(file).toFile())
        return doc.children().findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    private static List<Map> scoped(String elementName) {
        return rows(FILE, elementName).findAll { it.attribute("compareScopeId") == SCOPE }.collect { Node n ->
            Map m = new LinkedHashMap(n.attributes())
            ["sequenceNum", "conditionSeq"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
            m
        }
    }

    private static Map kept(Map record) { [presence: "KEPT", record: record, firstFieldPresence: "KEPT", firstFieldRecord: record] }
    private static Map excluded(Map record) { [presence: "EXCLUDED", record: record, firstFieldPresence: "EXCLUDED", firstFieldRecord: record] }
    private static final Map ABSENT = [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]

    private static String conclude(String bucket, Map file1, Map file2) {
        Map<String, String> labels = rows(FILE, "moqui.basic.Enumeration")
                .collectEntries { [(it.attribute("enumId") as String): it.attribute("description") as String] }
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(RunConclusionStep.buildRules(
                scoped("darpan.rule.RuleSetConclusionRule"), scoped("darpan.rule.RuleSetConclusionCondition"), labels))
        try {
            return engine.conclude([[bucket: bucket, sides: [FILE_1: file1, FILE_2: file2]]])[0].code
        } finally {
            engine.close()
        }
    }

    private static Map item(String id, String live) { [id: id, sku: "SKU-${id}".toString(), itemType: "InvtPart", live: live] }
    private static Map product(String nsKey, String state) {
        [nsKey: nsKey, productId: "10056", internalName: "2210-013a-G-OLD", netsuiteIdState: state]
    }

    @Test
    void theScopeComparesActiveItemsWithSellableProducts() {
        Map scope = scoped("darpan.rule.RuleSetCompareScope")[0]
        assertEquals("COMPARE", scope.scopeMode)
        Map<String, Map> sources = scoped("darpan.rule.RuleSetCompareSource").collectEntries { [(it.fileSide): it] }
        assertEquals("NETSUITE_SUITEQL", sources.FILE_1.systemEnumId)
        assertEquals("NS_ACTIVE_ITEMS", sources.FILE_1.sourceConfigId)
        assertEquals("OMS_PRODUCTS_GQL", sources.FILE_2.systemEnumId)
        assertEquals("HOTWAX_OMS_GQL_PRODUCTS", sources.FILE_2.sourceConfigType)
        Map<String, String> keys = scoped("darpan.rule.RuleSetCompareSourceKeyField").collectEntries { [(it.fileSide): it.fieldExpression] }
        assertEquals([FILE_1: "id", FILE_2: "nsKey"], keys)
        List<Map> filters = scoped("darpan.rule.RuleSetCompareSourceFilter")
        assertEquals(1, filters.size())
        assertEquals([fileSide: "FILE_1", fieldExpression: "live", operator: "EXCLUDE_IN", filterValues: "N"],
                filters[0].subMap(["fileSide", "fieldExpression", "operator", "filterValues"]))
    }

    @Test
    void theNetsuiteQueryIsTheActiveItemsTemplate() {
        // Client config lives in the wrapper checkout, beside the other gorjana queries; a component-only checkout skips.
        String queries = "../../../../../tools/netsuite-chain-config/gorjana-product-queries.xml"
        assumeTrue(dataDir().resolve(queries).toFile().exists(), "no wrapper checkout")
        Node q = rows(queries, "NsSuiteQlSourceQuery")
                .find { it.attribute("nsSuiteQlSourceQueryId") == "NS_ACTIVE_ITEMS" }
        assertTrue(q != null, "no NS_ACTIVE_ITEMS query")
        assertEquals("ACTIVE_ITEMS", q.attribute("queryTemplate"))
        assertEquals("item", q.attribute("fromTable"))
        assertEquals("id", q.attribute("joinKeyFieldName"))
        assertEquals("GORJANA", q.attribute("companyUserGroupId"))
    }

    @Test
    void eachGapTakesItsCause() {
        assertEquals("CONC_PRODUCT_NOT_IN_HOTWAX", conclude("MISSING_FROM_FILE_2", kept(item("42808", "Y")), ABSENT), "live, not in HotWax")
        assertEquals("CONC_PRODUCT_QUIET_IN_NS", conclude("MISSING_FROM_FILE_1", excluded(item("30000", "N")), kept(product("30000", "OWN"))),
                "HotWax carries a quiet NetSuite item")
        assertEquals("CONC_PRODUCT_NO_NS_ID", conclude("MISSING_FROM_FILE_1", ABSENT, kept(product("HOTWAX-ONLY:10056", "NONE"))))
        assertEquals("CONC_PRODUCT_NS_ID_SHARED", conclude("MISSING_FROM_FILE_1", ABSENT, kept(product("DUPLICATE:43000:10200", "SHARED"))))
        assertEquals("CONC_PRODUCT_NS_ITEM_GONE", conclude("MISSING_FROM_FILE_1", ABSENT, kept(product("99999", "OWN"))),
                "its NetSuite id is not an active stock item or kit")
    }

    @Test
    void theStatementCountsTheFixesAndNotTheQuietItems() {
        Node st = rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.AuditStatement").find { it.attribute("statementId") == "GORJANA_R22" }
        assertTrue(st != null, "no GORJANA_R22")
        assertEquals("AUDC_ORDERS", st.attribute("categoryEnumId"))
        assertEquals("Every active product is in both NetSuite and HotWax.", st.attribute("statementText"))
        Node link = rows(FILE, "darpan.audit.AuditStatementCheck")[0]
        assertEquals("GORJANA_R22", link.attribute("statementId"))
        assertEquals("MISSING_IN_FILE_1,MISSING_IN_FILE_2", link.attribute("diffTypes"))
        List<String> counted = (link.attribute("conclusionCodes") as String).split(",")*.trim()
        assertEquals(["CONC_PRODUCT_NOT_IN_HOTWAX", "CONC_PRODUCT_NO_NS_ID", "CONC_PRODUCT_NS_ID_SHARED", "CONC_PRODUCT_NS_ITEM_GONE", "UNEXPLAINED"], counted)
        Node check = rows(FILE, "darpan.audit.AuditCheck")[0]
        assertEquals("products-not-in-both", check.attribute("checkCode"))
        assertEquals("GORJANA_PRODUCT_PRESENCE_RS", check.attribute("savedRunId"))
        assertEquals(null, check.attribute("automationId"), "automations are made per environment")
        assertEquals("darpan-tenant-config", new XmlParser(false, false).parse(dataDir().resolve(FILE).toFile()).attribute("type"))
    }
}
