package reconciliation.rule

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

// NB: the "allowed" cases call the gate directly — a thrown exception fails the test. We avoid
// JUnit5 assertDoesNotThrow here because a Groovy closure coerces ambiguously to both its
// Executable and ThrowingSupplier overloads.

/**
 * MACH P0 #5 — the rule-engine COMPILE path fails closed on tenant-authored rule logic.
 *
 * DAR-OPS-029 S02: a stored ruleLogic compiles only when it is exactly FieldComparisonRuleLogicGenerator's output (the
 * only rule logic a tenant can author), or when darpan.rules.allowRawDrl=true. The gate used to trust ruleType=
 * FIELD_COMPARISON beside any expression, any bare constraint, and a null tenant; each of those now fails.
 * RuleLogicValidator runs first in every case, flag or not.
 *
 * NB: `eval(...)` below is Drools DRL syntax inside test-fixture strings, not a JS/Python eval; nothing is executed.
 */
class RuleEngineSupportCompileGateTests {

    private static final String ALLOW_RAW_DRL_PROP = "darpan.rules.allowRawDrl"

    private static final String BENIGN_TENANT_DRL = '''rule "TENANT_MARK"
when
    $m : Map(this["status"] == null)
then
    $m.put("status", "REVIEW");
end'''

    private static final String DANGEROUS_DRL = '''rule "PWN"
when
    $m : Map(this["file1"] != null)
then
    new java.io.FileWriter("/tmp/pwn").write("x");
end'''

    // Hand-written, validator-clean, and NOT what the generator emits. Before S02 the gate let it compile because the
    // row said ruleType=FIELD_COMPARISON and carried an expression.
    private static final String HAND_WRITTEN_FIELD_COMPARISON_DRL = '''rule "FIELD_COMPARISON_1"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(reconciliation.rule.RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku")))
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "sku", ((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku"), "WARN", "SKU mismatch");
end'''

    private static String generated(String operator = "=", String preActions = "", String tolerance = "") {
        String expression = '{"type":"FIELD_COMPARISON","file1FieldPath":"$.orders[*].sku","file2FieldPath":"$.items[*].sku",' +
                '"operator":"' + operator + '"' + preActions + tolerance + '}'
        String drl = FieldComparisonRuleLogicGenerator.generate(expression, '$.orders[*].id', '$.items[*].id', "R1", "WARN", 0)
        assertNotNull(drl)
        return drl
    }

    @AfterEach
    void clearFlag() {
        System.clearProperty(ALLOW_RAW_DRL_PROP)
    }

    @Test
    void tenantRawDrlRejectedWhenFlagOff() {
        def ex = assertThrows(IllegalArgumentException) {
            RuleEngineSupport.assertCompilableRuleLogic("TENANT_MARK", BENIGN_TENANT_DRL)
        }
        assertTrue(ex.message?.contains("not a generated field comparison"), "got: ${ex.message}")
    }

    @Test
    void dangerousPayloadRejectedAtCompileTimeEvenWithFlagOn() {
        System.setProperty(ALLOW_RAW_DRL_PROP, "true")
        assertThrows(IllegalArgumentException) {
            RuleEngineSupport.assertCompilableRuleLogic("PWN", DANGEROUS_DRL)
        }
    }

    @Test
    void generatedFieldComparisonCompilesForEveryViolationForm() {
        // Each form generate() can emit: Objects.equals (=, !=), violatesOperator (ordering, presence, list, pre-actions),
        // differsByAtLeast (tolerance). Must not throw.
        [generated("="), generated("!="), generated(">="), generated("requires"), generated("all in"),
         generated("=", ',"preActions":[{"fieldSide":"file1","action":"STRING_TO_NUMBER"},{"fieldSide":"file2","action":"STRING_TO_INT"}]'),
         generated("=", ',"preActions":[{"fieldSide":"file2","action":"STRING_TO_NUMBER"}]', ',"tolerance":"1.00"')
        ].each { String drl -> RuleEngineSupport.assertCompilableRuleLogic("R1", drl) }
    }

    @Test
    void handWrittenDrlLabelledFieldComparisonIsRejected() {
        // The row's ruleType and expression are no longer consulted: only the logic itself is.
        assertNotNull(RuleEngineSupport.compileGateViolation("FIELD_COMPARISON_1", HAND_WRITTEN_FIELD_COMPARISON_DRL))
    }

    @Test
    void bareConstraintLogicIsRejectedWhenFlagOff() {
        // A non-`rule` ruleLogic is pasted into Map(...) as an MVEL constraint; it is tenant-authored code too.
        assertNotNull(RuleEngineSupport.compileGateViolation("C1", 'this["status"] == "Pending"'))
    }

    @Test
    void nullTenantGetsNoExemption() {
        // The old "platform rule" branch (null companyUserGroupId) is gone: such a rule set never reaches the compiler.
        assertNotNull(RuleEngineSupport.compileGateViolation("PLATFORM_MARK", BENIGN_TENANT_DRL))
    }

    @Test
    void tamperedGeneratorOutputIsRejected() {
        String drl = generated(">")
        List<String> tampered = [
                drl.replace('    );\nend', '    );\n    results.add($m);\nend'),                       // extra consequence
                drl.replace('eval(', 'eval(true || '),                                                  // altered condition
                drl.replace('"WARN"', '"WARN" + $m.size()'),                                            // code beside a literal
                drl.replace('Object _file1Value = ', 'Object _file1Value = $m.size() + '),              // altered value
                drl.replace('reconciliation.rule.RuleDiffSupport.violatesOperator', 'reconciliation.rule.RuleDiffSupport.valuesDiffer'),
                drl.replace('"sku"', '"sk\\u0075"'),                                                    // Unicode escape
                drl + '\nrule "SECOND"\nwhen\n    $x : Map()\nthen\nend',                              // a second rule
        ]
        tampered.eachWithIndex { String t, int i ->
            assertTrue(t != drl, "mutation ${i} did not change the fixture")
            assertNotNull(RuleEngineSupport.compileGateViolation("R1", t), "mutation ${i} passed the gate:\n${t}")
        }
    }

    @Test
    void aLiteralSlotHoldsOnlyWhatTheGeneratorCanPutThere() {
        // QA refute: the shape alone took any literal in any slot; differsByAtLeast(a, b, "0") flags every numeric pair.
        String tolerant = generated("=", "", ',"tolerance":"1.00"')
        String ordered = generated(">=")
        assertNull(RuleEngineSupport.compileGateViolation("R1", tolerant))
        assertNull(RuleEngineSupport.compileGateViolation("R1", ordered))
        [tolerant.replace('"1.00"))', '"0"))'), tolerant.replace('"1.00"))', '"-1"))'),
         tolerant.replace('"1.00"))', '"1e9"))'), tolerant.replace('"1.00"))', '""))'),
         ordered.replace('">="))', '"matches"))'), ordered.replace('">="))', '""))'),
         ordered.replace('this["file1"] != null', 'this["nope"] != null')
        ].eachWithIndex { String t, int i ->
            assertTrue(t != tolerant && t != ordered, "mutation ${i} did not change the fixture")
            assertNotNull(RuleEngineSupport.compileGateViolation("R1", t), "mutation ${i} passed the gate:\n${t}")
        }
    }

    @Test
    void theRefusalNamesTheRuleAndQuotesNoneOfItsLogic() {
        // A run's error reaches notifications: it names the rule, never the stored text (not even the validator's fragment).
        System.setProperty(ALLOW_RAW_DRL_PROP, "true")
        String validatorRefusal = RuleEngineSupport.compileGateViolation("PWN_RULE", DANGEROUS_DRL)
        System.clearProperty(ALLOW_RAW_DRL_PROP)
        String shapeRefusal = RuleEngineSupport.compileGateViolation("MARK_RULE", BENIGN_TENANT_DRL)
        [[validatorRefusal, "PWN_RULE"], [shapeRefusal, "MARK_RULE"]].each { List pair ->
            String refusal = pair[0] as String
            assertTrue(refusal.contains(pair[1] as String), refusal)
            ["FileWriter", "java.io", "/tmp/pwn", "REVIEW", "status", "\$m"].each { String fragment ->
                assertTrue(!refusal.contains(fragment), "refusal quotes '${fragment}': ${refusal}")
            }
        }
    }

    @Test
    void tenantRawDrlAllowedWhenFlagOn() {
        System.setProperty(ALLOW_RAW_DRL_PROP, "true")
        RuleEngineSupport.assertCompilableRuleLogic("TENANT_MARK", BENIGN_TENANT_DRL)
    }

    @Test
    void nullRuleLogicIsNoop() {
        assertNull(RuleEngineSupport.compileGateViolation("EMPTY", null))
    }

    @Test
    void buildRuleSetDrlFailsClosedOnTenantRawDrl() {
        List rules = [[ruleId: "TENANT_MARK", ruleLogic: BENIGN_TENANT_DRL, sequenceNum: 10]]
        assertThrows(IllegalArgumentException) {
            RuleEngineSupport.buildRuleSetDrl("RS1", "reconciliation.rules.tenant_a.rs1", rules, [])
        }
    }

    @Test
    void buildRuleSetDrlEmitsGeneratedAndParsedRules() {
        List rules = [[ruleId: "R1", ruleLogic: generated("="), sequenceNum: 10],
                      [ruleId: "R2", ruleText: "status is Pending", sequenceNum: 20]]
        String drl = RuleEngineSupport.buildRuleSetDrl("RS2", "reconciliation.rules.t.rs2", rules, [])
        assertTrue(drl.contains('rule "R1"'), drl)
        assertTrue(drl.contains('this["status"] == "Pending"'), drl)
    }
}
