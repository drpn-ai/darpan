package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class RuleEngineSupportTests {

    @Test
    void validateProvidedIdRejectsInvalidCharacters() {
        Map<String, Object> valid = RuleEngineSupport.validateProvidedId("RS_VALID-1", "ruleSetId")
        Map<String, Object> invalid = RuleEngineSupport.validateProvidedId("RS BAD!", "ruleSetId")

        assertEquals("RS_VALID-1", valid.value)
        assertNull(valid.error)
        assertEquals(null, invalid.value)
        assertTrue(invalid.error?.contains("ruleSetId contains invalid characters"))
    }

    @Test
    void resolveRuleSetIdGeneratesUniquePrefixedIds() {
        FinderStub finder = new FinderStub(existingIds: ["RS_INVENTORY_COMPARISON", "RS_INVENTORY_COMPARISON_1"] as Set)
        EntityFacadeStub entity = new EntityFacadeStub(finder: finder)
        def ec = new Expando(entity: entity, context: new Expando(tenantId: "DEMO"))

        Map<String, Object> explicit = RuleEngineSupport.resolveRuleSetId(ec, "RS_CUSTOM", "ignored")
        Map<String, Object> generated = RuleEngineSupport.resolveRuleSetId(ec, null, "Inventory Comparison")

        assertEquals("RS_CUSTOM", explicit.ruleSetId)
        assertNull(explicit.error)
        assertEquals("RS_INVENTORY_COMPARISON_2", generated.ruleSetId)
        assertNull(generated.error)
    }

    @Test
    void invalidateRuleSetCacheUsesTenantScopedEntries() {
        def ec = new Expando(entity: null, context: new Expando(tenantId: "Demo Tenant"))

        RuleEngineSupport.RULESET_CACHE.clear()
        RuleEngineSupport.RULESET_CACHE[RuleEngineSupport.buildCacheKey(ec, "RS_ONE")] = [value: 1]
        RuleEngineSupport.RULESET_CACHE["TENANT_OTHER::RS_ONE"] = [value: 2]

        assertTrue(RuleEngineSupport.invalidateRuleSetCache(ec, "RS_ONE"))
        assertEquals(1, RuleEngineSupport.RULESET_CACHE.size())
        assertTrue(RuleEngineSupport.RULESET_CACHE.containsKey("TENANT_OTHER::RS_ONE"))

        assertTrue(RuleEngineSupport.invalidateRuleSetCache(ec, null))
        assertTrue(RuleEngineSupport.RULESET_CACHE.isEmpty())
    }

    static class EntityFacadeStub {
        FinderStub finder

        FinderStub find(String entityName) {
            return finder
        }
    }

    static class FinderStub {
        Set<String> existingIds = [] as Set
        String currentId

        FinderStub condition(String fieldName, Object value) {
            currentId = value?.toString()
            return this
        }

        FinderStub useCache(boolean useCache) {
            return this
        }

        Object one() {
            return existingIds.contains(currentId) ? [ruleSetId: currentId] : null
        }
    }
}
