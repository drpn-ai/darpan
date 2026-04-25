package darpan.facade.jsonschema

import darpan.facade.common.PilotAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.commons.fileupload.disk.DiskFileItem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonSchemaFacadeSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "json-schema-facade-smoke")
        ReconciliationSmokeTestSupport.seedPilotCompanyScope(ec)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference(PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY, "KREWE")
    }

    @Test
    void saveJsonSchemaTextKeepsRequestedLabelVisibleWhenInternalNameIsDeduped() {
        String requestedName = "Gorjana Shopify Orders"
        String schemaText = '{"type":"object","properties":{"order_id":{"type":"string"}}}'

        Map<String, Object> firstResult = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.save#JsonSchemaText")
                .parameters([
                        schemaName: requestedName,
                        schemaText: schemaText,
                        overwrite : false,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        ec.message.clearErrors()

        Map<String, Object> secondResult = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.save#JsonSchemaText")
                .parameters([
                        schemaName: requestedName,
                        schemaText: schemaText,
                        overwrite : false,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())

        Map<String, Object> firstSaved = (Map<String, Object>) firstResult.savedSchema
        Map<String, Object> secondSaved = (Map<String, Object>) secondResult.savedSchema

        assertNotNull(firstSaved.jsonSchemaId)
        assertNotNull(secondSaved.jsonSchemaId)
        assertEquals(requestedName, firstSaved.description)
        assertEquals(requestedName, secondSaved.description)

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.list#JsonSchemas")
                .parameters([
                        pageIndex: 0,
                        pageSize : 20,
                        query    : requestedName,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        List<Map<String, Object>> schemas = (List<Map<String, Object>>) (listResult.schemas ?: [])
        assertEquals(2, schemas.findAll { Map<String, Object> row -> row.description == requestedName }.size())
    }

    @Test
    void createJsonSchemaFromJsonKeepsRequestedLabelVisibleWhenInternalNameIsDeduped() {
        String requestedName = "Gorjana sample orders"

        Map<String, Object> firstResult = ec.service.sync()
                .name("jsonschema.JsonSchemaServices.create#JsonSchemaFromJson")
                .parameters([
                        jsonFile  : jsonFile("orders-one.json", '{"orderId":"1001"}'),
                        schemaName: requestedName,
                        overwrite : false,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        ec.message.clearErrors()

        Map<String, Object> secondResult = ec.service.sync()
                .name("jsonschema.JsonSchemaServices.create#JsonSchemaFromJson")
                .parameters([
                        jsonFile  : jsonFile("orders-two.json", '{"orderId":"1002"}'),
                        schemaName: requestedName,
                        overwrite : false,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        assertNotNull(firstResult.jsonSchemaId)
        assertNotNull(secondResult.jsonSchemaId)

        def firstSchema = ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("jsonSchemaId", firstResult.jsonSchemaId)
                .disableAuthz()
                .useCache(false)
                .one()
        def secondSchema = ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("jsonSchemaId", secondResult.jsonSchemaId)
                .disableAuthz()
                .useCache(false)
                .one()

        assertEquals(requestedName, firstSchema.description)
        assertEquals(requestedName, secondSchema.description)
    }

    @Test
    void inferJsonSchemaFromTextAcceptsLiteralComparisonCharactersInUploadedSamples() {
        Map<String, Object> result = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.infer#JsonSchemaFromText")
                .parameters([jsonText: '{"orderId":"1001","comparison":"<100 >50","less<than>Field":"kept"}'])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors.toString())
        assertTrue((Boolean) result.ok)
        assertNotNull(result.jsonSchemaString)

        List<Map<String, Object>> fields = (List<Map<String, Object>>) (result.fieldList ?: [])
        assertTrue(fields.any { Map<String, Object> row -> row.fieldName == "comparison" })
        assertTrue(fields.any { Map<String, Object> row -> row.fieldName == "less<than>Field" })
    }

    private static DiskFileItem jsonFile(String fileName, String text) {
        DiskFileItem item = new DiskFileItem(
                "jsonFile",
                "application/json",
                false,
                fileName,
                Math.max(1024, text.length()),
                new File(System.getProperty("java.io.tmpdir"))
        )
        item.outputStream.withCloseable { it.write(text.getBytes("UTF-8")) }
        return item
    }
}
