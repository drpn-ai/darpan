package darpan.reconciliation.conclusion

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test
import reconciliation.rule.FieldComparisonRuleLogicGenerator
import reconciliation.rule.RuleDiffSupport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-063 Part 2. The first scenario is rows only, so the rows are the thing to test: a typo here
 * does not fail to load, it concludes wrongly forever. The tree must compile, the amount rule's stored
 * DRL must be exactly what the server generator makes from its own expression, and the amount check
 * must be scale-safe.
 */
class ShopifyNsBilledRuleSetDataTests {

    static final String FILE = "data/ShopifyNsBilledRuleSetData.xml"
    static final String SCOPE = "GORJANA_SHOPIFY_NS_BILLED_SCOPE"

    private static Path componentRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("entity/RuleEntities.xml"))) {
            Path candidate = p.resolve("runtime/component/darpan")
            if (Files.exists(candidate.resolve("entity/RuleEntities.xml"))) return candidate
            p = p.parent
        }
        return p
    }

    private static def doc() { new XmlSlurper().parse(componentRoot().resolve(FILE).toFile()) }

    private static Map attrs(def node) {
        Map m = new LinkedHashMap(node.attributes())
        ["sequenceNum", "conditionSeq", "parentSequenceNum"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
        return m
    }

    @Test
    void itIsTenantConfigNotSeed() {
        assertTrue(Files.readString(componentRoot().resolve(FILE)).contains('<entity-facade-xml type="darpan-tenant-config">'))
    }

    @Test
    void theTreeCompilesAndEveryConclusionIsDeclared() {
        def d = doc()
        List<Map> ruleRows = d.'**'.findAll { it.name() == 'darpan.rule.RuleSetConclusionRule' }.collect { attrs(it) }
        List<Map> condRows = d.'**'.findAll { it.name() == 'darpan.rule.RuleSetConclusionCondition' }.collect { attrs(it) }
        Set<String> declared = d.'**'.findAll { it.name() == 'moqui.basic.Enumeration' }.collect { it.@enumId.toString() } as Set
        Set<String> reused = ["CONC_NEVER_REACHED_NS", "CONC_GIFT_CARD_NOT_IN_NS", "CONC_POS_NOT_IN_NS"] as Set
        ruleRows.each { Map r ->
            assertEquals(SCOPE, r.compareScopeId)
            assertEquals("GORJANA", r.companyUserGroupId)
            assertTrue(declared.contains(r.conclusionEnumId) || reused.contains(r.conclusionEnumId), "undeclared ${r.conclusionEnumId}")
            assertTrue(r.parentSequenceNum != null || r.appliesToBucket, "root ${r.sequenceNum} has no bucket")
            assertFalse(condRows.findAll { it.sequenceNum == r.sequenceNum }.isEmpty(), "rule ${r.sequenceNum} has no conditions")
        }
        condRows.each { Map c -> assertTrue(c.checkLabel?.toString()?.trim() as boolean, "condition without a check label: ${c}") }
        List<Map> rules = darpan.facade.reconciliation.RunConclusionStep.buildRules(ruleRows, condRows, [:])
        ConclusionTreeEngine.compile(rules).close()
    }

    @Test
    void storedAmountDrlIsExactlyWhatTheGeneratorMakes() {
        def d = doc()
        def rule = d.'**'.find { it.name() == 'darpan.rule.Rule' && it.@ruleId == 'GORJANA_SNB_AMOUNT' }
        def src1 = d.'**'.find { it.name() == 'darpan.rule.RuleSetCompareSource' && it.@fileSide == 'FILE_1' }
        def src2 = d.'**'.find { it.name() == 'darpan.rule.RuleSetCompareSource' && it.@fileSide == 'FILE_2' }
        String expected = FieldComparisonRuleLogicGenerator.generate(rule.expression.text(),
                src1.@primaryIdExpression.toString(), src2.@primaryIdExpression.toString(), "GORJANA_SNB_AMOUNT", "WARN", 0)
        assertEquals(expected, rule.ruleLogic.text().trim())
    }

    @Test
    void amountRuleIsScaleSafeAndBlankIsADifference() {
        List pre = ["STRING_TO_NUMBER"]
        assertFalse(RuleDiffSupport.violatesOperator(RuleDiffSupport.applyPreActions("84.00", pre),
                RuleDiffSupport.applyPreActions("84", pre), "="))
        assertTrue(RuleDiffSupport.violatesOperator(RuleDiffSupport.applyPreActions("84.00", pre),
                RuleDiffSupport.applyPreActions(null, pre), "="))
        assertTrue(RuleDiffSupport.violatesOperator(RuleDiffSupport.applyPreActions("84.00", pre),
                RuleDiffSupport.applyPreActions("83.99", pre), "="))
    }

    @Test
    void theNetSuiteSideReportsDuplicateSalesOrdersAndATreeRootNamesThem() {
        // Task 12: the first live run died on duplicate NetSuite keys; this side now reports them instead.
        def d = doc()
        def ns = d.'**'.find { it.name() == 'darpan.rule.RuleSetCompareSource' && it.@fileSide == 'FILE_2' }
        assertEquals("FINDING", ns.@duplicateKeyHandling.toString())
        def root = d.'**'.find { it.name() == 'darpan.rule.RuleSetConclusionRule' && it.@appliesToBucket == 'DUPLICATE_IN_FILE_2' }
        assertEquals("CONC_NS_DUPLICATE_SO", root.@conclusionEnumId.toString())
    }
}
