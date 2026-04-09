package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class PilotMappingSupportTests {

    private static final String NESTED_SCHEMA = """
        {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "data": {
                "type": "object",
                "properties": {
                  "orders": {
                    "type": "object",
                    "properties": {
                      "edges": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "node": {
                              "type": "object",
                              "properties": {
                                "legacyResourceId": { "type": "string" },
                                "id": { "type": "string" }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
    """

    @Test
    void resolvesPlainAndJsonPathExpressionsAgainstNestedSchema() {
        assertTrue(PilotMappingSupport.isResolvableJsonIdExpression("legacyResourceId", NESTED_SCHEMA))
        assertTrue(PilotMappingSupport.isResolvableJsonIdExpression("\$.data.orders.edges[*].node.legacyResourceId", NESTED_SCHEMA))
    }

    @Test
    void rejectsJsonPathThatOnlyMatchesLeafNameElsewhere() {
        assertFalse(PilotMappingSupport.isResolvableJsonIdExpression("\$.foo.legacyResourceId", NESTED_SCHEMA))
    }

    @Test
    void rejectsUnknownJsonSchemaProperty() {
        assertFalse(PilotMappingSupport.isResolvableJsonIdExpression("order_id", NESTED_SCHEMA))
        assertFalse(PilotMappingSupport.isResolvableJsonIdExpression("\$.data.orders.edges[*].node.order_id", NESTED_SCHEMA))
    }

    @Test
    void resolvesWizardDisplayPathsAgainstNestedSchema() {
        assertTrue(PilotMappingSupport.isResolvableJsonIdExpression("data.orders.edges.[0].node.legacyResourceId", NESTED_SCHEMA))
        assertTrue(PilotMappingSupport.isResolvableJsonIdExpression("[0].data.orders.edges.[0].node.id", NESTED_SCHEMA))
    }

    @Test
    void generatePilotMappingIdAddsTimestampSuffixWhenBaseIdExists() {
        FinderStub finder = new FinderStub(existingIds: ["OrdersVsInventory"] as Set)
        EntityFacadeStub entity = new EntityFacadeStub(finder: finder)
        def ec = new Expando(
                entity: entity,
                l10n: new Expando(format: { Object ts, String pattern -> "260408123456" }),
                user: new Expando(nowTimestamp: new java.sql.Timestamp(0L))
        )

        String mappingId = PilotMappingSupport.generatePilotMappingId(ec, "Orders Vs Inventory")

        assertEquals("OrdersVsInventory-260408123456", mappingId)
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
            return existingIds.contains(currentId) ? [reconciliationMappingId: currentId] : null
        }
    }
}
