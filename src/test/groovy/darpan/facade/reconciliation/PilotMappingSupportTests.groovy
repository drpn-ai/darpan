package darpan.facade.reconciliation

import darpan.facade.common.PilotAccessSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

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
    void collectMemberIssuesRequiresSavedSchemaForAllMappings() {
        def ec = buildLookupEc([:])

        List<String> issues = PilotMappingSupport.collectMemberIssues(ec, [
                systemEnumId     : "OMS",
                idFieldExpression: "order_id"
        ])

        assertEquals(1, issues.size())
        assertEquals("OMS is missing a saved schema.", issues[0].toString())
    }

    @Test
    void collectMemberIssuesRejectsUnsavedSchemaReferences() {
        def ec = buildLookupEc([
                "orders.schema.json": [
                        jsonSchemaId      : "JS_ORDER",
                        schemaName        : "orders.schema.json",
                        companyUserGroupId: "KREWE",
                        schemaText        : '{"type":"object","properties":{"order_id":{"type":"string"}}}'
                ]
        ])

        List<String> issues = PilotMappingSupport.collectMemberIssues(ec, [
                systemEnumId     : "OMS",
                schemaFileName   : "missing.schema.json",
                idFieldExpression: "order_id"
        ])

        assertEquals(1, issues.size())
        assertEquals("OMS schema 'missing.schema.json' is not saved in Darpan.", issues[0].toString())
    }

    @Test
    void collectMemberIssuesRejectsSchemaOutsideActiveCompany() {
        def ec = buildLookupEc([
                "orders.schema.json": [
                        jsonSchemaId      : "JS_ORDER",
                        schemaName        : "orders.schema.json",
                        companyUserGroupId: "ACME",
                        schemaText        : '{"type":"object","properties":{"order_id":{"type":"string"}}}'
                ]
        ])

        List<String> issues = PilotMappingSupport.collectMemberIssues(ec, [
                systemEnumId     : "OMS",
                schemaFileName   : "orders.schema.json",
                idFieldExpression: "order_id"
        ])

        assertEquals(1, issues.size())
        assertEquals("OMS schema 'orders.schema.json' is not available in your active company.", issues[0].toString())
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

    private static Expando buildLookupEc(Map<String, Map<String, Object>> schemasByName) {
        return new Expando(
                entity: new LookupEntityFacadeStub(schemasByName: schemasByName),
                user: new UserStub(preferences: [(PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY): "KREWE"]),
                message: new Expando(addError: { String ignored -> }),
                l10n: new Expando()
        )
    }

    static class UserStub {
        String userId = "EX_USER"
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]

        Object getPreference(String preferenceKey) {
            return preferences[preferenceKey]
        }
    }

    static class EntityFacadeStub {
        FinderStub finder

        FinderStub find(String entityName) {
            return finder
        }
    }

    static class LookupEntityFacadeStub {
        Map<String, Map<String, Object>> schemasByName = [:]

        FinderStub find(String entityName) {
            switch (entityName) {
                case "darpan.reconciliation.JsonSchema":
                    return new SchemaFinderStub(byName: schemasByName)
                case "moqui.security.UserGroupAndMember":
                    return new FinderStub(listResult: [
                            [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                    ])
                case "moqui.security.UserGroupMember":
                    return new FinderStub()
                case "moqui.basic.Enumeration":
                    return new EnumerationFinderStub()
                default:
                    return new FinderStub()
            }
        }
    }

    static class FinderStub {
        Set<String> existingIds = [] as Set
        String currentId
        Map<String, Object> conditions = [:]
        List listResult = []

        FinderStub condition(String fieldName, Object value) {
            currentId = value?.toString()
            conditions[fieldName] = value
            return this
        }

        FinderStub useCache(boolean useCache) {
            return this
        }

        FinderStub conditionDate(String fromField, String thruField, Object moment) {
            return this
        }

        Object one() {
            return existingIds.contains(currentId) ? [reconciliationMappingId: currentId] : null
        }

        List list() {
            return listResult
        }
    }

    static class EnumerationFinderStub extends FinderStub {
        @Override
        Object one() {
            String enumId = conditions.enumId?.toString()
            if (!enumId) return null
            return [enumId: enumId, enumCode: enumId, description: enumId]
        }
    }

    static class SchemaFinderStub extends FinderStub {
        Map<String, Map<String, Object>> byName = [:]

        @Override
        FinderStub condition(String fieldName, Object value) {
            currentId = value?.toString()
            conditions.clear()
            conditions[fieldName] = value
            return this
        }

        @Override
        Object one() {
            if (conditions.containsKey("jsonSchemaId")) {
                return byName.values().find { it.jsonSchemaId == conditions.jsonSchemaId }
            }
            if (conditions.containsKey("schemaName")) {
                return byName[conditions.schemaName]
            }
            return null
        }
    }
}
