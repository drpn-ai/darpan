package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * P0 #4 step 2 — cross-tenant denial tests for {@link RuleEngineSupport}.
 *
 * <p>compileRuleSet, executeRuleSetFacts and executeMatchedPairFacts fail closed when the requested {@code ruleSetId}
 * belongs to a foreign tenant or does not exist. Stub execution context ({@link RuleEngineSupportTestEc}), no Moqui.</p>
 */
class RuleEngineTenantDenialTests {

    private static final Map ACME_RULE_SET = [ruleSetId: "RS_ACME", companyUserGroupId: "ACME", ruleSetName: "Acme RS"]

    @Test
    void compileRuleSetReturnsErrorForForeignTenantRuleSetId() {
        Map result = RuleEngineSupport.compileRuleSet(RuleEngineSupportTestEc.ec("KREWE", ACME_RULE_SET), "RS_ACME", false, false)
        assertNotNull(result, "compileRuleSet must return a result map (not null) even on denial")
        assertNotNull(result.error, "compileRuleSet must return an error for a foreign-tenant ruleSetId — no compilation")
        assertNull(result.kieContainer, "compileRuleSet must NOT return a KieContainer for a foreign-tenant ruleSetId")
    }

    @Test
    void compileRuleSetReturnsErrorWhenRuleSetIdIsMissing() {
        Map result = RuleEngineSupport.compileRuleSet(RuleEngineSupportTestEc.ec("KREWE", null), "RS_MISSING", false, false)
        assertNotNull(result.error, "compileRuleSet must return an error for a missing ruleSetId")
        assertNull(result.kieContainer, "No KieContainer for a missing ruleSetId")
    }

    @Test
    void executeRuleSetFactsReturnsErrorForForeignTenantRuleSetId() {
        Map gorjana = [ruleSetId: "RS_GORJANA", companyUserGroupId: "GORJANA", ruleSetName: "Gorjana RS"]
        Map result = RuleEngineSupport.executeRuleSetFacts(RuleEngineSupportTestEc.ec("KREWE", gorjana), "RS_GORJANA", [[key: "value"]], false)
        assertNotNull(result.error, "executeRuleSetFacts must return an error for a foreign-tenant ruleSetId — no rule execution")
    }

    @Test
    void executeMatchedPairFactsReturnsErrorForForeignTenantRuleSetId() {
        Map result = RuleEngineSupport.executeMatchedPairFacts(RuleEngineSupportTestEc.ec("KREWE", ACME_RULE_SET), "RS_ACME", [[key: "value"]])
        assertNotNull(result.error, "executeMatchedPairFacts must return an error for a foreign-tenant ruleSetId — no rule execution")
        assertTrue(result.diffResults == null || ((List) result.diffResults).isEmpty(),
                "executeMatchedPairFacts must not leak diff results for a foreign-tenant ruleSetId")
    }
}
