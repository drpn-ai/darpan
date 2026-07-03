package reconciliation.rule

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Audit 2026-06-11 #1 durable fix — proves the server-side FieldComparisonRuleLogicGenerator produces
 * BYTE-IDENTICAL ruleLogic to the real darpan-ui generator, for fixtures captured from the UI itself
 * (src/test/resources/reconciliation/rule/rulelogic-goldens.json). If the UI generator changes, these
 * goldens must be re-captured and this test will catch the drift.
 */
class FieldComparisonRuleLogicGeneratorTests {

    private static List<Map> goldens() {
        def url = FieldComparisonRuleLogicGeneratorTests.class.getResource("/reconciliation/rule/rulelogic-goldens.json")
        assertNotNull(url, "golden fixture resource missing")
        return (List<Map>) new JsonSlurper().parse(url)
    }

    @Test
    void serverGenerationIsByteIdenticalToUiForAllGoldens() {
        List<Map> cases = goldens()
        assertTrue(cases.size() >= 16, "expected the full golden set")
        cases.each { Map g ->
            String actual = FieldComparisonRuleLogicGenerator.generate(
                    (String) g.expression,
                    (String) g.file1PrimaryIdExpression,
                    (String) g.file2PrimaryIdExpression,
                    (String) g.ruleId,
                    (String) g.severity,
                    ((Number) g.index).intValue())
            assertEquals((String) g.ruleLogic, actual,
                    "ruleLogic mismatch for golden case '${g.name}' (index ${g.index})")
        }
    }

    @Test
    void nonFieldComparisonExpressionReturnsNull() {
        assertNull(FieldComparisonRuleLogicGenerator.generate('{"type":"CUSTOM"}', "id", "id", "r", "WARN", 0))
        assertNull(FieldComparisonRuleLogicGenerator.generate(null, "id", "id", "r", "WARN", 0))
        assertNull(FieldComparisonRuleLogicGenerator.generate("not json", "id", "id", "r", "WARN", 0))
        // FIELD_COMPARISON missing field paths is malformed → null (caller keeps the validator path).
        assertNull(FieldComparisonRuleLogicGenerator.generate('{"type":"FIELD_COMPARISON","operator":"="}', "id", "id", "r", "WARN", 0))
    }

    @Test
    void regeneratedLogicPassesTheRuleLogicValidator() {
        // The whole point: server-regenerated FIELD_COMPARISON DRL must be accepted by the gate (it is
        // the canonical safe shape), so regeneration never produces something the validator rejects.
        goldens().each { Map g ->
            String generated = FieldComparisonRuleLogicGenerator.generate(
                    (String) g.expression, (String) g.file1PrimaryIdExpression, (String) g.file2PrimaryIdExpression,
                    (String) g.ruleId, (String) g.severity, ((Number) g.index).intValue())
            assertNull(RuleLogicValidator.firstViolation(generated),
                    "validator rejected regenerated logic for '${g.name}': ${RuleLogicValidator.firstViolation(generated)}")
        }
    }

    @Test
    void maliciousExpressionFieldsCannotInjectCode() {
        // Field paths / operators are attacker-influenceable via the expression JSON. They must end up as
        // escaped string segments, never as live code, and the result must still pass the validator.
        String evil = '{"type":"FIELD_COMPARISON","file1FieldPath":"$.a[*].x\\");} java.lang.Runtime.getRuntime().exec(\\"id\\");//","file2FieldPath":"$[*].y","operator":"="}'
        String generated = FieldComparisonRuleLogicGenerator.generate(evil, '$.a[*].id', '$[*].id', null, 'WARN', 0)
        assertNotNull(generated)
        // The injected text is escaped inside a .get("...") string literal, so the validator accepts it
        // (it is inert data) — and critically there is no live Runtime reference in code position.
        assertNull(RuleLogicValidator.firstViolation(generated))
    }
}
