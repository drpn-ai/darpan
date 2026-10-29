package darpan.reconciliation

import darpan.audit.AuditReportAssembler

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
 * DAR-BE-157: gorjana's NetSuite <-> HotWax product run, a plain two-way saved run (Aditi 2026-10-29: "product check is a
 * simple 2-way check.. not as part of the audit"). It compares NetSuite's ACTIVE_ITEMS read
 * (every non-inactive stock item and kit, live or quiet) with HotWax's sellable products keyed by their NetSuite id, and
 * names why each gap exists. Quiet NetSuite items are excluded on FILE_1, so a quiet item HotWax does carry closes as
 * expected ("Quiet in NetSuite") instead of reading as missing in NetSuite (1,078 such on gorjana, 2026-10-28).
 * Pure: the data file and the production conclusion engine, no Moqui.
 */
class GorjanaProductPresenceRunDataTests {

    private static final String FILE = "GorjanaProductPresenceRunData.xml"
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
        return concluded(bucket, file1, file2).code
    }

    /** The engine's conclusion, then its cause's fix as the audit report fills it (AuditReportAssembler.causeFix). */
    private static String fixOf(String bucket, Map file1, Map file2) {
        Map c = concluded(bucket, file1, file2)
        Map rule = scoped("darpan.rule.RuleSetConclusionRule").find { it.conclusionEnumId == c.code }
        List<Map> evidence = (c.checks as List<Map>).collect { [label: it.label, checkValue: it.value as String] }
        return AuditReportAssembler.causeFix(rule.fixActionTemplate as String, scoped("darpan.rule.RuleSetConclusionCondition")
                .findAll { it.sequenceNum == rule.sequenceNum }, evidence, [recordKey: (file1?.record?.id ?: file2?.record?.nsKey) as String])
    }

    private static Map concluded(String bucket, Map file1, Map file2) {
        Map<String, String> labels = rows(FILE, "moqui.basic.Enumeration")
                .collectEntries { [(it.attribute("enumId") as String): it.attribute("description") as String] }
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(RunConclusionStep.buildRules(
                scoped("darpan.rule.RuleSetConclusionRule"), scoped("darpan.rule.RuleSetConclusionCondition"), labels))
        try {
            return engine.conclude([[bucket: bucket, sides: [FILE_1: file1, FILE_2: file2]]])[0] as Map
        } finally {
            engine.close()
        }
    }

    private static Map item(String id, String live) { [id: id, sku: "SKU-${id}".toString(), itemType: "InvtPart", live: live] }
    private static Map product(String nsKey, String state) {
        [nsKey: nsKey, productId: "10056", internalName: "2210-013a-G-OLD", netsuiteIdState: state, sellable: "Y"]
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
        assertEquals(2, filters.size())
        assertEquals([fileSide: "FILE_1", fieldExpression: "live", operator: "EXCLUDE_IN", filterValues: "N"],
                filters.find { it.fileSide == "FILE_1" }.subMap(["fileSide", "fieldExpression", "operator", "filterValues"]))
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
        // Aditi 2026-10-29: "Active Status: Discontinued for SKU 193-102-G in NS ... not expected" in HotWax (16 of the 53).
        assertEquals("CONC_PRODUCT_DISCONTINUED_IN_NS", conclude("MISSING_FROM_FILE_2", kept(item("12777", "Y") + [activeStatus: "Discontinued"]), ABSENT))
        assertEquals("CONC_PRODUCT_DISCONTINUED_IN_NS", conclude("MISSING_FROM_FILE_2", kept(item("856", "Y") + [activeStatus: "Discontinued"]),
                excluded(product("856", "OWN") + [sellable: "N", notSellableReason: "DIGITAL_GOOD"])), "discontinued in NetSuite wins over not sellable in HotWax")
        assertEquals("CONC_PRODUCT_NOT_IN_HOTWAX", conclude("MISSING_FROM_FILE_2", kept(item("43649", "Y") + [activeStatus: "Active: Sell-Down"]), ABSENT),
                "a sell-down item is still sold")
        // A non-inventory item HotWax lacks is NetSuite's own (POS Tax Variance, Warranties Variance, donations): expected.
        assertEquals("CONC_PRODUCT_NS_ONLY_NON_INVENTORY", conclude("MISSING_FROM_FILE_2", kept(item("34838", "Y") + [itemType: "NonInvtPart"]), ABSENT))
        assertEquals("CONC_PRODUCT_QUIET_IN_NS", conclude("MISSING_FROM_FILE_1", excluded(item("30000", "N")), kept(product("30000", "OWN"))),
                "HotWax carries a quiet NetSuite item")
        assertEquals("CONC_PRODUCT_NO_NS_ID", conclude("MISSING_FROM_FILE_1", ABSENT, kept(product("HOTWAX-ONLY:10056", "NONE"))))
        assertEquals("CONC_PRODUCT_NS_ID_SHARED", conclude("MISSING_FROM_FILE_1", ABSENT, kept(product("DUPLICATE:43000:10200", "SHARED"))),
                "the cause holds even when a field its fix names is blank")
        assertEquals("UNEXPLAINED", conclude("MISSING_FROM_FILE_1", ABSENT, kept(product("X", "NEW_STATE"))), "only an OWN key reads as gone")
        assertEquals("CONC_PRODUCT_NS_ITEM_GONE", conclude("MISSING_FROM_FILE_1", ABSENT, kept(product("99999", "OWN"))),
                "its NetSuite id is not an active stock item or kit")
    }

    @Test
    void itIsASavedRunNotAnAuditCheck() {
        assertEquals("darpan-tenant-config", new XmlParser(false, false).parse(dataDir().resolve(FILE).toFile()).attribute("type"))
        assertEquals("GORJANA_PRODUCT_PRESENCE_RS", rows(FILE, "darpan.rule.RuleSet")[0].attribute("ruleSetId"))
        ["darpan.audit.AuditCheck", "darpan.audit.AuditStatementCheck", "darpan.audit.AuditStatement"].each {
            assertTrue(rows(FILE, it).isEmpty(), "${it}: the product run is not part of the audit")
        }
        assertTrue(rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.AuditStatement").every { it.attribute("statementId") != "GORJANA_R22" })
    }

    // Reviewer 2026-10-28: a NetSuite item HotWax has only as a discontinued, virtual or digital product read "Sync it to HotWax".
    @Test
    void aLiveItemHotWaxHasOnlyAsANonSellableProductIsNamed() {
        Map hw = product("42808", "OWN") + [sellable: "N", notSellableReason: "discontinued"]
        assertEquals("CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX", conclude("MISSING_FROM_FILE_2", kept(item("42808", "Y")), excluded(hw)))
        assertEquals("CONC_PRODUCT_NS_ONLY_NON_INVENTORY", conclude("MISSING_FROM_FILE_2",
                kept(item("22571", "Y") + [itemType: "NonInvtPart"]), excluded(hw + [notSellableReason: "DIGITAL_GOOD"])),
                "a NetSuite accounting item HotWax holds as a digital good stays NetSuite's own")
        Map filter = scoped("darpan.rule.RuleSetCompareSourceFilter").find { it.fileSide == "FILE_2" }
        assertEquals([fieldExpression: "sellable", operator: "EXCLUDE_IN", filterValues: "N"], filter.subMap(["fieldExpression", "operator", "filterValues"]))
    }

    // Reviewer 2026-10-28: a fix may only name what its conditions read (causeFix); every placeholder must fill.
    @Test
    void everyCausesFixIsFilled() {
        Map hw = product("HOTWAX-ONLY:10056", "NONE")
        assertEquals("Give HotWax product 10056 (2210-013a-G-OLD) its NetSuite id, or retire it in HotWax.",
                fixOf("MISSING_FROM_FILE_1", ABSENT, kept(hw)))
        Map shared = product("DUPLICATE:43000:10200", "SHARED") + [productId: "10200", netsuiteProductId: "43000", sharedWith: "10100"]
        assertEquals("NetSuite id 43000 is on HotWax products 10200 (2210-013a-G-OLD) and 10100: keep it on the product that is that NetSuite item and remove it from the others.",
                fixOf("MISSING_FROM_FILE_1", ABSENT, kept(shared)))
        assertEquals("Sync NetSuite item 42808 (SKU-42808) to HotWax, then re-import anything HotWax refused for it (transfer orders).",
                fixOf("MISSING_FROM_FILE_2", kept(item("42808", "Y")), ABSENT))
        Map nonSellable = product("42808", "OWN") + [sellable: "N", notSellableReason: "discontinued"]
        String notSellable = fixOf("MISSING_FROM_FILE_2", kept(item("42808", "Y")), excluded(nonSellable))
        assertEquals("NetSuite item 42808 (SKU-42808) was used in NetSuite this year, but HotWax product 10056 is discontinued: make it sellable in HotWax, or inactive in NetSuite.", notSellable)
        [fixOf("MISSING_FROM_FILE_1", excluded(item("30000", "N")), kept(product("30000", "OWN"))),
         fixOf("MISSING_FROM_FILE_1", ABSENT, kept(product("99999", "OWN"))),
         fixOf("MISSING_FROM_FILE_2", kept(item("34838", "Y") + [itemType: "NonInvtPart"]), ABSENT)].each {
            assertTrue(!(it =~ /\{[A-Za-z]/).find(), "unfilled placeholder: ${it}")
        }
    }

    // Aditi 2026-10-29: expected rows do not show ("Hide them"): the three not-needed causes drop out of the result.
    @Test
    void theNotNeededCausesAreHidden() {
        Map<String, String> outcome = scoped("darpan.rule.RuleSetConclusionRule").collectEntries { [(it.conclusionEnumId): it.outcome] }
        assertEquals("HIDE", outcome.CONC_PRODUCT_QUIET_IN_NS)
        assertEquals("HIDE", outcome.CONC_PRODUCT_DISCONTINUED_IN_NS)
        assertEquals("HIDE", outcome.CONC_PRODUCT_NS_ONLY_NON_INVENTORY)
        ["CONC_PRODUCT_NOT_IN_HOTWAX", "CONC_PRODUCT_NO_NS_ID", "CONC_PRODUCT_NS_ID_SHARED", "CONC_PRODUCT_NS_ITEM_GONE",
         "CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX"].each { assertEquals(null, outcome[it], "${it} needs fixing, so it shows") }
    }
}
