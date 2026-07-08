package darpan.reconciliation.core

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleSetCompareSourceCompositeKeyTests {
    private ExecutionContext ec
    private SparkSession spark

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "ruleset-composite-key")
        // One session for the whole class: per-test create/stop raced the next test's getOrCreate
        // against the previous test's asynchronous stop and intermittently handed out a dead session.
        spark = SparkSession.builder().appName("RuleSetCompareSourceCompositeKeyTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() {
        if (spark != null) spark.stop()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
    }

    @Test
    void keyFieldRowsAreReadableInSequenceOrderThroughTheSourceRelationship() {
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([
                ruleSetId: "DARPAN_TEST_CKEY_RS", ruleSetName: "Composite key test", version: "1.0"
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareScope").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", ruleSetId: "DARPAN_TEST_CKEY_RS", objectType: "RETURN_ITEM"
        ]).create()
        // RuleSetCompareSource.systemEnumId and fileTypeEnumId carry real FKs (RSCSR_SYS_ENUM,
        // RSCSR_FTYPE_ENUM) to moqui.basic.Enumeration; this test's own Enumeration.enumTypeId is
        // nullable, so minimal enum rows satisfy the FKs without needing EnumerationType rows too.
        // createOrUpdate (not create): JUnit does not guarantee method execution order within this
        // class, and the new composite-key facade test below also seeds/enriches a "DftJson" row —
        // a hard create() here would throw a PK violation if that test's @Test method runs first.
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([
                enumId: "DARPAN_SYS_SHOPIFY"
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([
                enumId: "DftJson"
        ]).createOrUpdate()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSource").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1", systemEnumId: "DARPAN_SYS_SHOPIFY",
                fileTypeEnumId: "DftJson"
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSourceKeyField").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1", sequenceNum: 2, fieldExpression: "product_id"
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSourceKeyField").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1", sequenceNum: 1, fieldExpression: "return_id"
        ]).create()

        def source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition([compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1"]).one()
        List keyFields = source.findRelated("keyFields", null, ["sequenceNum"], false, false)

        assertEquals(2, keyFields.size())
        assertEquals("return_id", keyFields[0].fieldExpression)
        assertEquals("product_id", keyFields[1].fieldExpression)
        assertFalse(ec.message.hasError())
    }

    @Test
    void buildCompareSourceIdSpecsReturnsOrderedSpecsForCompositeKeyFieldsAndFallsBackToLegacyExpression() {
        Map<String, Object> compositeConfig = [
                primaryIdExpression: null,
                idValueNormalizer  : null,
                recordRootExpression: '$.returns[*]',
                keyFields: [
                        [sequenceNum: 2, fieldExpression: "product_id"],
                        [sequenceNum: 1, fieldExpression: "return_id"],
                ]
        ]
        List<Map<String, Object>> compositeSpecs = RuleSetCompareScopeAdapter.buildCompareSourceIdSpecsForTest(
                "Composite scope", "FILE_1", "JSON", compositeConfig, [])
        assertEquals(2, compositeSpecs.size())
        assertEquals('$.returns[*].return_id', compositeSpecs[0].idExpr)
        assertEquals('$.returns[*].product_id', compositeSpecs[1].idExpr)

        Map<String, Object> legacyConfig = [
                primaryIdExpression: '$.returns[*].return_id',
                idValueNormalizer  : null,
                recordRootExpression: null,
                keyFields: []
        ]
        List<Map<String, Object>> legacySpecs = RuleSetCompareScopeAdapter.buildCompareSourceIdSpecsForTest(
                "Legacy scope", "FILE_1", "JSON", legacyConfig, [])
        assertEquals(1, legacySpecs.size())
        assertEquals('$.returns[*].return_id', legacySpecs[0].idExpr)
    }

    @Test
    void compositeKeyProducesDistinctComposedIds() {
        List<Map<String, Object>> idSpecs = [
                [idExpr: '$.returns[*].return_id', idNormalizer: null],
                [idExpr: '$.returns[*].product_id', idNormalizer: null],
        ]
        Map ingested = ReconciliationServices.ingestFile(
                ec, spark, "component://darpan/data/test/test-return-items-1.json", "JSON",
                idSpecs, true, "Return items 1", [], null)
        List<String> compareIds = ((Dataset) ingested.idDf).collectAsList()
                .collect { it.getAs("compare_id").toString() }
                .sort()

        assertEquals(["R1\u001FP1", "R1\u001FP2", "R2\u001FP1"], compareIds)
    }

    // Spark's concat_ws silently skips null columns; without the explicit guard a row missing one
    // composite field would compose a shorter, collidable compare_id instead of failing loudly.
    @Test
    void compositeKeyRejectsRowsWithNullOrBlankKeyFields() {
        List<Map<String, Object>> idSpecs = [
                [idExpr: '$.returns[*].return_id', idNormalizer: null],
                [idExpr: '$.returns[*].product_id', idNormalizer: null],
        ]
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException) {
            ReconciliationServices.ingestFile(
                    ec, spark, "component://darpan/data/test/test-return-items-null-field.json", "JSON",
                    idSpecs, true, "Return items with null field", [], null)
        }
        assertTrue(thrown.message.contains("must all be present"),
                "expected the null-field guard message, got: ${thrown.message}")
    }

    @Test
    void legacySingleFieldIdSpecStillWorksWhenPassedAsABareMapNotAList() {
        Map<String, Object> singleIdSpec = [idExpr: '$.returns[*].return_id', idNormalizer: null]
        Map ingested = ReconciliationServices.ingestFile(
                ec, spark, "component://darpan/data/test/test-return-items-1.json", "JSON",
                singleIdSpec, true, "Return items 1", [], null)
        List<String> compareIds = ((Dataset) ingested.idDf).collectAsList()
                .collect { it.getAs("compare_id").toString() }
                .sort()

        assertEquals(["R1", "R2"], compareIds)
    }

    // Regression: buildCsvFieldColumns must not nest applyIdNormalizer(expr(...), ...) in one Groovy
    // expression — dynamic dispatch resolved the wrong overload and broke single-field CSV ingestion.
    @Test
    void csvSingleFieldIdSpecStillWorksWhenPassedAsABareMapNotAList() {
        Map<String, Object> singleIdSpec = [idExpr: 'return_id', idNormalizer: null]
        Map ingested = ReconciliationServices.ingestFile(
                ec, spark, "component://darpan/data/test/test-return-items-1.csv", "CSV",
                singleIdSpec, true, "Return items 1", [], null)
        List<String> compareIds = ((Dataset) ingested.idDf).collectAsList()
                .collect { it.getAs("compare_id").toString() }
                .sort()

        assertEquals(["R1", "R2"], compareIds)
    }

    // Task 5: create#RuleSetRun must accept plural file1PrimaryIdExpressions/file2PrimaryIdExpressions
    // arrays, leave RuleSetCompareSource.primaryIdExpression null for composite sides, and persist one
    // RuleSetCompareSourceKeyField row per composite field in sequence order.
    //
    // Uses real system enum ids (SHOPIFY/OMS) and the DftJson file type, matching how the facade's
    // canonicalSystemEnumId and JSON-schema resolvability checks actually work. The JSON schema
    // resolvability check (isResolvableJsonIdExpression) requires a saved JsonSchema whose systemEnumId
    // equals the passed file*SystemEnumId; since file1 and file2 must use different systems, this needs
    // two distinct schemas (one per system), each containing return_id/product_id fields.
    @Test
    void createRuleSetRunWithTwoPrimaryIdExpressionsCreatesKeyFieldRowsAndLeavesLegacyExpressionNull() {
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
        // Test 1 in this class creates a bare "DftJson" Enumeration row (no enumCode) directly via
        // entity API; JUnit does not guarantee method execution order, so force-correct enumCode/
        // enumTypeId here via createOrUpdate (idempotent/self-healing) rather than relying on
        // seedSchemaBackedCsvMappingFixtures' find-or-skip upsert to have won the race.
        ec.entity.makeValue("moqui.basic.EnumerationType").setAll([
                enumTypeId : "DarpanFileType", description: "File Types for Reconciliation"
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([
                enumId: "DftJson", enumTypeId: "DarpanFileType", enumCode: "JSON", description: "JSON", sequenceNum: 2
        ]).createOrUpdate()

        String companyUserGroupId = "KREWE"
        def seedTimestamp = ec.user.nowTimestamp
        String returnItemsSchemaText = '{"type":"object","properties":{"returns":{"type":"array",' +
                '"items":{"type":"object","properties":{"return_id":{"type":"string"},"product_id":{"type":"string"}}}}}}'
        ec.entity.makeValue("darpan.reconciliation.JsonSchema").setAll([
                jsonSchemaId      : "TestReturnItemsSchemaShopify",
                schemaName        : "test-return-items-schema-shopify",
                description       : "Composite-key facade test Shopify return items schema",
                systemEnumId      : "SHOPIFY",
                companyUserGroupId: companyUserGroupId,
                createdDate       : seedTimestamp,
                schemaText        : returnItemsSchemaText
        ]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.JsonSchema").setAll([
                jsonSchemaId      : "TestReturnItemsSchemaOms",
                schemaName        : "test-return-items-schema-oms",
                description       : "Composite-key facade test OMS return items schema",
                systemEnumId      : "OMS",
                companyUserGroupId: companyUserGroupId,
                createdDate       : seedTimestamp,
                schemaText        : returnItemsSchemaText
        ]).createOrUpdate()

        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                  : "Composite key facade test",
                        file1SystemEnumId        : "SHOPIFY",
                        file1FileTypeEnumId      : "DftJson",
                        file1SchemaFileName      : "test-return-items-schema-shopify",
                        file1PrimaryIdExpressions: ["return_id", "product_id"],
                        file2SystemEnumId        : "OMS",
                        file2FileTypeEnumId      : "DftJson",
                        file2SchemaFileName      : "test-return-items-schema-oms",
                        file2PrimaryIdExpressions: ["return_id", "product_id"],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertTrue((Boolean) result.ok)
        String compareScopeId = (String) result.savedRun.compareScopeId

        def file1Source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition([compareScopeId: compareScopeId, fileSide: "FILE_1"]).useCache(false).one()
        assertEquals(null, file1Source.primaryIdExpression)
        List file1KeyFields = file1Source.findRelated("keyFields", null, ["sequenceNum"], false, false)
        assertEquals(2, file1KeyFields.size())
        assertEquals("return_id", file1KeyFields[0].fieldExpression)
        assertEquals("product_id", file1KeyFields[1].fieldExpression)

        def file2Source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition([compareScopeId: compareScopeId, fileSide: "FILE_2"]).useCache(false).one()
        assertEquals(null, file2Source.primaryIdExpression)
        List file2KeyFields = file2Source.findRelated("keyFields", null, ["sequenceNum"], false, false)
        assertEquals(2, file2KeyFields.size())
        assertEquals("return_id", file2KeyFields[0].fieldExpression)
        assertEquals("product_id", file2KeyFields[1].fieldExpression)
    }

    // A single-entry plural array with no singular fallback is NOT a composite key: per the facade
    // contract it must behave like the legacy singular field — write primaryIdExpression and create
    // NO key-field rows. Guards the allow-remote service against a direct/API caller (the UI collapses
    // one field to the singular param, but the service must be safe on its own) persisting a source
    // with neither a primaryIdExpression nor any key-field rows.
    @Test
    void createRuleSetRunWithSingleEntryPluralArrayWritesPrimaryIdExpressionAndNoKeyFieldRows() {
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
        ec.entity.makeValue("moqui.basic.EnumerationType").setAll([
                enumTypeId : "DarpanFileType", description: "File Types for Reconciliation"
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([
                enumId: "DftJson", enumTypeId: "DarpanFileType", enumCode: "JSON", description: "JSON", sequenceNum: 2
        ]).createOrUpdate()

        String companyUserGroupId = "KREWE"
        def seedTimestamp = ec.user.nowTimestamp
        String returnItemsSchemaText = '{"type":"object","properties":{"returns":{"type":"array",' +
                '"items":{"type":"object","properties":{"return_id":{"type":"string"},"product_id":{"type":"string"}}}}}}'
        ec.entity.makeValue("darpan.reconciliation.JsonSchema").setAll([
                jsonSchemaId      : "TestReturnItemsSchemaShopify",
                schemaName        : "test-return-items-schema-shopify",
                description       : "Composite-key facade test Shopify return items schema",
                systemEnumId      : "SHOPIFY",
                companyUserGroupId: companyUserGroupId,
                createdDate       : seedTimestamp,
                schemaText        : returnItemsSchemaText
        ]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.JsonSchema").setAll([
                jsonSchemaId      : "TestReturnItemsSchemaOms",
                schemaName        : "test-return-items-schema-oms",
                description       : "Composite-key facade test OMS return items schema",
                systemEnumId      : "OMS",
                companyUserGroupId: companyUserGroupId,
                createdDate       : seedTimestamp,
                schemaText        : returnItemsSchemaText
        ]).createOrUpdate()

        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                  : "Single-entry plural facade test",
                        file1SystemEnumId        : "SHOPIFY",
                        file1FileTypeEnumId      : "DftJson",
                        file1SchemaFileName      : "test-return-items-schema-shopify",
                        file1PrimaryIdExpressions: ["return_id"],
                        file2SystemEnumId        : "OMS",
                        file2FileTypeEnumId      : "DftJson",
                        file2SchemaFileName      : "test-return-items-schema-oms",
                        file2PrimaryIdExpressions: ["return_id"],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertTrue((Boolean) result.ok)
        String compareScopeId = (String) result.savedRun.compareScopeId

        def file1Source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition([compareScopeId: compareScopeId, fileSide: "FILE_1"]).useCache(false).one()
        assertEquals("return_id", file1Source.primaryIdExpression)
        assertEquals(0, file1Source.findRelated("keyFields", null, ["sequenceNum"], false, false).size())

        def file2Source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition([compareScopeId: compareScopeId, fileSide: "FILE_2"]).useCache(false).one()
        assertEquals("return_id", file2Source.primaryIdExpression)
        assertEquals(0, file2Source.findRelated("keyFields", null, ["sequenceNum"], false, false).size())
    }
}
