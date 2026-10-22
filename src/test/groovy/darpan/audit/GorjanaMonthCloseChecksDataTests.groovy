package darpan.audit

import darpan.facade.reconciliation.RunConclusionStep
import reconciliation.rule.FieldComparisonRuleLogicGenerator
import darpan.reconciliation.conclusion.FlatEvaluatorOracle
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-093 — gorjana month close comparison 2 (R1, R2, R3) as configuration, at parse level (no Moqui boot). The
 * loader rolls a file back on a referential failure and still reports a count, so the references are proven here.
 */
class GorjanaMonthCloseChecksDataTests {

    private static final String FILE = "GorjanaMonthCloseChecksData.xml"
    /** DAR-BE-094: comparison 1, the returns checks (R9 so far). */
    private static final String RETURNS = "GorjanaMonthCloseReturnsChecksData.xml"
    /** DAR-BE-094 R14 / DAR-BE-095 R13: exchange credit memos against their replacement orders. */
    private static final String REPLACEMENT = "GorjanaMonthCloseReplacementChecksData.xml"

    private static Path dataDir() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data")
    }

    private static Node doc(String file) {
        return new XmlParser(false, false).parse(dataDir().resolve(file).toFile())
    }

    private static List<Node> rows(String file, String elementName) {
        return doc(file).children().findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    private static Set<String> attr(List<Node> nodes, String name) {
        return nodes.collect { it.attribute(name) as String } as Set<String>
    }

    @Test
    void theFileIsTenantConfigurationNotSeed() {
        [FILE, RETURNS, REPLACEMENT].each { assertEquals("darpan-tenant-config", doc(it).attribute("type"), it) }
    }

    @Test
    void everyReferenceResolves() {
        [FILE, RETURNS, REPLACEMENT].each { everyReferenceResolvesIn(it) }
        assertEquals(["GORJANA_R1", "GORJANA_R2", "GORJANA_R3"] as Set, attr(rows(FILE, "darpan.audit.AuditStatementCheck"), "statementId"),
                "comparison 2 covers R1-R3")
        assertEquals(["GORJANA_R9"] as Set, attr(rows(RETURNS, "darpan.audit.AuditStatementCheck"), "statementId"),
                "comparison 1 covers R9 so far")
        assertEquals(["GORJANA_R13", "GORJANA_R14"] as Set, attr(rows(REPLACEMENT, "darpan.audit.AuditStatementCheck"), "statementId"))
    }

    @Test
    void eachReplacementStatementTakesOnlyItsOwnRulesMismatches() {
        Set<String> rules = attr(rows(REPLACEMENT, "darpan.rule.Rule"), "ruleId")
        Map<String, String> byStatement = rows(REPLACEMENT, "darpan.audit.AuditStatementCheck")
                .collectEntries { [(it.attribute("statementId")): it.attribute("ruleIds")] }
        assertEquals([GORJANA_R14: "GORJANA_MR_SAME_CUSTOMER", GORJANA_R13: "GORJANA_MR_NOT_OWN_ORIGINAL,GORJANA_MR_NOT_YEAR_OLDER"], byStatement)
        assertTrue(rules.containsAll(byStatement.values().collectMany { it.split(",") as List }), "a link names rules the rule set has")
        rows(REPLACEMENT, "darpan.audit.AuditStatementCheck").each { assertEquals("FIELD_MISMATCH", it.attribute("diffTypes")) }
        // Both checks are one compare, so one automation runs them.
        assertEquals(["GORJANA_MEMO_REPLACEMENT_RS"] as Set, attr(rows(REPLACEMENT, "darpan.audit.AuditCheck"), "savedRunId"))
    }

    @Test
    void storedReplacementDrlIsExactlyWhatTheGeneratorMakes() {
        List<Node> sources = rows(REPLACEMENT, "darpan.rule.RuleSetCompareSource")
        String key1 = sources.find { it.attribute("fileSide") == "FILE_1" }.attribute("primaryIdExpression")
        String key2 = sources.find { it.attribute("fileSide") == "FILE_2" }.attribute("primaryIdExpression")
        rows(REPLACEMENT, "darpan.rule.Rule").eachWithIndex { Node rule, int i ->
            String expression = (rule.children().find { it instanceof Node && it.name() == "expression" } as Node).text()
            String stored = (rule.children().find { it instanceof Node && it.name() == "ruleLogic" } as Node).text().trim()
            String expected = FieldComparisonRuleLogicGenerator.generate(expression, key1, key2, rule.attribute("ruleId") as String,
                    rule.attribute("severity") as String, i)
            if (expected != stored) println "EXPECTED_DRL|${rule.attribute('ruleId')}|" + expected.bytes.encodeBase64().toString()
            assertEquals(expected, stored, rule.attribute("ruleId") as String)
        }
    }

    private static void everyReferenceResolvesIn(String file) {
        List<Node> checks = rows(file, "darpan.audit.AuditCheck")
        List<Node> links = rows(file, "darpan.audit.AuditStatementCheck")
        Set<String> ruleSets = attr(rows(file, "darpan.rule.RuleSet") +
                rows("TriSystemOrderRunsRuleSetData.xml", "darpan.rule.RuleSet"), "ruleSetId")
        Set<String> statements = attr(rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.AuditStatement"), "statementId")
        Set<String> conclusions = attr(rows(file, "moqui.basic.Enumeration") +
                rows("GorjanaConclusionRulesData.xml", "moqui.basic.Enumeration"), "enumId")

        checks.each { Node c ->
            String id = c.attribute("checkId")
            assertEquals("GORJANA", c.attribute("companyUserGroupId"), "${id} tenant")
            assertTrue(ruleSets.contains(c.attribute("savedRunId") as String), "${id} names an unknown saved run")
            assertTrue(c.attribute("fixWhereEnumId") in ["OMS", "NETSUITE", "SHOPIFY"], "${id} fixWhere must be a root system")
            String headline = c.attribute("headlineTemplate") as String
            List<String> names = ["{recordKey}"] + ["file1RecordType", "file2RecordType"].collect { c.attribute(it) }.findAll { it }.collect { "{${it}}".toString() }
            assertTrue(names.any { headline.contains(it) }, "${id} headline names the record (${names})")
            // Automations are created per environment; an id here would be a dangling FK on load.
            assertEquals(null, c.attribute("automationId"), "${id} must not carry an automationId")
            assertFalse(links.findAll { it.attribute("checkId") == id }.isEmpty(), "${id} tests no statement")
        }
        links.each { Node l ->
            assertTrue(statements.contains(l.attribute("statementId") as String), "unknown statement ${l.attribute('statementId')}")
            assertTrue(attr(checks, "checkId").contains(l.attribute("checkId") as String), "unknown check ${l.attribute('checkId')}")
            ((l.attribute("conclusionCodes") ?: "") as String).split(",")*.trim().findAll { it }.each { String code ->
                assertTrue(conclusions.contains(code), "undeclared conclusion ${code}")
            }
        }
    }

    @Test
    void anR2RowIsAFindingOnlyWhenTheEtailIdIsBlank() {
        ["GORJANA_INVOICE_ETAIL_SCOPE", "GORJANA_CREDIT_ETAIL_SCOPE"].each { String scope ->
            assertEquals("CONC_NO_ETAIL_ID", conclude(scope, [tranId: "INV1"]), "${scope}: absent (SuiteQL omits null)")
            assertEquals("CONC_NO_ETAIL_ID", conclude(scope, [tranId: "INV1", etailOrderId: " "]), "${scope}: blank")
            assertNotEquals("CONC_NO_ETAIL_ID", conclude(scope, [tranId: "INV1", etailOrderId: "7156795932803"]), "${scope}: carried")
        }
    }

    @Test
    void theR2RunsReadEveryRecordSoAFixedOneCloses() {
        // A source filter runs in the getter, before the examined-keys file is written: a filtered-out record would
        // never be examined, and its finding could never close.
        [FILE, RETURNS].each { String file ->
            assertTrue(rows(file, "darpan.rule.RuleSetCompareSourceFilter").isEmpty(), file)
            rows(file, "darpan.rule.RuleSetCompareScope").each { assertEquals("EVALUATE", it.attribute("scopeMode"), file) }
        }
    }

    @Test
    void anR9RowIsAFindingWhenTheCustomerNameOrTheReturnAddressIsMissing() {
        Map whole = [tranId: "RMA1", returnId: "M258615", customerName: "Ada Lovelace", shipAddressId: "9001"]
        ["GORJANA_RMA_CUSTOMER_SCOPE", "GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE"].each { String scope ->
            assertEquals("UNEXPLAINED", conclude(RETURNS, scope, whole), "${scope}: name and address")
            assertEquals("CONC_NO_CUSTOMER_NAME", conclude(RETURNS, scope, whole - [customerName: "Ada Lovelace"]), "${scope}: no name")
            assertEquals("CONC_NO_CUSTOMER_NAME", conclude(RETURNS, scope, whole + [customerName: " "]), "${scope}: blank name")
            assertEquals("CONC_NO_RETURN_ADDRESS", conclude(RETURNS, scope, whole - [shipAddressId: "9001"]), "${scope}: no address")
            // A billing address is not the address it came from: its absence alone is no R9 finding (open question).
            assertEquals("UNEXPLAINED", conclude(RETURNS, scope, whole + [billAddressId: null]), "${scope}: no billing address")
        }
        // A credit memo that is not a return's (no HC return id) is not R9's to judge.
        assertEquals("UNEXPLAINED", conclude(RETURNS, "GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE",
                [tranId: "CM1", customerName: null, shipAddressId: null]))
    }

    private static String conclude(String scope, Map record) {
        return conclude(FILE, scope, record)
    }

    private static String conclude(String file, String scope, Map record) {
        Closure<List<Map>> scoped = { String name ->
            rows(file, name).findAll { it.attribute("compareScopeId") == scope }.collect { Node node ->
                Map m = new LinkedHashMap(node.attributes())
                ["sequenceNum", "conditionSeq"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
                m
            }
        }
        Map<String, String> labels = rows(file, "moqui.basic.Enumeration")
                .collectEntries { [(it.attribute("enumId") as String): it.attribute("description") as String] }
        List<Map> rules = RunConclusionStep.buildRules(scoped("darpan.rule.RuleSetConclusionRule"),
                scoped("darpan.rule.RuleSetConclusionCondition"), labels)
        Map side = [presence: "KEPT", record: record, firstFieldPresence: "KEPT", firstFieldRecord: record]
        Map absent = [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]
        return FlatEvaluatorOracle.evaluate(rules, "FINDING", [FILE_1: side, FILE_2: absent]).code
    }
}
