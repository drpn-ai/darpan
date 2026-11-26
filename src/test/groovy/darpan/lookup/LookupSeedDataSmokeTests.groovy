package darpan.lookup

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.*

/** DAR-CLI-008 part 3: the product lookup rows each system repo ships are usable as shipped. */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupSeedDataSmokeTests {

    private static final List<String> FILES = ["component://shopify-darpan/data/ShopifyLookupSeedData.xml",
                                               "component://darpan-hotwax/data/HotWaxLookupSeedData.xml",
                                               "component://netsuite-darpan/data/NetSuiteLookupSeedData.xml"]
    private ExecutionContext ec
    private List<Map> reads
    private List<Map> links

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "lookup-seed")
        ReconciliationSmokeTestSupport.loadSeedData(ec, *FILES)
        reads = LookupCatalog.reads(ec, null)
        links = LookupCatalog.links(ec, null)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void "every shipped read's template is read-only, its format one literal %s, and a GraphQL read says where its records are"() {
        assertTrue(reads.size() >= 15, "loaded: ${reads*.readId}")
        List<String> problems = []
        reads.each { Map r ->
            String why = OrderLookup.templateRefusal(r.systemCode as String, r.queryTemplate as String) ?: OrderLookup.formatRefusal(r.valueFormat as String)
            if (why) problems << "${r.readId}: ${why}".toString()
            if (r.systemCode != "NETSUITE" && !r.recordsAt) problems << "${r.readId}: no recordsAt".toString()
            if (r.systemCode == "NETSUITE" && r.valueFormat) problems << "${r.readId}: a SuiteQL read takes the value as is".toString()
            if (r.valueShape) { try { java.util.regex.Pattern.compile(r.valueShape as String) } catch (Exception e) { problems << "${r.readId}: bad shape".toString() } }
            if (((r.fieldsDefault ?: "") as String).toLowerCase().contains("customer")) problems << "${r.readId}: customer details in the default fields".toString()
        }
        assertTrue(problems.isEmpty(), problems.join("\n"))
    }

    @Test
    void "every shipped link joins two shipped reads"() {
        Set<String> ids = reads*.readId as Set
        List<String> broken = links.findAll { !(it.fromReadId in ids) || !(it.toReadId in ids) }.collect { "${it.linkId}: ${it.fromReadId} -> ${it.toReadId}".toString() }
        assertTrue(broken.isEmpty(), broken.join("\n"))
    }

    @Test
    void "a Shopify id, a HotWax name and a NetSuite number each have a find read"() {
        Closure<List<String>> finders = { String v -> reads.findAll { it.isFind == "Y" && OrderLookup.shapeFits(it, v) }*.readId }
        assertTrue("SHOPIFY_ORDER_BY_ID" in finders("19014662389929"))
        assertTrue("OMS_ORDER_BY_EXTERNAL_ID" in finders("19014662389929"))
        assertTrue("OMS_ORDER_BY_NAME" in finders("#GOR197527791"))
        assertTrue("NS_SO_BY_OTHERREFNUM" in finders("#GOR197527791"))
        assertTrue("OMS_ORDER_BY_BARE_NAME" in finders("1044966"))
        assertTrue("NS_SO_BY_TRANID" in finders("SO7482568"))
        assertTrue("OMS_ORDER_BY_ID" in finders("M1329302"))
    }
}
