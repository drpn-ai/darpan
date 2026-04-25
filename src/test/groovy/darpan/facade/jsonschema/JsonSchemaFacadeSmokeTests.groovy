package darpan.facade.jsonschema

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

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
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
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
}
