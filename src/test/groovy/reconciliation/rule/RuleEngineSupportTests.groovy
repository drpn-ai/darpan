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
    void invalidateRuleSetCacheUsesTenantScopedEntries() {
        def ec = new Expando(entity: null, context: new Expando(tenantId: "Demo Tenant"))

        RuleEngineSupport.RULESET_CACHE.clear()
        RuleEngineSupport.RULESET_CACHE["DEMO_TENANT::RS_ONE"] = [value: 1]
        RuleEngineSupport.RULESET_CACHE["TENANT_OTHER::RS_ONE"] = [value: 2]

        assertTrue(RuleEngineSupport.invalidateRuleSetCache(ec, "RS_ONE"))
        assertEquals(1, RuleEngineSupport.RULESET_CACHE.size())
        assertTrue(RuleEngineSupport.RULESET_CACHE.containsKey("TENANT_OTHER::RS_ONE"))

        assertTrue(RuleEngineSupport.invalidateRuleSetCache(ec, null))
        assertTrue(RuleEngineSupport.RULESET_CACHE.isEmpty())
    }
}
