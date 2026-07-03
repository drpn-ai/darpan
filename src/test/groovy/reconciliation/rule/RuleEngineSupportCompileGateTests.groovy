package reconciliation.rule

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

// NB: the "allowed" cases call the gate directly — a thrown exception fails the test. We avoid
// JUnit5 assertDoesNotThrow here because a Groovy closure coerces ambiguously to both its
// Executable and ThrowingSupplier overloads.

/**
 * MACH P0 #5 — the rule-engine COMPILE path must fail closed on tenant-authored raw DRL.
 *
 * RuleLogicValidator historically gated only the save#Rule boundary
 * (ReconciliationRuleEngineServices.xml). A Rule row whose ruleLogic reached the DB by any
 * other path — data load, upgrade-data, a direct entity write, or creation predating the
 * validator — was compiled by RuleEngineSupport unvalidated (RCE-class residual). These tests
 * pin the compile-time backstop + default-OFF raw-DRL flag:
 *   (1) every ruleLogic is re-validated at compile time (throw on violation);
 *   (2) a raw `rule…end` block is rejected unless it is a platform rule (null companyUserGroupId,
 *       loaded by ops), a server-regenerable FIELD_COMPARISON rule, or darpan.rules.allowRawDrl=true.
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

    // NB: the `eval(...)` tokens below are Drools DRL constraint syntax inside test-fixture strings
    // (mirroring FieldComparisonRuleLogicGenerator output), NOT a JS/Python eval — no code is executed here.
    private static final String FIELD_COMPARISON_DRL = '''rule "FIELD_COMPARISON_1"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(reconciliation.rule.RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku")))
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "sku", ((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku"), "WARN", "SKU mismatch");
end'''

    private static final String FIELD_COMPARISON_EXPRESSION =
            '{"type":"FIELD_COMPARISON","file1FieldPath":"sku","file2FieldPath":"sku","operator":"="}'

    @AfterEach
    void clearFlag() {
        System.clearProperty(ALLOW_RAW_DRL_PROP)
    }

    @Test
    void tenantRawDrlRejectedWhenFlagOff() {
        def ex = assertThrows(IllegalArgumentException) {
            RuleEngineSupport.assertCompilableRuleLogic("TENANT_MARK", BENIGN_TENANT_DRL, "DRL", null, "TENANT_A")
        }
        assertTrue(ex.message?.toLowerCase()?.contains("raw drl"),
                "expected a raw-DRL fail-closed message, got: ${ex.message}")
    }

    @Test
    void dangerousPayloadRejectedAtCompileTimeEvenWithFlagOn() {
        // The validator backstop rejects a forbidden construct regardless of tenant/platform or flag.
        System.setProperty(ALLOW_RAW_DRL_PROP, "true")
        assertThrows(IllegalArgumentException) {
            RuleEngineSupport.assertCompilableRuleLogic("PWN", DANGEROUS_DRL, "DRL", null, null)
        }
    }

    @Test
    void platformRawDrlAllowed() {
        // companyUserGroupId == null => platform rule loaded by ops via data/*.xml; trusted. Must not throw.
        RuleEngineSupport.assertCompilableRuleLogic("PLATFORM_MARK", BENIGN_TENANT_DRL, "DRL", null, null)
    }

    @Test
    void fieldComparisonTenantRuleAllowed() {
        // Server-regenerable FIELD_COMPARISON rule (the UI's only rule type). Must not throw.
        RuleEngineSupport.assertCompilableRuleLogic(
                "FIELD_COMPARISON_1", FIELD_COMPARISON_DRL, "FIELD_COMPARISON", FIELD_COMPARISON_EXPRESSION, "TENANT_A")
    }

    @Test
    void tenantRawDrlAllowedWhenFlagOn() {
        System.setProperty(ALLOW_RAW_DRL_PROP, "true")
        // Explicit opt-in for the environment. Must not throw.
        RuleEngineSupport.assertCompilableRuleLogic("TENANT_MARK", BENIGN_TENANT_DRL, "DRL", null, "TENANT_A")
    }

    @Test
    void nullRuleLogicIsNoop() {
        // No ruleLogic to gate. Must not throw.
        RuleEngineSupport.assertCompilableRuleLogic("EMPTY", null, "DRL", null, "TENANT_A")
    }

    @Test
    void buildRuleSetDrlFailsClosedOnTenantRawDrl() {
        List rules = [[ruleId: "TENANT_MARK", ruleLogic: BENIGN_TENANT_DRL, ruleType: "DRL", expression: null, sequenceNum: 10]]
        List warnings = []
        assertThrows(IllegalArgumentException) {
            RuleEngineSupport.buildRuleSetDrl("RS1", "reconciliation.rules.tenant_a.rs1", rules, warnings, "TENANT_A")
        }
    }

    @Test
    void buildRuleSetDrlCompilesPlatformRawDrl() {
        List rules = [[ruleId: "PLATFORM_MARK", ruleLogic: BENIGN_TENANT_DRL, ruleType: "DRL", expression: null, sequenceNum: 10]]
        List warnings = []
        String drl = RuleEngineSupport.buildRuleSetDrl("RS2", "reconciliation.rules.default.rs2", rules, warnings, null)
        assertTrue(drl?.contains('rule "TENANT_MARK"'), "platform raw DRL should be emitted into the compiled package")
    }
}
