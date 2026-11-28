package darpan.jsonschema.service

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * S99-213. validate#JsonLocationAgainstSchema, which the JSON file reconciliation runs (ReconciliationServices), had no
 * test. A file against the active tenant's saved schema: valid, invalid, missing; an unknown schema; another tenant's.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonLocationValidationSmokeTests {
    private static final String SCHEMA = "s99-213-order.schema.json"
    private static final String FOREIGN_SCHEMA = "s99-213-foreign.schema.json"
    private ExecutionContext ec
    private Path dir

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "json-location-validation")
        dir = Files.createTempDirectory("s99-213")
    }

    @AfterAll
    void cleanup() {
        dir.toFile().deleteDir()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void tenant() {
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        String schemaText = '{"type":"object","required":["orderId"],"properties":{"orderId":{"type":"string"}}}'
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("darpan.reconciliation.JsonSchema").setAll([jsonSchemaId: "S99_213_OWN", schemaName: SCHEMA,
                    schemaText: schemaText, companyUserGroupId: "KREWE"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "S99_213_OTHER", description: "Other tenant"]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.JsonSchema").setAll([jsonSchemaId: "S99_213_FOREIGN", schemaName: FOREIGN_SCHEMA,
                    schemaText: schemaText, companyUserGroupId: "S99_213_OTHER"]).createOrUpdate()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        ec.message.clearAll()
    }

    @Test
    void aFileThatMatchesTheSchemaIsValid() {
        Map out = validate(file("good.json", '{"orderId":"SO-1"}'), SCHEMA)
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(true, out.valid)
        assertEquals(0, out.errorCount)
        assertEquals([], out.errorMessages)
    }

    @Test
    void aFileThatBreaksTheSchemaIsInvalidAndSaysWhy() {
        Map out = validate(file("bad.json", '{"orderId":5}'), SCHEMA)
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(false, out.valid)
        assertTrue((out.errorCount as int) > 0, out.toString())
        assertEquals(out.errorCount as int, (out.errorMessages as List).size(), "one message per error")
    }

    @Test
    void aMissingFileAnUnknownSchemaAndAnotherTenantsSchemaAreRefused() {
        assertTrue(refusal(dir.resolve("absent.json").toUri().toString(), SCHEMA).contains("JSON file not found"))
        String good = file("good2.json", '{"orderId":"SO-2"}')
        assertTrue(refusal(good, "s99-213-no-such.schema.json").contains("Schema not found"))
        assertTrue(refusal(good, FOREIGN_SCHEMA).contains("Schema not found"), "another tenant's schema reads as missing")
    }

    private String file(String name, String text) {
        Path f = dir.resolve(name)
        f.toFile().text = text
        return f.toUri().toString()
    }

    private Map validate(String location, String schemaName) {
        return ec.service.sync().name("jsonschema.JsonSchemaServices.validate#JsonLocationAgainstSchema")
                .parameters([jsonLocation: location, schemaFileName: schemaName]).call()
    }

    /** The refusal's words, whether the service raised them or put them on the message list. */
    private String refusal(String location, String schemaName) {
        ec.message.clearAll()
        String thrown = ""
        try {
            validate(location, schemaName)
        } catch (Exception e) {
            thrown = e.message ?: ""
        }
        String words = thrown + " " + ec.message.errorsString
        ec.message.clearAll()
        return words
    }
}
