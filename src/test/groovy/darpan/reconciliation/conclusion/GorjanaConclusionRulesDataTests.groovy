package darpan.reconciliation.conclusion

import darpan.facade.reconciliation.RunConclusionStep
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-044. The gorjana conclusion catalogue is hand-written tenant config, and a typo in it does not
 * fail to load — it silently concludes nothing, or reaches the wrong conclusion forever. So its shape is
 * asserted here: every rule has conditions, every conclusion is declared, every scope exists, and no
 * scope reuses a sequence number (first-match order is the whole semantics).
 */
class GorjanaConclusionRulesDataTests {

    private static Path componentRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("entity/RuleEntities.xml"))) {
            Path candidate = p.resolve("runtime/component/darpan")
            if (Files.exists(candidate.resolve("entity/RuleEntities.xml"))) return candidate
            p = p.parent
        }
        return p
    }

    private static def load(String rel) {
        return new XmlSlurper().parse(componentRoot().resolve(rel).toFile())
    }

    @Test
    void theCatalogueIsTenantConfigNotSeed() {
        String text = Files.readString(componentRoot().resolve("data/GorjanaConclusionRulesData.xml"))
        assertTrue(text.contains('<entity-facade-xml type="darpan-tenant-config">'))
    }

    @Test
    void everyRuleIsWellFormed() {
        def doc = load("data/GorjanaConclusionRulesData.xml")
        def rules = doc.'**'.findAll { it.name() == 'darpan.rule.RuleSetConclusionRule' }
        def conditions = doc.'**'.findAll { it.name() == 'darpan.rule.RuleSetConclusionCondition' }
        Set<String> declaredEnums = doc.'**'.findAll { it.name() == 'moqui.basic.Enumeration' && it.@enumTypeId == 'DarpanConclusion' }
                .collect { it.@enumId.toString() } as Set
        Set<String> scopes = load("data/TriSystemOrderRunsRuleSetData.xml").'**'
                .findAll { it.name() == 'darpan.rule.RuleSetCompareScope' }.collect { it.@compareScopeId.toString() } as Set

        assertEquals(20, rules.size(), "the catalogue for runs A-open, B, C and C-cancel (DAR-BE-118: +2 causes on run C)")
        Set<String> seen = []
        rules.each { rule ->
            String key = "${rule.@compareScopeId}/${rule.@sequenceNum}".toString()
            assertTrue(seen.add(key), "duplicate rule ${key}: first-match order is the semantics")
            assertTrue(scopes.contains(rule.@compareScopeId.toString()), "unknown scope ${rule.@compareScopeId}")
            assertTrue(declaredEnums.contains(rule.@conclusionEnumId.toString()), "undeclared conclusion ${rule.@conclusionEnumId}")
            assertTrue(rule.@companyUserGroupId.toString() == 'GORJANA', "rule ${key} must name its tenant")
            def own = conditions.findAll { it.@compareScopeId == rule.@compareScopeId && it.@sequenceNum == rule.@sequenceNum }
            assertFalse(own.isEmpty(), "rule ${key} has no conditions: it would conclude every finding")
            own.each { c ->
                assertTrue(c.@checkLabel.toString().trim().length() > 0, "a condition of ${key} has no check label")
                assertTrue(c.@subject.toString() in ['FILE_1', 'FILE_2'])
            }
        }
    }

    @Test
    void everyConnectorTheRunsUseCarriesAnEvidenceProjection() {
        def doc = load("data/SourceSystemConnectorSeedData.xml")
        ['OMS_ORDER_ITEMS', 'OMS_ORDER_LINE_UNITS', 'SHOPIFY_ORDER_LINE_UNITS', 'NETSUITE_SUITEQL'].each { String systemEnumId ->
            def row = doc.'**'.find { it.name() == 'darpan.reconciliation.SourceSystemConnector' && it.@systemEnumId == systemEnumId }
            String json = row.@evidenceFieldsJson.toString()
            assertTrue(json.contains('"system"') && json.contains('"state"'), "${systemEnumId} has no evidence projection")
        }
    }

    /**
     * 2026-10-17. Done in NetSuite is BILLED and CLOSED is NetSuite's cancel, so each NetSuite line state a
     * finding can land on has to be NAMED, in both runs: the line states a filter drops are exactly
     * what explains the finding.
     */
    @Test
    void everyNetSuiteLineStateIsNamedInTheCompletedAndCancelledRuns() {
        // run C: HotWax completed, NetSuite not billed
        assertEquals("CONC_COMPLETED_CLOSED_IN_NS", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "CLOSED"]]))
        assertEquals("CONC_SHIPPED_NOT_BILLED", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "SHIPPED"]]))
        assertEquals("CONC_NS_BACKORDERED", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "OPEN", quantityBackordered: "1", lineLocation: "12"]]))
        // run C: NetSuite billed, HotWax not completed
        assertEquals("CONC_BILLED_NOT_COMPLETED", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_1",
                null, [presence: "KEPT", record: [lineState: "BILLED"]]))

        // run C-cancel: HotWax cancelled, NetSuite line not closed
        assertEquals("CONC_CANCEL_NOT_IN_NS", conclude("GORJANA_OMS_NS_CANCEL_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "OPEN"]]))
        ["SHIPPED", "BILLED"].each { String state ->
            assertEquals("CONC_CANCELLED_FULFILLED_IN_NS", conclude("GORJANA_OMS_NS_CANCEL_SCOPE", "MISSING_FROM_FILE_2",
                    null, [presence: "EXCLUDED", record: [lineState: state]]))
        }
        assertEquals("CONC_NEVER_REACHED_NS", conclude("GORJANA_OMS_NS_CANCEL_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "ABSENT", record: null]))
        // run C-cancel: NetSuite closed, HotWax not cancelled
        assertEquals("CONC_COMPLETED_CLOSED_IN_NS", conclude("GORJANA_OMS_NS_CANCEL_SCOPE", "MISSING_FROM_FILE_1",
                [presence: "EXCLUDED", record: [unitState: "COMPLETED"]], null))
        assertEquals("CONC_OPEN_CLOSED_IN_NS", conclude("GORJANA_OMS_NS_CANCEL_SCOPE", "MISSING_FROM_FILE_1",
                [presence: "EXCLUDED", record: [unitState: "OPEN"]], null))
    }

    /**
     * DAR-BE-118, Aditi 2026-10-23 on R1. F-100799: a shipped line is not billed because another line of its order is
     * backordered (NetSuite bills the order once), read from the order looked up in NetSuite (orderInNetsuite, only for
     * these rows). F-100767: an order whose payment was taken back (total 0, "partial payment") is never sent to NetSuite.
     */
    @Test
    void theCauseIsNamedWhenTheOrderIsBackorderedOrItsPaymentWasTakenBack() {
        assertEquals("CONC_ORDER_BACKORDER_HOLDS_BILLING", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "SHIPPED", orderInNetsuite: [tranId: "SO7330652"]]]))
        assertEquals("CONC_SHIPPED_NOT_BILLED", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "SHIPPED"]]), "no backordered line on the order")
        assertEquals("CONC_PARTIAL_PAYMENT_NO_NS", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                [presence: "KEPT", record: [orderGrandTotal: 0]], [presence: "ABSENT", record: null]))
        assertEquals("CONC_NEVER_REACHED_NS", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                [presence: "KEPT", record: [orderGrandTotal: 140]], [presence: "ABSENT", record: null]))
    }

    /**
     * DAR-BE-093, gorjana R3 ("every store sale carries its store"). A completed item NetSuite never shipped because its
     * line has no location is named for that cause, ahead of the backorder it shows as. SuiteQL omits a null column, so
     * the field is absent. A line that shipped had a location that worked, so it keeps its own conclusion.
     */
    @Test
    void anUnshippedLineWithNoLocationIsNamedForTheLocation() {
        assertEquals("CONC_NS_NO_LINE_LOCATION", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "OPEN", quantityBackordered: "1"]]))
        assertEquals("CONC_NS_NO_LINE_LOCATION", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "OPEN", lineLocation: ""]]))
        assertEquals("CONC_SHIPPED_NOT_BILLED", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "SHIPPED"]]))
        assertEquals("CONC_COMPLETED_CLOSED_IN_NS", conclude("GORJANA_OMS_NS_LINE_SCOPE", "MISSING_FROM_FILE_2",
                null, [presence: "EXCLUDED", record: [lineState: "CLOSED"]]))
    }

    private static String conclude(String scope, String bucket, Map file1, Map file2) {
        def doc = load("data/GorjanaConclusionRulesData.xml")
        Closure<Map> rows = { String name ->
            doc.'**'.findAll { it.name() == name && it.@compareScopeId == scope }.collect { node ->
                Map m = new LinkedHashMap(node.attributes())
                ["sequenceNum", "conditionSeq"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
                m
            }
        }
        Map<String, String> labels = doc.'**'.findAll { it.name() == 'moqui.basic.Enumeration' }
                .collectEntries { [(it.@enumId.toString()): it.@description.toString()] }
        List<Map> rules = RunConclusionStep.buildRules(rows('darpan.rule.RuleSetConclusionRule'),
                rows('darpan.rule.RuleSetConclusionCondition'), labels)
        Closure<Map> side = { Map s -> s == null ? [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]
                : s + [firstFieldPresence: s.presence, firstFieldRecord: s.record] }
        // The product engine, not the frozen DAR-UI-044 oracle (ConclusionEngineParityTests holds them equal on flat
        // catalogues): DAR-BE-118's rules read dotted paths into a looked-up record, which the oracle predates.
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(rules)
        try {
            return engine.conclude([[bucket: bucket, sides: [FILE_1: side(file1), FILE_2: side(file2)]]])[0].code
        } finally {
            engine.close()
        }
    }
}
