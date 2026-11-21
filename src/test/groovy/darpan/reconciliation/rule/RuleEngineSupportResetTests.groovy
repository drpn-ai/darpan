package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-OPS-026: resetForTests empties the ruleset cache. Pure: no Moqui. */
class RuleEngineSupportResetTests {
    @Test
    void resetForTestsEmptiesRuleSetCache() {
        RuleEngineSupport.RULESET_CACHE.put('reset-test', [signature: 'x'] as Map<String, Object>)
        assertEquals(1, RuleEngineSupport.RULESET_CACHE.size())
        RuleEngineSupport.resetForTests()
        assertEquals(0, RuleEngineSupport.RULESET_CACHE.size())
        RuleEngineSupport.resetForTests()
    }
}
