package reconciliation.rule

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S99-205 (OC6, end to end). The compile gate as production runs it: darpan.rules.allowRawDrl OFF.
 * Since S99-205b every smoke fixture seeds generated rules and no smoke JVM turns the property on, so every Moqui rule
 * run goes through the gate; this class is the gate's own proof: a stored tenant rule goes through
 * execute#RuleSetMatchedPairs, a hand-written one is refused loudly, a generator-made one runs.
 *
 * Not @SharedMoqui on purpose: it clears the System property for its own JVM (forkEvery=1) in case the environment set
 * it, which a shared-JVM class may not do, and restores it in @AfterAll.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RawDrlGateSmokeTests {
    private static final String ALLOW_RAW_DRL = "darpan.rules.allowRawDrl"
    private static final String TENANT = "KREWE"

    private ExecutionContext ec
    private String priorAllowRawDrl

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "raw-drl-gate-smoke")
        // The active tenant KREWE and its scope (the support's compare fixtures).
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
        // DAR-OPS-029 S99-205b: the shared fixtures are generated rules now, so the hand-written rule this class refuses is
        // its own (the SKU rule the support used to carry), under the active tenant KREWE.
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([ruleSetId: "RS_GATE_HANDWRITTEN", ruleSetName: "Gate hand-written",
                companyUserGroupId: TENANT]).createOrUpdate()
        ec.entity.makeValue("darpan.rule.Rule").setAll([ruleId: "GATE_HANDWRITTEN_SKU", ruleSetId: "RS_GATE_HANDWRITTEN", sequenceNum: 10,
                ruleType: "DRL", enabled: "Y", severity: "WARN", companyUserGroupId: TENANT,
                ruleLogic: 'rule "GATE_HANDWRITTEN_SKU" when $m : Map( this["file1"] != null, this["file2"] != null ) ' +
                        'eval(RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku"))) ' +
                        'then RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "sku", ((Map) $m.get("file1")).get("sku"), ' +
                        '((Map) $m.get("file2")).get("sku"), "WARN", "SKU mismatch"); end']).createOrUpdate()
        priorAllowRawDrl = System.getProperty(ALLOW_RAW_DRL)
        System.clearProperty(ALLOW_RAW_DRL)
        RuleEngineSupport.resetForTests()
    }

    @AfterAll
    void cleanup() {
        if (priorAllowRawDrl != null) System.setProperty(ALLOW_RAW_DRL, priorAllowRawDrl)
        RuleEngineSupport.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
    }

    @Test
    void aHandWrittenStoredRuleIsRefusedLoudlyAndNamesNoLogic() {
        Map<String, Object> out = runPairs("RS_GATE_HANDWRITTEN")

        String error = out.error as String
        assertTrue(error?.contains("is not a generated field comparison"), "the gate did not refuse the hand-written rule: ${out}")
        assertFalse(error.contains("RuleDiffSupport.valuesDiffer"), "the refusal must never quote the rule's logic: ${error}")
        assertTrue(((List) (out.diffResults ?: [])).isEmpty(), "a refused rule set produced differences: ${out.diffResults}")
    }

    @Test
    void aGeneratedStoredRuleRunsWithTheGateOn() {
        String expression = '{"type":"FIELD_COMPARISON","file1FieldPath":"sku","file2FieldPath":"sku","operator":"="}'
        String generated = FieldComparisonRuleLogicGenerator.generate(expression, "", "", "GATE_SKU", "WARN", 0)
        assertTrue(FieldComparisonRuleLogicGenerator.isGeneratedRuleLogic(generated))
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([ruleSetId: "RS_GATE_GENERATED", ruleSetName: "Gate generated",
                companyUserGroupId: TENANT]).createOrUpdate()
        ec.entity.makeValue("darpan.rule.Rule").setAll([ruleId: "GATE_SKU", ruleSetId: "RS_GATE_GENERATED", sequenceNum: 10,
                ruleText: "sku = sku", ruleType: "FIELD_COMPARISON", expression: expression, ruleLogic: generated,
                enabled: "Y", severity: "WARN", companyUserGroupId: TENANT]).createOrUpdate()

        Map<String, Object> out = runPairs("RS_GATE_GENERATED")

        assertNull(out.error, "a generated rule was refused: ${out.error}")
        assertEquals(1, out.ruleCount)
        List<Map> diffs = (List<Map>) out.diffResults
        assertEquals(["P1"], diffs*.primaryId, "only the pair whose sku differs is a difference")
        assertEquals(["FIELD_MISMATCH"], diffs*.diffType)
        assertEquals(["GATE_SKU"], diffs*.ruleId)
    }

    private Map<String, Object> runPairs(String ruleSetId) {
        List<Map> facts = [
                [primaryId: "P1", file1: [sku: "A"], file2: [sku: "B"]],
                [primaryId: "P2", file1: [sku: "C"], file2: [sku: "C"]],
        ]
        Map<String, Object> out = ec.service.sync()
                .name("reconciliation.ReconciliationRuleEngineServices.execute#RuleSetMatchedPairs")
                .parameters([ruleSetId: ruleSetId, dataList: facts])
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return out
    }
}
