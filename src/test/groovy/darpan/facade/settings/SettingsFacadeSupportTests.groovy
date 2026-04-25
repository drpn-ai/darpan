package darpan.facade.settings

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertIterableEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class SettingsFacadeSupportTests {

    @Test
    void validateJsonObjectTextRejectsArraysAndBadJson() {
        assertNull(SettingsFacadeSupport.validateJsonObjectText('{"header":"value"}', "Headers JSON"))
        assertEquals("Headers JSON must be a JSON object.", SettingsFacadeSupport.validateJsonObjectText('["value"]', "Headers JSON"))
        assertTrue(SettingsFacadeSupport.validateJsonObjectText('{"broken"', "Headers JSON")?.startsWith("Headers JSON is invalid:"))
    }

    @Test
    void resolveHcReadDbConfigIdNormalizesAndGeneratesUniqueIds() {
        FinderStub finder = new FinderStub(existingIds: ["orders_db", "orders_db_2"] as Set)
        EntityFacadeStub entity = new EntityFacadeStub(finder: finder)
        def ec = new Expando(entity: entity)

        Map<String, Object> explicit = SettingsFacadeSupport.resolveHcReadDbConfigId(ec, "Orders DB!", null, null, null)
        Map<String, Object> generated = SettingsFacadeSupport.resolveHcReadDbConfigId(ec, null, "Orders DB", "db.example", "orders")

        assertEquals("orders_db", explicit.configId)
        assertEquals(null, explicit.error)
        assertEquals("orders_db_3", generated.configId)
        assertEquals(null, generated.error)
    }

    @Test
    void normalizeAdditionalParametersAndJdbcUrlPreserveExpectedShape() {
        String additional = SettingsFacadeSupport.normalizeAdditionalParameters("?useSSL=false&serverTimezone=UTC")
        String jdbcUrl = SettingsFacadeSupport.buildMysqlJdbcUrl("localhost", 3306, "inventory", additional)

        assertEquals("useSSL=false&serverTimezone=UTC", additional)
        assertEquals("jdbc:mysql://localhost:3306/inventory?useSSL=false&serverTimezone=UTC", jdbcUrl)
    }

    @Test
    void deduplicateEnumOptionsPrefersCanonicalSystemIds() {
        List<Map<String, Object>> options = [
                [enumId: "DarSysOms", enumCode: "OMS", description: "OMS", sequenceNum: 1, label: "OMS"],
                [enumId: "OMS", enumCode: "OMS", description: "OMS", sequenceNum: 1, label: "OMS"],
                [enumId: "DarSysShopify", enumCode: "SHOPIFY", description: "Shopify", sequenceNum: 2, label: "SHOPIFY"],
                [enumId: "SHOPIFY", enumCode: "SHOPIFY", description: "Shopify", sequenceNum: 2, label: "SHOPIFY"],
                [enumId: "NETSUITE", enumCode: "NETSUITE", description: "NetSuite", sequenceNum: 3, label: "NETSUITE"],
        ]

        List<Map<String, Object>> deduplicated = SettingsFacadeSupport.deduplicateEnumOptions("DarpanSystemSource", options)

        assertEquals(3, deduplicated.size())
        assertIterableEquals(["OMS", "SHOPIFY", "NETSUITE"], deduplicated.collect { it.enumId })
        assertIterableEquals(["OMS", "SHOPIFY", "NETSUITE"], deduplicated.collect { it.label })
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
            return existingIds.contains(currentId) ? [hcReadDbConfigId: currentId] : null
        }
    }
}
