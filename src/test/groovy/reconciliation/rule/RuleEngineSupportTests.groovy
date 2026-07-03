package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class RuleEngineSupportTests {

    @Test
    void sanitizeIdTokenNormalizesGeneratedIds() {
        assertEquals("RS_123_BAD_ID", RuleEngineSupport.sanitizeIdToken("123 bad id!", "RS"))
        assertEquals("RUN", RuleEngineSupport.sanitizeIdToken("", "RUN"))
    }

    @Test
    void invalidateRuleSetCacheRemovesByRuleSetIdAndClearsAll() {
        // Audit 2026-06-11 #27: ruleSetId is a globally unique PK, so the cache is keyed on it
        // directly (no illusory ec.context.tenantId scoping). Targeted invalidation removes only
        // the named ruleset; a null ruleSetId clears the whole cache.
        def ec = new Expando(entity: null, context: new Expando())

        RuleEngineSupport.RULESET_CACHE.clear()
        RuleEngineSupport.RULESET_CACHE["RS_ONE"] = [value: 1]
        RuleEngineSupport.RULESET_CACHE["RS_TWO"] = [value: 2]

        assertTrue(RuleEngineSupport.invalidateRuleSetCache(ec, "RS_ONE"))
        assertEquals(1, RuleEngineSupport.RULESET_CACHE.size())
        assertTrue(RuleEngineSupport.RULESET_CACHE.containsKey("RS_TWO"))

        assertTrue(RuleEngineSupport.invalidateRuleSetCache(ec, null))
        assertTrue(RuleEngineSupport.RULESET_CACHE.isEmpty())
    }
}
