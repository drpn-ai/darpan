package darpan.facade.reconciliation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavedRunsFacadeSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "saved-runs-facade-smoke")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
    }

    @Test
    void csvRunCanBeCreatedListedAndExecutedThroughTheSavedRunFacade() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "CSV Order Compare",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("CSV Order Compare", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)
        assertNotNull(createResult.savedRun.savedRunId)
        assertNotNull(createResult.savedRun.ruleSetId)
        assertNotNull(createResult.savedRun.compareScopeId)
        assertTrue((createResult.savedRun.compareScopeId as String).contains("_CSV_SCOPE"))
        assertEquals("CSV compare scope for CSV Order Compare", createResult.savedRun.compareScopeDescription)
        assertEquals("OMS", createResult.savedRun.defaultFile1SystemEnumId)
        assertEquals("SHOPIFY", createResult.savedRun.defaultFile2SystemEnumId)
        assertEquals(null, ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .one()
                ?.objectType)

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#SavedRuns")
                .parameters([
                        pageIndex: 0,
                        pageSize : 20,
                        query    : "",
                ])
                .disableAuthz()
                .call()

        List<Map<String, Object>> savedRuns = (List<Map<String, Object>>) (listResult.savedRuns ?: [])
        assertTrue(savedRuns.any { Map<String, Object> row -> row.savedRunId == createResult.savedRun.savedRunId })
        assertTrue(savedRuns.any { Map<String, Object> row -> row.savedRunId == "OrderIdSchemaMap" })

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\nA300\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\nA400\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertEquals("OMS", runResult.runResult.file1SystemEnumId)
        assertEquals("SHOPIFY", runResult.runResult.file2SystemEnumId)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.generatedOutput.savedRunId)
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.generatedOutput.ruleSetId)
        assertEquals("CSV compare scope for CSV Order Compare", runResult.runResult.generatedOutput.compareScopeDescription)
        assertEquals(2L, runResult.runResult.generatedOutput.totalDifferences)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile1Count)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile2Count)

        Map<String, Object> outputListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        List<Map<String, Object>> generatedOutputs = (List<Map<String, Object>>) (outputListResult.generatedOutputs ?: [])
        assertTrue(generatedOutputs.size() >= 1)
        Map<String, Object> matchingOutput = generatedOutputs.find { Map<String, Object> row ->
            row.fileName == runResult.runResult.generatedOutput.fileName
        }
        assertNotNull(matchingOutput)
        assertEquals(createResult.savedRun.savedRunId, matchingOutput.savedRunId)
        assertEquals("ruleset", matchingOutput.savedRunType)
    }

    @Test
    void csvSavedRunRejectsJsonSchemaDocumentsBeforeSparkProjection() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "CSV Schema Guard",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "orderId",
                        file2CompareColumn: "orderId",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        String schemaDocument = '''{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "orderId": { "type": "string" }
    }
  }
}'''

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "Gorjana-OMS-Order.json",
                        file1Text : schemaDocument,
                        file2Name : "Gorjana-Shopify-Orders.json",
                        file2Text : schemaDocument,
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertEquals(false, runResult.ok)
        assertTrue(((List) (runResult.errors ?: [])).size() > 0)
        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.contains("expects CSV data") })
        assertTrue(ec.message.errors.any { String message -> message.contains("JSON Schema document") })
        assertTrue(ec.message.errors.any { String message -> message.contains("source data file") })
    }

    @Test
    void csvRunKeepsCompareScopeIdsWithinEntityLengthAndUsesCsvSuffix() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "CSV Compare Scope Name That Definitely Exceeds The Stored Id Limit",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(compareScopeId)
        assertTrue(compareScopeId.size() <= ReconciliationSavedRunSupport.ENTITY_ID_MAX_LENGTH)
        assertTrue(compareScopeId.endsWith("_CSV_SCOPE"))
    }

    @Test
    void ruleSetRunCanBeCreatedWithInlineRulesAndExecutedThroughTheSavedRunFacade() {
        String skuMismatchRule = '''rule "ORDER_STATUS_MISMATCH"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(reconciliation.rule.RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("status"), ((Map) $m.get("file2")).get("status")))
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch(
        $m,
        kcontext.getRule().getName(),
        "status",
        ((Map) $m.get("file1")).get("status"),
        ((Map) $m.get("file2")).get("status"),
        "WARN",
        "Status mismatch"
    );
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Order Status Compare",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : skuMismatchRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("Order Status Compare", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)
        assertEquals("OMS", createResult.savedRun.defaultFile1SystemEnumId)
        assertEquals("SHOPIFY", createResult.savedRun.defaultFile2SystemEnumId)
        assertEquals(2, ((List) createResult.savedRun.systemOptions).size())
        assertEquals(null, ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .one()
                ?.objectType)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id,status\nA100,open\nA200,closed\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id,status\nA100,pending\nA200,closed\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals(1L, runResult.runResult.generatedOutput.totalDifferences)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile1Count ?: 0) as Long)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile2Count ?: 0) as Long)
    }

    @Test
    void ruleSetRunCanBeCreatedWithoutInitialRulesAndStillRunsBasicDiff() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Basic Diff Only",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("Basic Diff Only", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)

        List rules = ec.entity.find("darpan.rule.Rule")
                .condition("ruleSetId", createResult.savedRun.ruleSetId)
                .useCache(false)
                .list() ?: []
        assertEquals(0, rules.size())

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\nA300\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\nA400\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals(2L, runResult.runResult.generatedOutput.totalDifferences)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile1Count)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile2Count)
    }

    @Test
    void jsonRuleSetRunUsesSavedSchemasForSourceSelectionAndExecutesThroughTheSavedRunFacade() {
        String noopRule = '''rule "NOOP_JSON_RULE"
when
    $m : Map()
then
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "JSON Order Compare",
                        file1SystemEnumId       : "SHOPIFY",
                        file1FileTypeEnumId     : "DftJson",
                        file1SchemaFileName     : "test-shopify-orders.schema.json",
                        file1PrimaryIdExpression: '$.data.orders.edges[0].node.id|SHOPIFY_GID_TAIL',
                        file2SystemEnumId       : "OMS",
                        file2FileTypeEnumId     : "DftJson",
                        file2SchemaFileName     : "test-oms-orders.schema.json",
                        file2PrimaryIdExpression: '$.orders[0].order_id',
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : noopRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("JSON Order Compare", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)
        assertTrue((createResult.savedRun.compareScopeId as String).contains("_COMPARE_SCOPE"))
        assertFalse((createResult.savedRun.compareScopeId as String).contains("_CSV_SCOPE"))
        assertEquals("Compare scope for JSON Order Compare", createResult.savedRun.compareScopeDescription)
        assertEquals(null, ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .one()
                ?.objectType)

        List<Map<String, Object>> systemOptions = (List<Map<String, Object>>) (createResult.savedRun.systemOptions ?: [])
        assertEquals("test-shopify-orders.schema.json", systemOptions.find { it.fileSide == "FILE_1" }?.schemaFileName)
        assertEquals("test-oms-orders.schema.json", systemOptions.find { it.fileSide == "FILE_2" }?.schemaFileName)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.json",
                        file1Text : '{"data":{"orders":{"edges":[{"node":{"id":"gid://shopify/Order/1001","status":"open"}}]}}}',
                        file2Name : "orders-2.json",
                        file2Text : '{"orders":[{"order_id":"1001","status":"open"}]}',
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals("Compare scope for JSON Order Compare", runResult.runResult.generatedOutput.compareScopeDescription)
        assertEquals(0L, (runResult.runResult.generatedOutput.totalDifferences ?: 0) as Long)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile1Count ?: 0) as Long)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile2Count ?: 0) as Long)
    }

    @Test
    void ruleSetRunKeepsCompareScopeIdsWithinEntityLengthAndUsesNeutralSuffix() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "RuleSet Compare Scope Name That Definitely Exceeds The Stored Id Limit",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(compareScopeId)
        assertTrue(compareScopeId.size() <= ReconciliationSavedRunSupport.ENTITY_ID_MAX_LENGTH)
        assertTrue(compareScopeId.endsWith("_COMPARE_SCOPE"))
        assertFalse(compareScopeId.contains("_CSV_SCOPE"))
    }

    @Test
    void ruleSetRunSaveUpdatesRunSetupWithoutTouchingRules() {
        String noopRule = '''rule "NOOP_SAVE_RULE"
when
    $m : Map()
then
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "RuleSet Save Source",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : noopRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(savedRunId)
        assertEquals(1, ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).useCache(false).list().size())

        Map<String, Object> saveResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters([
                        savedRunId              : savedRunId,
                        runName                 : "RuleSet Save Revised",
                        description             : "Updated non-rule setup.",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_number",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "shopify_order_number",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(savedRunId, saveResult.savedRun.savedRunId)
        assertEquals("RuleSet Save Revised", saveResult.savedRun.runName)
        assertEquals("Updated non-rule setup.", saveResult.savedRun.description)
        assertEquals(1, ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).useCache(false).list().size())
        assertEquals(1, ((List) saveResult.savedRun.rules).size())

        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .useCache(false)
                .list() ?: []
        assertEquals("order_number", sources.find { it.fileSide == "FILE_1" }?.primaryIdExpression)
        assertEquals("shopify_order_number", sources.find { it.fileSide == "FILE_2" }?.primaryIdExpression)
    }

    @Test
    void ruleSetRunSavePersistsProvidedRulesAndReturnsEditableRuleMetadata() {
        String totalRule = '''rule "TOTAL_MISMATCH"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(!java.util.Objects.equals(((Map) $m.get("file1")).get("total"), ((Map) $m.get("file2")).get("order_total")))
then
end'''
        String expression = '''{"type":"FIELD_COMPARISON","file1FieldPath":"total","file2FieldPath":"order_total","operator":"=","preActions":[{"fieldSide":"file1","action":"STRING_TO_INT"},{"fieldSide":"file2","action":"STRING_TO_NUMBER"}]}'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "RuleSet Rule Save Source",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String

        Map<String, Object> saveResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters([
                        savedRunId              : savedRunId,
                        runName                 : "RuleSet Rule Save Source",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 1,
                                        ruleLogic  : totalRule,
                                        ruleType   : "FIELD_COMPARISON",
                                        expression : expression,
                                        enabled    : "Y",
                                        severity   : "WARN",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List savedRules = (List) saveResult.savedRun.rules
        assertEquals(1, savedRules.size())
        assertEquals("total", savedRules[0].file1FieldPath)
        assertEquals("order_total", savedRules[0].file2FieldPath)
        assertEquals("=", savedRules[0].operator)
        assertEquals([
                [fieldSide: "file1", action: "STRING_TO_INT"],
                [fieldSide: "file2", action: "STRING_TO_NUMBER"],
        ], savedRules[0].preActions)
        assertEquals("FIELD_COMPARISON", savedRules[0].ruleType)
        assertNotNull(savedRules[0].ruleId)

        List ruleRows = ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).useCache(false).list() ?: []
        assertEquals(1, ruleRows.size())
        assertEquals(expression, ruleRows[0].expression)
    }

    @Test
    void savedRunDeleteRemovesRuleSetChildrenAndGeneratedOutputs() {
        String noopRule = '''rule "NOOP_DELETE_RULE"
when
    $m : Map()
then
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Codex Cleanup Smoke",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : noopRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(savedRunId)
        assertNotNull(compareScopeId)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(savedRunId, runResult.runResult.generatedOutput.savedRunId)

        Map<String, Object> outputListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        assertTrue(((List) (outputListResult.generatedOutputs ?: [])).any { Map<String, Object> row ->
            row.fileName == runResult.runResult.generatedOutput.fileName
        })

        Map<String, Object> deleteResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.delete#SavedRun")
                .parameters([savedRunId: savedRunId])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        assertEquals(true, deleteResult.deleted)
        assertEquals(savedRunId, deleteResult.deletedSavedRunId)
        assertEquals("ruleset", deleteResult.deletedRunType)
        assertEquals(1, deleteResult.deletedRuleCount)
        assertEquals(1, deleteResult.deletedCompareScopeCount)
        assertEquals(2, deleteResult.deletedCompareSourceCount)
        assertTrue((deleteResult.deletedGeneratedOutputCount as Integer) >= 1)

        assertNull(ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", savedRunId).useCache(false).one())
        assertNull(ec.entity.find("darpan.rule.RuleSetCompareScope").condition("compareScopeId", compareScopeId).useCache(false).one())
        assertEquals(0, ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", compareScopeId).useCache(false).list().size())

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#SavedRuns")
                .parameters([
                        pageIndex: 0,
                        pageSize : 50,
                        query    : "",
                ])
                .disableAuthz()
                .call()

        List<Map<String, Object>> savedRuns = (List<Map<String, Object>>) (listResult.savedRuns ?: [])
        assertFalse(savedRuns.any { Map<String, Object> row -> row.savedRunId == savedRunId })

        Map<String, Object> postDeleteOutputListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        assertTrue(((List) (postDeleteOutputListResult.generatedOutputs ?: [])).isEmpty())
    }
}
