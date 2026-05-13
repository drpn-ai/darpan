package jsonschema.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class JsonSchemaUtilTests {

    @Test
    void ensureJsonSchemaTableChecksSchemaEntityTable() {
        DatasourceFactoryStub datasourceFactory = new DatasourceFactoryStub()
        EntityFacadeStub entity = new EntityFacadeStub(datasourceFactory: datasourceFactory, groupName: "tenant")
        def ec = new Expando(entity: entity)

        assertTrue(JsonSchemaUtil.ensureJsonSchemaTable(ec))
        assertEquals("darpan.reconciliation.JsonSchema", datasourceFactory.checkedEntityName)
        assertEquals("tenant", entity.requestedGroupName)
    }

    @Test
    void resolveSystemLabelUsesEnumerationWhenAvailable() {
        FinderStub finder = new FinderStub(oneResult: [enumId: "SHOPIFY", enumCode: "Shopify", description: "Shopify Description"])
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.basic.Enumeration": finder])
        def ec = new Expando(entity: entity)

        String label = JsonSchemaUtil.resolveSystemLabel(ec, "SHOPIFY", null, true)

        assertEquals("Shopify", label)
        assertEquals("DarpanSystemSource", finder.conditions.enumTypeId)
        assertEquals("SHOPIFY", finder.conditions.enumId)
        assertTrue(finder.useCacheValue)
    }

    @Test
    void deleteSchemaRecordAddsErrorWhenDeleteFails() {
        MessageFacadeStub message = new MessageFacadeStub()
        SchemaValueStub schema = new SchemaValueStub(schemaName: "orders", failDelete: true)
        def ec = new Expando(message: message)

        boolean deleted = JsonSchemaUtil.deleteSchemaRecord(ec, schema)

        assertFalse(deleted)
        assertEquals(["Error deleting schema: boom"], message.errors)
        assertTrue(message.messages.isEmpty())
    }

    @Test
    void validateJsonTextAndResolveSchemaRecordCoverFacadeHelpers() {
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["darpan.reconciliation.JsonSchema": new SchemaResolverFinderStub(
                byId: ["JS_1": [jsonSchemaId: "JS_1", schemaName: "orders"]],
                byName: ["orders_v2": [jsonSchemaId: "JS_2", schemaName: "orders_v2"], "orders_legacy": [jsonSchemaId: "JS_3", schemaName: "orders_legacy"]]
        )])
        def ec = new Expando(entity: entity)

        assertNull(JsonSchemaUtil.validateJsonText('{"id":1}', "jsonText"))
        assertTrue(JsonSchemaUtil.validateJsonText('{"id"', "jsonText")?.startsWith("jsonText is invalid JSON:"))
        assertEquals("JS_1", JsonSchemaUtil.resolveSchemaRecord(ec, "JS_1", null, null, false)?.jsonSchemaId)
        assertEquals("JS_2", JsonSchemaUtil.resolveSchemaRecord(ec, null, "orders_v2", null, false)?.jsonSchemaId)
        assertEquals("JS_3", JsonSchemaUtil.resolveSchemaRecord(ec, null, null, "orders_legacy", false)?.jsonSchemaId)
    }

    @Test
    void cleanFileNameReturnsNullForUnsafeTokens() {
        assertNull(JsonSchemaUtil.cleanFileName(null))
        assertNull(JsonSchemaUtil.cleanFileName(""))
        assertNull(JsonSchemaUtil.cleanFileName("../etc/passwd"))
        assertNull(JsonSchemaUtil.cleanFileName("dir/schema.json"))
        assertNull(JsonSchemaUtil.cleanFileName("dir\\schema.json"))
        assertEquals("schema.json", JsonSchemaUtil.cleanFileName(" schema.json "))
    }

    @Test
    void readUploadedTextReadsUtf8AndRejectsEmptyUploads() {
        FileItemStub goodFile = new FileItemStub(name: "schema.json", size: 12L, text: '{"a":1}')
        FileItemStub emptyFile = new FileItemStub(name: "empty.json", size: 0L, text: '')

        Map<String, Object> uploaded = JsonSchemaUtil.readUploadedText(goodFile, "schema file")
        Map<String, Object> empty = JsonSchemaUtil.readUploadedText(emptyFile, "schema file")

        assertEquals("schema.json", uploaded.fileName)
        assertEquals('{"a":1}', uploaded.text)
        assertNull(uploaded.error)
        assertTrue(empty.error?.contains("Uploaded schema file is empty"))
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]
        DatasourceFactoryStub datasourceFactory = new DatasourceFactoryStub()
        String groupName = "default"
        String requestedGroupName

        String getEntityGroupName(String entityName) {
            return groupName
        }

        DatasourceFactoryStub getDatasourceFactory(String groupName) {
            requestedGroupName = groupName
            return datasourceFactory
        }

        FinderStub find(String entityName) {
            FinderStub finder = finders[entityName]
            if (finder == null) {
                finder = new FinderStub()
                finders[entityName] = finder
            }
            return finder
        }
    }

    static class DatasourceFactoryStub {
        String checkedEntityName

        void checkAndAddTable(String entityName) {
            checkedEntityName = entityName
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        boolean useCacheValue
        Object oneResult

        FinderStub condition(String fieldName, Object value) {
            conditions[fieldName] = value
            return this
        }

        FinderStub useCache(boolean useCache) {
            useCacheValue = useCache
            return this
        }

        Object one() {
            return oneResult
        }
    }

    static class SchemaResolverFinderStub extends FinderStub {
        Map<String, Object> byId = [:]
        Map<String, Object> byName = [:]

        @Override
        FinderStub condition(String fieldName, Object value) {
            conditions.clear()
            conditions[fieldName] = value
            return this
        }

        @Override
        Object one() {
            if (conditions.containsKey("jsonSchemaId")) return byId[conditions.jsonSchemaId]
            if (conditions.containsKey("schemaName")) return byName[conditions.schemaName]
            return null
        }
    }

    static class MessageFacadeStub {
        List<String> messages = []
        List<String> errors = []

        void addMessage(String message) {
            messages << message
        }

        void addError(String error) {
            errors << error
        }
    }

    static class SchemaValueStub {
        String schemaName
        boolean failDelete
        boolean deleted

        void delete() {
            if (failDelete) throw new IllegalStateException("boom")
            deleted = true
        }
    }

    static class FileItemStub {
        String name
        long size
        String text

        String getName() {
            return name
        }

        long getSize() {
            return size
        }

        String getString(String charset) {
            return text
        }
    }
}
