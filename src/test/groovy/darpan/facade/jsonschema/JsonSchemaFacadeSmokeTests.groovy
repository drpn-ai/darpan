package darpan.facade.jsonschema

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.context.ArtifactExecutionInfo

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonSchemaFacadeSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "json-schema-facade-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.seedSharedReconciliationEnums(ec)
        seedSchema("SchemaLibraryVisibleKrewe", "krewe-visible.schema.json", "Krewe visible schema", "KREWE")
        seedSchema("SchemaLibraryHiddenLegacy", "legacy-hidden.schema.json", "Legacy hidden schema", null)
        seedSchema("SchemaLibraryHiddenGorjana", "gorjana-hidden.schema.json", "Gorjana hidden schema", "GORJANA")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference("darpan.auth.activeTenantUserGroupId", "KREWE")
    }

    @Test
    void listJsonSchemasOnlyReturnsSchemasAccessibleToTheActiveTenant() {
        Map<String, Object> result = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.list#JsonSchemas")
                .parameters([
                        pageIndex: 0,
                        pageSize : 20,
                        query    : "",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        List<Map<String, Object>> schemas = (List<Map<String, Object>>) (result.schemas ?: [])
        assertTrue(schemas.every { Map<String, Object> row -> row.companyUserGroupId == "KREWE" })
        assertTrue(schemas.any { Map<String, Object> row -> row.jsonSchemaId == "SchemaLibraryVisibleKrewe" })
        assertFalse(schemas.any { Map<String, Object> row -> row.jsonSchemaId == "SchemaLibraryHiddenLegacy" })
        assertFalse(schemas.any { Map<String, Object> row -> row.jsonSchemaId == "SchemaLibraryHiddenGorjana" })
    }

    @Test
    void getJsonSchemaStillRejectsLegacyUnscopedSchemas() {
        Map<String, Object> result = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.get#JsonSchema")
                .parameters([jsonSchemaId: "SchemaLibraryHiddenLegacy"])
                .disableAuthz()
                .call()

        assertTrue(ec.message.hasError())
        assertNull(result.schemaData)
        assertEquals(["Schema is not available in your active tenant."], ec.message.errors)
    }

    @Test
    void saveJsonSchemaTextKeepsRequestedLabelVisibleWhenInternalNameIsDeduped() {
        String requestedName = "Gorjana Shopify Orders"
        String schemaText = '{"type":"object","properties":{"order_id":{"type":"string"}}}'

        Map<String, Object> firstResult = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.save#JsonSchemaText")
                .parameters([
                        schemaName  : requestedName,
                        systemEnumId: "SHOPIFY",
                        schemaText  : schemaText,
                        overwrite   : false,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        ec.message.clearErrors()

        Map<String, Object> secondResult = ec.service.sync()
                .name("facade.JsonSchemaFacadeServices.save#JsonSchemaText")
                .parameters([
                        schemaName  : requestedName,
                        systemEnumId: "SHOPIFY",
                        schemaText  : schemaText,
                        overwrite   : false,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())

        Map<String, Object> firstSaved = (Map<String, Object>) firstResult.savedSchema
        Map<String, Object> secondSaved = (Map<String, Object>) secondResult.savedSchema

        assertNotNull(firstSaved.jsonSchemaId)
        assertNotNull(secondSaved.jsonSchemaId)
        assertNotEquals(firstSaved.jsonSchemaId, secondSaved.jsonSchemaId)
        assertEquals(requestedName, firstSaved.description)
        assertEquals(requestedName, secondSaved.description)
        assertNotNull(firstSaved.schemaName)
        assertNotNull(secondSaved.schemaName)

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

    private void seedSchema(String jsonSchemaId, String schemaName, String description, String companyUserGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedJsonSchema",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ensureCompanyGroup(companyUserGroupId)

            def existing = ec.entity.find("darpan.reconciliation.JsonSchema")
                    .condition("jsonSchemaId", jsonSchemaId)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (existing != null) return

            def value = ec.entity.makeValue("darpan.reconciliation.JsonSchema")
            value.setAll([
                    jsonSchemaId       : jsonSchemaId,
                    schemaName         : schemaName,
                    description        : description,
                    companyUserGroupId : companyUserGroupId,
                    statusId           : "Active",
                    createdDate        : Timestamp.valueOf("2026-04-22 00:00:00"),
                    schemaText         : '{"type":"object","properties":{"order_id":{"type":"string"}}}',
            ])
            value.create()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void ensureCompanyGroup(String companyUserGroupId) {
        if (!companyUserGroupId) return

        def existingGroup = ec.entity.find("moqui.security.UserGroup")
                .condition("userGroupId", companyUserGroupId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (existingGroup != null) return

        def groupValue = ec.entity.makeValue("moqui.security.UserGroup")
        groupValue.setAll([
                userGroupId    : companyUserGroupId,
                description    : companyUserGroupId,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        groupValue.create()
    }
}
