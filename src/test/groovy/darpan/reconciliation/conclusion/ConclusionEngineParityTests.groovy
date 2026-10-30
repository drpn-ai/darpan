package darpan.reconciliation.conclusion

import darpan.facade.reconciliation.RunConclusionStep
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-063. A real conclusion catalogue (DAR-UI-044, flat; the fixture data/test/ConclusionParityCatalogueData.xml,
 * cut from a tenant config pack, DAR-BE-160) must conclude IDENTICALLY under the Drools engine: same code, label,
 * checks and question for every finding shape the catalogue can see. The matrix is every presence x every value any
 * condition names (plus values no condition names, plus null), for both sides, in every bucket — all concluded in ONE session per scope, which is also the
 * many-facts isolation proof at scale.
 */
class ConclusionEngineParityTests {

    private static final List<String> ORACLE_OPERATORS = ["IN", "NOT_IN", "GT_ZERO", "BLANK", "NOT_BLANK"]

    private static Path componentRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("entity/RuleEntities.xml"))) {
            Path candidate = p.resolve("runtime/component/darpan")
            if (Files.exists(candidate.resolve("entity/RuleEntities.xml"))) return candidate
            p = p.parent
        }
        return p
    }

    private static Map attrs(def node) {
        Map m = new LinkedHashMap(node.attributes())
        ["sequenceNum", "conditionSeq", "parentSequenceNum"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
        return m
    }

    @Test
    void aRealCatalogueConcludesIdentically() {
        def doc = new XmlSlurper().parse(componentRoot().resolve("data/test/ConclusionParityCatalogueData.xml").toFile())
        List<Map> ruleRows = doc.'**'.findAll { it.name() == 'darpan.rule.RuleSetConclusionRule' }.collect { attrs(it) }
        List<Map> condRows = doc.'**'.findAll { it.name() == 'darpan.rule.RuleSetConclusionCondition' }.collect { attrs(it) }
        Map<String, String> labels = doc.'**'.findAll { it.name() == 'moqui.basic.Enumeration' }
                .collectEntries { [(it.@enumId.toString()): it.@description.toString()] }
        int compared = 0
        ruleRows*.compareScopeId.unique().each { String scope ->
            // The oracle is DAR-UI-044's frozen evaluator: parity is over what it has. Later operators (GT, WITHIN_HOURS)
            // and dotted paths (a looked-up record, DAR-BE-118) are proven by ConclusionConditionSupportTests and
            // ConclusionLookupSupportTests; their rules are left out of the comparison.
            List<Map> rules = RunConclusionStep.buildRules(ruleRows.findAll { it.compareScopeId == scope },
                    condRows.findAll { it.compareScopeId == scope }, labels)
                    .findAll { Map r -> ((List<Map>) r.conditions).every {
                        (!it.operator || it.operator in ORACLE_OPERATORS) && !(it.fieldExpression as String)?.contains(".") } }
            List<Map> findings = matrix(rules)
            ConclusionTreeEngine engine = ConclusionTreeEngine.compile(rules)
            List<Map> actual
            try {
                actual = engine.conclude(findings)
            } finally {
                engine.close()
            }
            findings.eachWithIndex { Map f, int i ->
                Map expected = FlatEvaluatorOracle.evaluate(rules, (String) f.bucket, (Map) f.sides)
                Map got = actual[i]
                assertEquals([expected.code, expected.label, expected.checks, expected.question],
                        [got.code, got.label, got.checks, got.question], "${scope} finding ${i}: ${f}")
                compared++
            }
        }
        assertTrue(compared > 1000, "matrix too small to mean anything: ${compared}")
    }

    private static List<Map> matrix(List<Map> rules) {
        Map<String, Set<String>> valuesByField = [:]
        rules.each { Map r -> ((List<Map>) r.conditions).each { Map c ->
            if (c.fieldExpression) valuesByField.computeIfAbsent((String) c.fieldExpression) { new LinkedHashSet<String>() }
                    .addAll(((List) c.conditionValues).collect { it.toString() } + ["0", "1", "OTHER"])
        } }
        List<Map> records = [null, [:]]
        valuesByField.each { String field, Set<String> values -> values.each { String v -> records << [(field): v] } }
        List<Map> states = []
        ["KEPT", "EXCLUDED", "ABSENT", "UNKNOWN"].each { String p ->
            records.each { Map rec -> states << [presence: p, record: rec, firstFieldPresence: p, firstFieldRecord: rec] }
        }
        List<Map> findings = []
        ["MISSING_FROM_FILE_1", "MISSING_FROM_FILE_2", "RULE"].each { String bucket ->
            states.each { Map s1 -> states.each { Map s2 -> findings << [bucket: bucket, sides: [FILE_1: s1, FILE_2: s2]] } }
        }
        return findings
    }
}
