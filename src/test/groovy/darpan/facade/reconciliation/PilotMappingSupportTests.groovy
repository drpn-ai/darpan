package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

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
    void rejectsUnknownJsonSchemaProperty() {
        assertFalse(PilotMappingSupport.isResolvableJsonIdExpression("order_id", NESTED_SCHEMA))
        assertFalse(PilotMappingSupport.isResolvableJsonIdExpression("\$.data.orders.edges[*].node.order_id", NESTED_SCHEMA))
    }
}
