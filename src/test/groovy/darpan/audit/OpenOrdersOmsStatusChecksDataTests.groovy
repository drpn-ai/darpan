package darpan.audit

import darpan.facade.reconciliation.RunConclusionStep
import darpan.reconciliation.conclusion.ConclusionTreeEngine
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-111. NetSuite orders never shipped or billed, against their HotWax status: gorjana's R18 and RAILS's R1, the same
 * check configured per tenant. The rules are Aditi's (2026-10-22): completed in HotWax is a finding; cancelled is one only
 * while NetSuite keeps the order open; approved, created or held is correct; an HC Order Id HotWax lacks is a finding of
 * its own. Records are the live RAILS cases (SO8432201 / HC 145059 completed, SO8431481 / 144314 cancelled and Closed).
 */
class OpenOrdersOmsStatusChecksDataTests {

    private static final Map<String, String> SCOPES = [
            "GorjanaMonthCloseOpenOrdersChecksData.xml": "GORJANA_OPEN_OMS_STATUS_SCOPE",
            "RailsAuditData.xml"                       : "RAILS_OPEN_OMS_STATUS_SCOPE",
    ].asImmutable()
    private static final Map OPEN = [tranId: "SO8432201", orderId: "145059", status: "B", shipped: "F", billed: "F"]

    private static Path dataDir() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data")
    }

    private static List<Node> rows(String file, String elementName) {
        return new XmlParser(false, false).parse(dataDir().resolve(file).toFile()).children()
                .findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    @Test
    void eachTenantFindsCompletedCancelledWhileOpenAndMissingAndNothingElse() {
        SCOPES.each { String file, String scope ->
            assertEquals("CONC_OMS_COMPLETED_NS_OPEN", conclude(file, scope, OPEN + [omsStatusId: "ORDER_COMPLETED"]), file)
            assertEquals("CONC_OMS_COMPLETED_NS_OPEN", conclude(file, scope, OPEN + [omsStatusId: "ORDER_COMPLETED", status: "H"]),
                    "${file}: completed in HotWax and closed unshipped, unbilled in NetSuite is still a finding")
            assertEquals("UNEXPLAINED", conclude(file, scope, OPEN + [omsStatusId: "ORDER_CANCELLED", status: "H"]),
                    "${file}: the cancellation reached NetSuite (Closed)")
            assertEquals("UNEXPLAINED", conclude(file, scope, OPEN + [omsStatusId: "ORDER_CANCELLED", status: "C"]), file)
            assertEquals("CONC_OMS_CANCELLED_NS_OPEN", conclude(file, scope, OPEN + [omsStatusId: "ORDER_CANCELLED", status: "F"]),
                    "${file}: gorjana's four Pending Billing cancellations")
            assertEquals("CONC_NS_ORDER_NOT_IN_OMS", conclude(file, scope, OPEN + [omsStatusId: "NOT_IN_OMS"]), file)
            ["ORDER_APPROVED", "ORDER_CREATED", "ORDER_HOLD"].each { String s ->
                assertEquals("UNEXPLAINED", conclude(file, scope, OPEN + [omsStatusId: s]), "${file}: ${s} is open in both")
            }
            assertEquals("UNEXPLAINED", conclude(file, scope, OPEN + [omsStatusId: "ORDER_COMPLETED", shipped: "T"]), "${file}: shipped")
            assertEquals("UNEXPLAINED", conclude(file, scope, OPEN + [omsStatusId: "ORDER_COMPLETED", billed: "T"]), "${file}: billed")
        }
    }

    @Test
    void everyOrderIsReadSoAFixedOneCloses() {
        SCOPES.each { String file, String scope ->
            assertEquals("darpan-tenant-config", new XmlParser(false, false).parse(dataDir().resolve(file).toFile()).attribute("type"), file)
            Node compare = rows(file, "darpan.rule.RuleSetCompareScope").find { it.attribute("compareScopeId") == scope }
            assertEquals("EVALUATE", compare.attribute("scopeMode"), file)
            // The only source filter drops orders with no HC Order Id; a filter on shipped, billed or status would stop a
            // fixed order being read, and its finding would never close.
            List<Node> filters = rows(file, "darpan.rule.RuleSetCompareSourceFilter").findAll { it.attribute("compareScopeId") == scope }
            assertEquals([["orderOrigin", "INCLUDE_IN", "HOTWAX"]], filters.collect { [it.attribute("fieldExpression"), it.attribute("operator"), it.attribute("filterValues")] }, file)
        }
    }

    // A text-medium column holds 255 characters; a longer value rolls the whole file back at load, and the loader only logs
    // "Skipping to next file" (caught 2026-10-22 by the end-to-end probe on RuleSet.description).
    @Test
    void noValueOutgrowsItsColumn() {
        SCOPES.keySet().each { String file ->
            new XmlParser(false, false).parse(dataDir().resolve(file).toFile()).children().findAll { it instanceof Node }.each { Node row ->
                ["description", "label", "checkLabel", "statementText", "headlineTemplate", "fixActionTemplate", "passesWhenTemplate", "matchedBy"].each { String a ->
                    String v = row.attribute(a) as String
                    assertTrue(v == null || v.length() <= 255, "${file} ${row.name()} ${a} is ${v?.length()} characters")
                }
            }
        }
    }

    @Test
    void theStatementChecksNameConclusionsTheFileDefines() {
        SCOPES.each { String file, String scope ->
            Set<String> defined = rows(file, "moqui.basic.Enumeration")*.attribute("enumId") as Set<String>
            Set<String> concluded = rows(file, "darpan.rule.RuleSetConclusionRule").findAll { it.attribute("compareScopeId") == scope }*.attribute("conclusionEnumId") as Set<String>
            List<Node> links = rows(file, "darpan.audit.AuditStatementCheck")
            Set<String> named = links.collectMany { (it.attribute("conclusionCodes") as String).split(",") as List } as Set<String>
            assertEquals(concluded, named, "${file}: every conclusion breaks the statement, through one check or the other")
            assertTrue(defined.containsAll(named), file)
            Set<String> checks = rows(file, "darpan.audit.AuditCheck")*.attribute("checkId") as Set<String>
            assertEquals(checks, links*.attribute("checkId") as Set<String>, file)
        }
        assertEquals(["RAILS_R1"] as Set, rows("RailsAuditData.xml", "darpan.audit.AuditStatementCheck")*.attribute("statementId") as Set)
        assertEquals(["GORJANA_R18"] as Set, rows("GorjanaMonthCloseOpenOrdersChecksData.xml", "darpan.audit.AuditStatementCheck")*.attribute("statementId") as Set)
        assertEquals(["RAILS_R1"], rows("RailsAuditData.xml", "darpan.audit.AuditStatement")*.attribute("statementId"))
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
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(rules)
        try {
            return engine.conclude([[bucket: "FINDING", sides: [FILE_1: side, FILE_2: absent]]])[0].code
        } finally {
            engine.close()
        }
    }
}
