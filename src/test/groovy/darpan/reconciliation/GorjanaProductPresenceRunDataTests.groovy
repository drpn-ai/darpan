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
 * DAR-BE-157: gorjana's NetSuite <-> HotWax product runs, plain two-way saved runs (Aditi 2026-10-29: "product check is a
 * simple 2-way check.. not as part of the audit"). Both compare NetSuite's ACTIVE_ITEMS read (quiet items excluded) with
 * HotWax's sellable products keyed by their NetSuite id; each reports only its own causes (Aditi 2026-10-29): the DAILY run
 * a sellable NetSuite item HotWax lacks, the WEEKLY run a non-sellable one and one NetSuite id on several HotWax products.
 * Sellable = NetSuite Subgroup Fashion / Fine / Core / Accessories / Home Décor. Everything else is hidden.
 * Pure: the data file and the production conclusion engine, no Moqui.
 */
class GorjanaProductPresenceRunDataTests {

    private static final String FILE = "GorjanaProductPresenceRunData.xml"
    private static final String DAILY = "GORJANA_PRODUCT_NOT_IN_HOTWAX_SCOPE"
    private static final String WEEKLY = "GORJANA_PRODUCT_PRESENCE_SCOPE"

    private static Path dataDir() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data")
    }

    private static List<Node> rows(String file, String elementName) {
        Node doc = new XmlParser(false, false).parse(dataDir().resolve(file).toFile())
        return doc.children().findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    private static List<Map> scoped(String scope, String elementName) {
        return rows(FILE, elementName).findAll { it.attribute("compareScopeId") == scope }.collect { Node n ->
            Map m = new LinkedHashMap(n.attributes())
            ["sequenceNum", "conditionSeq"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
            m
        }
    }

    private static Map kept(Map record) { [presence: "KEPT", record: record, firstFieldPresence: "KEPT", firstFieldRecord: record] }
    private static Map excluded(Map record) { [presence: "EXCLUDED", record: record, firstFieldPresence: "EXCLUDED", firstFieldRecord: record] }
    private static final Map ABSENT = [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]

    private static String conclude(String scope, String bucket, Map file1, Map file2) {
        return concluded(scope, bucket, file1, file2).code
    }

    /** The engine's conclusion, then its cause's fix as the audit report fills it (AuditReportAssembler.causeFix). */
    private static String fixOf(String scope, String bucket, Map file1, Map file2) {
        Map c = concluded(scope, bucket, file1, file2)
        Map rule = scoped(scope, "darpan.rule.RuleSetConclusionRule").find { it.conclusionEnumId == c.code }
        List<Map> evidence = (c.checks as List<Map>).collect { [label: it.label, checkValue: it.value as String] }
        return AuditReportAssembler.causeFix(rule.fixActionTemplate as String, scoped(scope, "darpan.rule.RuleSetConclusionCondition")
                .findAll { it.sequenceNum == rule.sequenceNum }, evidence, [recordKey: (file1?.record?.id ?: file2?.record?.nsKey) as String])
    }

    private static Map concluded(String scope, String bucket, Map file1, Map file2) {
        Map<String, String> labels = rows(FILE, "moqui.basic.Enumeration")
                .collectEntries { [(it.attribute("enumId") as String): it.attribute("description") as String] }
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(RunConclusionStep.buildRules(
                scoped(scope, "darpan.rule.RuleSetConclusionRule"), scoped(scope, "darpan.rule.RuleSetConclusionCondition"), labels))
        try {
            return engine.conclude([[bucket: bucket, sides: [FILE_1: file1, FILE_2: file2]]])[0] as Map
        } finally {
            engine.close()
        }
    }

    private static Map item(String id, String subgroup) {
        [id: id, sku: "SKU-${id}".toString(), itemType: "InvtPart", live: "Y", subgroup: subgroup]
    }
    private static Map product(String nsKey, String state) {
        [nsKey: nsKey, productId: "10056", internalName: "2210-013a-G-OLD", netsuiteIdState: state, sellable: "Y"]
    }

    @Test
    void bothRunsCompareActiveItemsWithSellableProducts() {
        [DAILY, WEEKLY].each { String scope ->
            assertEquals("COMPARE", scoped(scope, "darpan.rule.RuleSetCompareScope")[0].scopeMode, scope)
            Map<String, Map> sources = scoped(scope, "darpan.rule.RuleSetCompareSource").collectEntries { [(it.fileSide): it] }
            assertEquals("NETSUITE_SUITEQL", sources.FILE_1.systemEnumId)
            assertEquals("NS_ACTIVE_ITEMS", sources.FILE_1.sourceConfigId)
            assertEquals("OMS_PRODUCTS_GQL", sources.FILE_2.systemEnumId)
            assertEquals("HOTWAX_OMS_GQL_PRODUCTS", sources.FILE_2.sourceConfigType)
            Map<String, String> keys = scoped(scope, "darpan.rule.RuleSetCompareSourceKeyField").collectEntries { [(it.fileSide): it.fieldExpression] }
            assertEquals([FILE_1: "id", FILE_2: "nsKey"], keys)
            Map<String, Map> filters = scoped(scope, "darpan.rule.RuleSetCompareSourceFilter").collectEntries { [(it.fileSide): it] }
            assertEquals([fieldExpression: "live", operator: "EXCLUDE_IN", filterValues: "N"], filters.FILE_1.subMap(["fieldExpression", "operator", "filterValues"]))
            assertEquals([fieldExpression: "sellable", operator: "EXCLUDE_IN", filterValues: "N"], filters.FILE_2.subMap(["fieldExpression", "operator", "filterValues"]))
        }
    }

    @Test
    void theNetsuiteQueryIsTheActiveItemsTemplateWithTheSubgroup() {
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
        Node subgroup = rows(queries, "NsSuiteQlSourceQueryField").find { it.attribute("recordFieldName") == "subgroup" }
        assertEquals("BUILTIN.DF(custitemsubgroup) AS subgroup", subgroup?.attribute("sourceColumn"))
    }

    // Aditi 2026-10-29: "for the daily report, focus on the products that are sellable".
    @Test
    void theDailyRunReportsASellableItemHotWaxLacks() {
        assertEquals("CONC_PRODUCT_NOT_IN_HOTWAX", conclude(DAILY, "MISSING_FROM_FILE_2", kept(item("43656", "Fine")), ABSENT), "271-010-*-G earrings")
        assertEquals("CONC_PRODUCT_NOT_IN_HOTWAX", conclude(DAILY, "MISSING_FROM_FILE_2", kept(item("1", "Home Décor ")), ABSENT),
                "NetSuite's 'Home Décor ' carries a trailing space")
        assertEquals("CONC_PRODUCT_NOT_SELLABLE_IN_NS", conclude(DAILY, "MISSING_FROM_FILE_2", kept(item("42808", "Packaging")), ABSENT), "PKG403")
        assertEquals("CONC_PRODUCT_NOT_SELLABLE_IN_NS", conclude(DAILY, "MISSING_FROM_FILE_2", kept(item("43434", null)), ABSENT), "LabGrownCoin, no Subgroup")
        assertEquals("CONC_PRODUCT_NOT_SELLABLE_IN_NS", conclude(DAILY, "MISSING_FROM_FILE_2", kept(item("34838", null) + [itemType: "NonInvtPart"]), ABSENT))
        assertEquals("CONC_PRODUCT_DISCONTINUED_IN_NS", conclude(DAILY, "MISSING_FROM_FILE_2", kept(item("12777", "Fashion") + [activeStatus: "Discontinued"]), ABSENT))
        Map hw = product("42808", "OWN") + [sellable: "N", notSellableReason: "discontinued"]
        assertEquals("CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX", conclude(DAILY, "MISSING_FROM_FILE_2", kept(item("42808", "Fashion")), excluded(hw)))
        [product("DUPLICATE:43000:10200", "SHARED"), product("HOTWAX-ONLY:10056", "NONE"), product("99999", "OWN")].each { Map p ->
            assertEquals("CONC_PRODUCT_NOT_REPORTED", conclude(DAILY, "MISSING_FROM_FILE_1", ABSENT, kept(p)), p.nsKey as String)
        }
        assertEquals("CONC_PRODUCT_NOT_REPORTED", conclude(DAILY, "MISSING_FROM_FILE_1", excluded(item("30000", "Fashion") + [live: "N"]), kept(product("30000", "OWN"))))
    }

    // Aditi 2026-10-29: "non-sellable products should be in the weekly report only", with one NetSuite id on several products.
    @Test
    void theWeeklyRunReportsANonSellableItemHotWaxLacksAndASharedId() {
        assertEquals("CONC_PRODUCT_NOT_IN_HOTWAX", conclude(WEEKLY, "MISSING_FROM_FILE_2", kept(item("42808", "Packaging")), ABSENT), "PKG403")
        assertEquals("CONC_PRODUCT_NOT_IN_HOTWAX", conclude(WEEKLY, "MISSING_FROM_FILE_2", kept(item("43434", null)), ABSENT), "LabGrownCoin")
        assertEquals("CONC_PRODUCT_SELLABLE_IN_NS", conclude(WEEKLY, "MISSING_FROM_FILE_2", kept(item("43656", "Fine")), ABSENT), "the daily run's")
        assertEquals("CONC_PRODUCT_NS_ONLY_NON_INVENTORY", conclude(WEEKLY, "MISSING_FROM_FILE_2", kept(item("34838", null) + [itemType: "NonInvtPart"]), ABSENT),
                "POS Tax Variance is NetSuite's own")
        assertEquals("CONC_PRODUCT_DISCONTINUED_IN_NS", conclude(WEEKLY, "MISSING_FROM_FILE_2", kept(item("856", "Packaging") + [activeStatus: "Discontinued"]),
                excluded(product("856", "OWN") + [sellable: "N", notSellableReason: "DIGITAL_GOOD"])), "discontinued in NetSuite wins over not sellable in HotWax")
        assertEquals("CONC_PRODUCT_NS_ID_SHARED", conclude(WEEKLY, "MISSING_FROM_FILE_1", ABSENT, kept(product("DUPLICATE:43000:10200", "SHARED"))),
                "the cause holds even when a field its fix names is blank")
        [product("HOTWAX-ONLY:10056", "NONE"), product("99999", "OWN"), product("X", "NEW_STATE")].each { Map p ->
            assertEquals("CONC_PRODUCT_NOT_REPORTED", conclude(WEEKLY, "MISSING_FROM_FILE_1", ABSENT, kept(p)), p.nsKey as String)
        }
    }

    @Test
    void eachRunShowsOnlyItsOwnCauses() {
        Closure<Set> shown = { String scope ->
            scoped(scope, "darpan.rule.RuleSetConclusionRule").findAll { it.outcome != "HIDE" }*.conclusionEnumId as Set
        }
        assertEquals(["CONC_PRODUCT_NOT_IN_HOTWAX"] as Set, shown(DAILY))
        assertEquals(["CONC_PRODUCT_NOT_IN_HOTWAX", "CONC_PRODUCT_NS_ID_SHARED"] as Set, shown(WEEKLY))
        Set<String> enums = rows(FILE, "moqui.basic.Enumeration")*.attribute("enumId") as Set
        [DAILY, WEEKLY].each { String scope ->
            assertTrue(enums.containsAll(scoped(scope, "darpan.rule.RuleSetConclusionRule")*.conclusionEnumId), "${scope}: every cause has its label")
        }
        assertEquals(enums, ([DAILY, WEEKLY].collectMany { scoped(it, "darpan.rule.RuleSetConclusionRule")*.conclusionEnumId } as Set), "no unused label")
    }

    @Test
    void theyAreSavedRunsNotAuditChecks() {
        assertEquals("darpan-tenant-config", new XmlParser(false, false).parse(dataDir().resolve(FILE).toFile()).attribute("type"))
        assertEquals(["GORJANA_PRODUCT_NOT_IN_HOTWAX_RS", "GORJANA_PRODUCT_PRESENCE_RS"], rows(FILE, "darpan.rule.RuleSet")*.attribute("ruleSetId"))
        ["darpan.audit.AuditCheck", "darpan.audit.AuditStatementCheck", "darpan.audit.AuditStatement"].each {
            assertTrue(rows(FILE, it).isEmpty(), "${it}: the product runs are not part of the audit")
        }
        assertTrue(rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.AuditStatement").every { it.attribute("statementId") != "GORJANA_R22" })
    }

    // Reviewer 2026-10-28: a fix may only name what its conditions read (causeFix); every placeholder must fill.
    @Test
    void everyCausesFixIsFilled() {
        Map shared = product("DUPLICATE:43000:10200", "SHARED") + [productId: "10200", netsuiteProductId: "43000", sharedWith: "10100"]
        assertEquals("NetSuite id 43000 is on HotWax products 10200 (2210-013a-G-OLD) and 10100: keep it on the product that is that NetSuite item and remove it from the others.",
                fixOf(WEEKLY, "MISSING_FROM_FILE_1", ABSENT, kept(shared)))
        assertEquals("Sync NetSuite item 43656 (SKU-43656) to HotWax, then re-import anything HotWax refused for it (transfer orders, purchase orders).",
                fixOf(DAILY, "MISSING_FROM_FILE_2", kept(item("43656", "Fine")), ABSENT))
        Map nonSellable = product("42808", "OWN") + [sellable: "N", notSellableReason: "discontinued"]
        assertEquals("NetSuite item 42808 (SKU-42808) is new or was used for HotWax this year, but HotWax product 10056 is discontinued: make it sellable in HotWax, or inactive in NetSuite.",
                fixOf(DAILY, "MISSING_FROM_FILE_2", kept(item("42808", "Fine")), excluded(nonSellable)))
        assertEquals("Nothing to fix here: SKU-42808 is not sellable in NetSuite (Subgroup Packaging); the weekly product run reports it.",
                fixOf(DAILY, "MISSING_FROM_FILE_2", kept(item("42808", "Packaging")), ABSENT))
        [fixOf(WEEKLY, "MISSING_FROM_FILE_2", kept(item("42808", "Packaging")), ABSENT),
         fixOf(WEEKLY, "MISSING_FROM_FILE_2", kept(item("43656", "Fine")), ABSENT),
         fixOf(WEEKLY, "MISSING_FROM_FILE_1", ABSENT, kept(product("99999", "OWN"))),
         fixOf(DAILY, "MISSING_FROM_FILE_1", ABSENT, kept(product("99999", "OWN"))),
         fixOf(WEEKLY, "MISSING_FROM_FILE_2", kept(item("34838", null) + [itemType: "NonInvtPart"]), ABSENT)].each {
            assertTrue(!(it =~ /\{[A-Za-z]/).find(), "unfilled placeholder: ${it}")
        }
    }
}
