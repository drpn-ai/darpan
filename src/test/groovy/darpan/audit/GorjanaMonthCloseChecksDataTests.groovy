package darpan.audit

import darpan.facade.reconciliation.RunConclusionStep
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
        assertEquals("darpan-tenant-config", doc(FILE).attribute("type"))
    }

    @Test
    void everyReferenceResolves() {
        List<Node> checks = rows(FILE, "darpan.audit.AuditCheck")
        List<Node> links = rows(FILE, "darpan.audit.AuditStatementCheck")
        Set<String> ruleSets = attr(rows(FILE, "darpan.rule.RuleSet") +
                rows("TriSystemOrderRunsRuleSetData.xml", "darpan.rule.RuleSet"), "ruleSetId")
        Set<String> statements = attr(rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.AuditStatement"), "statementId")
        Set<String> conclusions = attr(rows(FILE, "moqui.basic.Enumeration") +
                rows("GorjanaConclusionRulesData.xml", "moqui.basic.Enumeration"), "enumId")

        checks.each { Node c ->
            String id = c.attribute("checkId")
            assertEquals("GORJANA", c.attribute("companyUserGroupId"), "${id} tenant")
            assertTrue(ruleSets.contains(c.attribute("savedRunId") as String), "${id} names an unknown saved run")
            assertTrue(c.attribute("fixWhereEnumId") in ["OMS", "NETSUITE", "SHOPIFY"], "${id} fixWhere must be a root system")
            assertTrue((c.attribute("headlineTemplate") as String).contains("{recordKey}"), "${id} headline names the record")
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
        assertEquals(["GORJANA_R1", "GORJANA_R2", "GORJANA_R3"] as Set, attr(links, "statementId"), "comparison 2 covers R1-R3")
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
        assertTrue(rows(FILE, "darpan.rule.RuleSetCompareSourceFilter").isEmpty())
        rows(FILE, "darpan.rule.RuleSetCompareScope").each { assertEquals("EVALUATE", it.attribute("scopeMode")) }
    }

    private static String conclude(String scope, Map record) {
        Closure<List<Map>> scoped = { String name ->
            rows(FILE, name).findAll { it.attribute("compareScopeId") == scope }.collect { Node node ->
                Map m = new LinkedHashMap(node.attributes())
                ["sequenceNum", "conditionSeq"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
                m
            }
        }
        Map<String, String> labels = rows(FILE, "moqui.basic.Enumeration")
                .collectEntries { [(it.attribute("enumId") as String): it.attribute("description") as String] }
        List<Map> rules = RunConclusionStep.buildRules(scoped("darpan.rule.RuleSetConclusionRule"),
                scoped("darpan.rule.RuleSetConclusionCondition"), labels)
        Map side = [presence: "KEPT", record: record, firstFieldPresence: "KEPT", firstFieldRecord: record]
        Map absent = [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]
        return FlatEvaluatorOracle.evaluate(rules, "FINDING", [FILE_1: side, FILE_2: absent]).code
    }
}
