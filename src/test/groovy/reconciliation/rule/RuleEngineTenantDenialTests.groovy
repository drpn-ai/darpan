package reconciliation.rule

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * P0 #4 step 2 — cross-tenant denial tests for {@link RuleEngineSupport}.
 *
 * <p>compileRuleSet, executeRuleSetFacts and executeMatchedPairFacts fail closed when the requested {@code ruleSetId}
 * belongs to a foreign tenant or does not exist. Stub execution context ({@link RuleEngineSupportTestEc}), no Moqui.
 * DAR-OPS-029 S02 (QA): the rule set has a real active rule, and each refusal must be the tenant gate's own
 * ("not found or access denied"), never "has no active rules"; the same rule set in its own tenant compiles.</p>
 */
class RuleEngineTenantDenialTests {

    private static final String DENIED = "not found or access denied"
    private static final List<Map> RULES = [[ruleId: "R1", sequenceNum: 10, enabled: "Y", ruleText: "status is Pending"]]

    private static Expando ecFor(String activeTenant, String ruleSetId, String owner) {
        Map ruleSet = owner ? [ruleSetId: ruleSetId, companyUserGroupId: owner, ruleSetName: ruleSetId] : null
        return RuleEngineSupportTestEc.ec(activeTenant, ruleSet, RULES.collect { it + [ruleSetId: ruleSetId] })
    }

    private static void assertDenied(Map result, String what) {
        assertNotNull(result.error, "${what} must return an error")
        assertTrue((result.error as String).contains(DENIED), "${what} was refused for another reason: ${result.error}")
    }

    @AfterEach
    void clearCache() {
        RuleEngineSupport.resetForTests()
    }

    @Test
    void theSameRuleSetCompilesInItsOwnTenant() {
        // The positive control: without it, every denial below could pass for an unrelated error.
        Map result = RuleEngineSupport.compileRuleSet(ecFor("ACME", "RS_ACME", "ACME"), "RS_ACME", false, true)
        assertNull(result.error, "own tenant's rule set failed: ${result.error}")
        assertNotNull(result.kieContainer)
    }

    @Test
    void compileRuleSetReturnsErrorForForeignTenantRuleSetId() {
        Map result = RuleEngineSupport.compileRuleSet(ecFor("KREWE", "RS_ACME", "ACME"), "RS_ACME", false, true)
        assertDenied(result, "compileRuleSet (foreign)")
        assertNull(result.kieContainer, "compileRuleSet must NOT return a KieContainer for a foreign-tenant ruleSetId")
    }

    @Test
    void compileRuleSetReturnsErrorWhenRuleSetIdIsMissing() {
        Map result = RuleEngineSupport.compileRuleSet(ecFor("KREWE", "RS_MISSING", null), "RS_MISSING", false, true)
        assertDenied(result, "compileRuleSet (missing)")
        assertNull(result.kieContainer)
    }

    @Test
    void executeRuleSetFactsReturnsErrorForForeignTenantRuleSetId() {
        Map result = RuleEngineSupport.executeRuleSetFacts(ecFor("KREWE", "RS_GORJANA", "GORJANA"), "RS_GORJANA",
                [[status: "Pending"]], false)
        assertDenied(result, "executeRuleSetFacts (foreign)")
    }

    @Test
    void executeMatchedPairFactsReturnsErrorForForeignTenantRuleSetId() {
        Map result = RuleEngineSupport.executeMatchedPairFacts(ecFor("KREWE", "RS_ACME", "ACME"), "RS_ACME", [[status: "Pending"]])
        assertDenied(result, "executeMatchedPairFacts (foreign)")
        assertTrue(result.diffResults == null || ((List) result.diffResults).isEmpty(),
                "executeMatchedPairFacts must not leak diff results for a foreign-tenant ruleSetId")
    }
}
