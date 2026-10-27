package darpan.audit

import darpan.facade.reconciliation.RunConclusionStep
import darpan.reconciliation.conclusion.ConclusionTreeEngine
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test
import reconciliation.rule.FieldComparisonRuleLogicGenerator

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-CLIENT-005. RAILS's audit (R1-R7) as configuration, at parse level. RAILS runs five Shopify stores into one HotWax
 * and one NetSuite, so every statement with Shopify on a side has one check per store, and each store's run must read that
 * store on BOTH sides: its Shopify config, HotWax productStoreId, NetSuite custbody_rails_shopify_store (live 2026-10-27).
 */
class RailsAuditDataTests {

    private static final String FILE = "RailsAuditData.xml"
    /** Store code -> [Shopify config, HotWax productStoreId, NetSuite custbody_rails_shopify_store]. */
    private static final Map<String, List<String>> STORES = [
            USA   : ["100003", "STORE", "1"],
            CANADA: ["100000", "STORE_CA", "5"],
            EU    : ["100001", "STORE_EU", "2"],
            UK    : ["100004", "STORE_UK", "3"],
            OUTLET: ["100002", "STORE_OUTLET", "4"],
    ].asImmutable()

    private static Path dataDir() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data")
    }

    private static List<Node> rows(String elementName) {
        return new XmlParser(false, false).parse(dataDir().resolve(FILE).toFile()).children()
                .findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    private static Node source(String scope, String side) {
        return rows("darpan.rule.RuleSetCompareSource").find { it.attribute("compareScopeId") == scope && it.attribute("fileSide") == side }
    }

    private static List<List<String>> filters(String scope, String side) {
        return rows("darpan.rule.RuleSetCompareSourceFilter").findAll { it.attribute("compareScopeId") == scope && it.attribute("fileSide") == side }
                .collect { [it.attribute("fieldExpression"), it.attribute("operator"), it.attribute("filterValues")] as List<String> }
    }

    @Test
    void theAuditCarriesAditisSevenStatements() {
        assertEquals(["R1": "An order HotWax completed or cancelled does not stay open in NetSuite.",
                      "R2": "Every Shopify order is in HotWax.",
                      "R3": "Every order paid in Shopify has its payment in HotWax.",
                      "R4": "Every item HotWax completes is billed in NetSuite.",
                      "R5": "Every order HotWax completes is fulfilled in Shopify.",
                      "R6": "Every return Shopify completes with a refund is in NetSuite.",
                      "R7": "Every exchange Shopify makes is in NetSuite."],
                rows("darpan.audit.AuditStatement").collectEntries { [(it.attribute("statementCode")): it.attribute("statementText")] })
        assertEquals("AUDC_EXCHANGES_WARRANTY", rows("darpan.audit.AuditStatement").find { it.attribute("statementCode") == "R7" }.attribute("categoryEnumId"))
    }

    @Test
    void everyShopifyStatementHasOneCheckPerStore() {
        Map<String, Set<String>> checksByStatement = rows("darpan.audit.AuditStatementCheck").groupBy { it.attribute("statementId") }
                .collectEntries { k, v -> [(k): v*.attribute("checkId") as Set<String>] }
        ["RAILS_R2": "RAILS_CHK_NOT_IN_OMS_", "RAILS_R3": "RAILS_CHK_PAID_NO_OPP_", "RAILS_R5": "RAILS_CHK_COMPLETED_NOT_FULFILLED_",
         "RAILS_R6": "RAILS_CHK_RETURN_NO_CM_"].each { String statement, String prefix ->
            assertEquals(STORES.keySet().collect { prefix + it } as Set, checksByStatement[statement], statement)
        }
        assertEquals(["RAILS_CHK_COMPLETED_BILLED"] as Set, checksByStatement["RAILS_R4"])
        // R7 has two exchange paths per store: Loop's and HotWax's (POS).
        assertEquals(STORES.keySet().collectMany { ["RAILS_CHK_LOOP_EXC_NOT_IN_NS_" + it, "RAILS_CHK_POS_EXC_NOT_IN_NS_" + it] } as Set,
                checksByStatement["RAILS_R7"])
    }

    @Test
    void eachStoresOrderRunReadsThatStoreOnBothSides() {
        STORES.each { String code, List<String> store ->
            String scope = "RAILS_SHOP_OMS_${code}_SCOPE"
            assertEquals(["SHOPIFY", store[0]], [source(scope, "FILE_1").attribute("systemEnumId"), source(scope, "FILE_1").attribute("sourceConfigId")], code)
            assertEquals(["OMS_GQL", "rails_prod"], [source(scope, "FILE_2").attribute("systemEnumId"), source(scope, "FILE_2").attribute("sourceConfigId")], code)
            // Shopify is read whole, so a fixed order is read again; HotWax only for the store. HotWax's EXC-#<name> POS
            // exchanges (same externalId as their original) leave through OMS_GQL's built-in exchange exclusion.
            assertEquals([], filters(scope, "FILE_1"), code)
            assertEquals([["productStoreId", "INCLUDE_IN", store[1]]], filters(scope, "FILE_2"), code)
            assertEquals("FINDING", source(scope, "FILE_2").attribute("duplicateKeyHandling"), code)
        }
    }

    @Test
    void eachStoresReturnRunReadsCompletedRefundedReturnsAgainstThatStoresCreditMemos() {
        STORES.each { String code, List<String> store ->
            String scope = "RAILS_RETURN_CM_${code}_SCOPE"
            Node shop = source(scope, "FILE_1")
            assertEquals(["SHOPIFY_RETURN_REFS", store[0], "SHOPIFY_RETURN_REFS_API"],
                    ["systemEnumId", "sourceConfigId", "sourceConfigType"].collect { shop.attribute(it) }, code)
            // Loop refunds the ORDER and never links the refund to the Return; "refunded" would see none of its returns.
            assertEquals([completedReturns: "orderRefunded"], new JsonSlurper().parseText(shop.attribute("extractParametersJson") as String), code)
            assertEquals("RAILS_NS_CREDIT_MEMOS", source(scope, "FILE_2").attribute("sourceConfigId"), code)
            assertEquals([], filters(scope, "FILE_1"), code)
            assertEquals([["shopifyStore", "INCLUDE_IN", store[2]]], filters(scope, "FILE_2"), code)
            assertEquals("CONC_NO_CREDIT_MEMO_IN_NS", conclude(scope, "MISSING_FROM_FILE_2", [orderId: "7317384134825"], null))
        }
    }

    @Test
    void eachStoresLoopExchangeRunReadsShopifyExchangeOrdersAgainstThatStoresExchangeSalesOrders() {
        STORES.each { String code, List<String> store ->
            String scope = "RAILS_EXC_LOOP_${code}_SCOPE"
            Node shop = source(scope, "FILE_1")
            assertEquals(["SHOPIFY", store[0], "legacyResourceId"], ["systemEnumId", "sourceConfigId", "primaryIdExpression"].collect { shop.attribute(it) }, code)
            // A Loop exchange is a new Shopify order, EXC-<name>-1, and NetSuite keys it by its own Shopify id.
            assertEquals([["name", "INCLUDE_PREFIX", "EXC-"]], filters(scope, "FILE_1"), code)
            assertEquals(["RAILS_NS_EXCHANGE_ORDERS", "shopifyOrderId"], ["sourceConfigId", "primaryIdExpression"].collect { source(scope, "FILE_2").attribute(it) }, code)
            assertEquals([["shopifyStore", "INCLUDE_IN", store[2]]], filters(scope, "FILE_2"), code)
            assertEquals("CONC_EXCHANGE_NOT_IN_NS", conclude(scope, "MISSING_FROM_FILE_2", [legacyResourceId: "18938434945193", name: "EXC-1015225-1"], null))
        }
    }

    @Test
    void eachStoresPosExchangeRunReadsCompletedShopifyExchangesAgainstThatStoresExchangeSalesOrders() {
        STORES.each { String code, List<String> store ->
            String scope = "RAILS_EXC_POS_${code}_SCOPE"
            Node shop = source(scope, "FILE_1")
            assertEquals(["SHOPIFY_RETURN_REFS", store[0], "SHOPIFY_RETURN_REFS_API", "orderId"],
                    ["systemEnumId", "sourceConfigId", "sourceConfigType", "primaryIdExpression"].collect { shop.attribute(it) }, code)
            // A HotWax (POS) exchange is a Shopify return with exchange items; NetSuite keys its EXC-#<name>-1 sales order by
            // the ORIGINAL order's Shopify id.
            assertEquals([completedReturns: "exchanged"], new JsonSlurper().parseText(shop.attribute("extractParametersJson") as String), code)
            assertEquals([], filters(scope, "FILE_1"), code)
            assertEquals(["RAILS_NS_EXCHANGE_ORDERS", "shopifyOrderId"], ["sourceConfigId", "primaryIdExpression"].collect { source(scope, "FILE_2").attribute(it) }, code)
            assertEquals([["shopifyStore", "INCLUDE_IN", store[2]]], filters(scope, "FILE_2"), code)
            assertEquals("CONC_EXCHANGE_NOT_IN_NS", conclude(scope, "MISSING_FROM_FILE_2", [orderId: "18910442225833", originalUnfulfilledQuantity: 0], null))
            assertEquals("CONC_EXCHANGE_ORIGINAL_UNFULFILLED", conclude(scope, "MISSING_FROM_FILE_2", [orderId: "18910442225833", originalUnfulfilledQuantity: 2], null))
        }
    }

    @Test
    void r4ConcludesHowNetSuiteLeftACompletedItem() {
        String scope = "RAILS_OMS_NS_LINE_SCOPE"
        Map item = [omsOrderId: "157377", orderItemSeqId: "01", orderGrandTotal: "120.00"]
        assertEquals("CONC_NEVER_REACHED_NS", conclude(scope, "MISSING_FROM_FILE_2", item, null))
        assertEquals("CONC_PARTIAL_PAYMENT_NO_NS", conclude(scope, "MISSING_FROM_FILE_2", item + [orderGrandTotal: "0"], null))
        assertEquals("CONC_COMPLETED_CLOSED_IN_NS", conclude(scope, "MISSING_FROM_FILE_2", item, [lineState: "CLOSED", lineLocation: "1"]))
        assertEquals("CONC_SHIPPED_NOT_BILLED", conclude(scope, "MISSING_FROM_FILE_2", item, [lineState: "SHIPPED", lineLocation: "1"]))
        assertEquals("CONC_NS_NO_LINE_LOCATION", conclude(scope, "MISSING_FROM_FILE_2", item, [lineState: "OPEN"]))
        assertEquals([["lineState", "INCLUDE_IN", "BILLED"], ["itemType", "EXCLUDE_IN", "Discount"], ["shopifyStore", "INCLUDE_IN", "1,2,3,4,5"]],
                filters(scope, "FILE_2"))
    }

    @Test
    void storedRuleLogicIsExactlyWhatTheGeneratorMakes() {
        rows("darpan.rule.Rule").groupBy { it.attribute("ruleSetId") }.each { String ruleSet, List<Node> rules ->
            String scope = rows("darpan.rule.RuleSetCompareScope").find { it.attribute("ruleSetId") == ruleSet }.attribute("compareScopeId")
            rules.eachWithIndex { Node rule, int i ->
                String expression = (rule.children().find { it instanceof Node && it.name() == "expression" } as Node).text()
                String stored = (rule.children().find { it instanceof Node && it.name() == "ruleLogic" } as Node).text().trim()
                assertEquals(FieldComparisonRuleLogicGenerator.generate(expression, source(scope, "FILE_1").attribute("primaryIdExpression") as String,
                        source(scope, "FILE_2").attribute("primaryIdExpression") as String, rule.attribute("ruleId") as String,
                        rule.attribute("severity") as String, i), stored, rule.attribute("ruleId") as String)
            }
        }
    }

    @Test
    void everyReferenceResolves() {
        Set<String> ruleSets = rows("darpan.rule.RuleSet")*.attribute("ruleSetId") as Set<String>
        Set<String> statements = rows("darpan.audit.AuditStatement")*.attribute("statementId") as Set<String>
        Set<String> enums = rows("moqui.basic.Enumeration")*.attribute("enumId") as Set<String>
        List<Node> checks = rows("darpan.audit.AuditCheck")
        List<Node> links = rows("darpan.audit.AuditStatementCheck")
        Set<String> codes = [] as Set
        checks.each { Node c ->
            String id = c.attribute("checkId")
            assertEquals("RAILS", c.attribute("companyUserGroupId"), id)
            assertTrue(ruleSets.contains(c.attribute("savedRunId") as String), "${id} names an unknown run")
            assertTrue(c.attribute("fixWhereEnumId") in ["OMS", "NETSUITE", "SHOPIFY"], id)
            assertTrue((c.attribute("headlineTemplate") as String).contains("{recordKey}"), id)
            assertNull(c.attribute("automationId"), "${id}: automations are made per environment")
            assertFalse(links.findAll { it.attribute("checkId") == id }.isEmpty(), "${id} tests no statement")
            assertTrue(codes.add(c.attribute("checkCode") as String), "${id}: checkCode is unique per tenant")
        }
        links.each { Node l ->
            assertTrue(statements.contains(l.attribute("statementId") as String), l.attribute("statementId") as String)
            Node check = checks.find { it.attribute("checkId") == l.attribute("checkId") }
            ((l.attribute("ruleIds") ?: "") as String).split(",").findAll { it }.each { String ruleId ->
                assertEquals(check.attribute("savedRunId"), rows("darpan.rule.Rule").find { it.attribute("ruleId") == ruleId }?.attribute("ruleSetId"), ruleId)
            }
            ((l.attribute("conclusionCodes") ?: "") as String).split(",").findAll { it }.each { String code ->
                assertTrue(enums.contains(code) || code == "UNEXPLAINED", code)
            }
        }
        rows("darpan.rule.RuleSetConclusionRule").each { assertTrue(enums.contains(it.attribute("conclusionEnumId") as String), it.attribute("conclusionEnumId") as String) }
    }

    @Test
    void noValueOutgrowsItsColumn() {
        new XmlParser(false, false).parse(dataDir().resolve(FILE).toFile()).children().findAll { it instanceof Node }.each { Node row ->
            ["description", "label", "checkLabel", "statementText", "ruleSetName", "matchedBy", "fixOwner"].each { String a ->
                String v = row.attribute(a) as String
                assertTrue(v == null || v.length() <= 255, "${row.name()} ${a} is ${v?.length()} characters")
            }
            String code = row.attribute("checkCode") as String
            assertTrue(code == null || code.length() <= 63, "checkCode ${code}")
        }
    }

    private static String conclude(String scope, String bucket, Map file1, Map file2) {
        Closure<List<Map>> scoped = { String name ->
            rows(name).findAll { it.attribute("compareScopeId") == scope }.collect { Node node ->
                Map m = new LinkedHashMap(node.attributes())
                ["sequenceNum", "conditionSeq"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
                m
            }
        }
        Map<String, String> labels = rows("moqui.basic.Enumeration").collectEntries { [(it.attribute("enumId") as String): it.attribute("description") as String] }
        List<Map> rules = RunConclusionStep.buildRules(scoped("darpan.rule.RuleSetConclusionRule"), scoped("darpan.rule.RuleSetConclusionCondition"), labels)
        Map one = [presence: "KEPT", record: file1, firstFieldPresence: "KEPT", firstFieldRecord: file1]
        Map two = file2 == null ? [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]
                : [presence: "EXCLUDED", record: file2, firstFieldPresence: "EXCLUDED", firstFieldRecord: file2]
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(rules)
        try {
            return engine.conclude([[bucket: bucket, sides: [FILE_1: one, FILE_2: two]]])[0].code
        } finally {
            engine.close()
        }
    }
}
