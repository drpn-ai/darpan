package darpan.facade.reconciliation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-064 — a run side's own choices, set when the run is created: repeated-key handling, and the extract options its
 * connector declares (gorjana R16 reads only cancelled OMS returns). The manual run hands those options to the extract
 * without letting them displace the config id or the window.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunSideOptionsSmokeTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "run-side-options")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/MappingSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    private Map create(Map overrides) {
        Map out = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                runName: "Side options ${UUID.randomUUID()}".toString(),
                file1SystemEnumId: "OMS_RETURNS_GQL", file1FileTypeEnumId: "DftCsv", file1PrimaryIdExpression: "returnId",
                file2SystemEnumId: "SHOPIFY", file2FileTypeEnumId: "DftCsv", file2PrimaryIdExpression: "returnId",
                rules: []] + overrides).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private def source(Map created, String side) {
        String ruleSetId = created.savedRun.savedRunId as String
        def scope = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", ruleSetId).disableAuthz().useCache(false).list().find()
        return ec.entity.find("darpan.rule.RuleSetCompareSource").condition([compareScopeId: scope.compareScopeId, fileSide: side])
                .disableAuthz().useCache(false).one()
    }

    @Test
    void storesEachSidesChoices() {
        Map created = create([file1ExtractOptions: [onlyCancelledReturns: true, includeReturnsWithoutShopifyRef: false],
                              file2DuplicateKeyHandling: "finding"])
        assertFalse(created.hadError as boolean, created.errorText as String)
        def file1 = source(created, "FILE_1"), file2 = source(created, "FILE_2")
        assertEquals('{"onlyCancelledReturns":true}', file1.extractParametersJson, "only what is on is stored")
        assertNull(file1.duplicateKeyHandling)
        assertEquals("FINDING", file2.duplicateKeyHandling)
        assertNull(file2.extractParametersJson)

        // A run's settings read them back from list#SavedRuns.
        Map listed = ec.service.sync().name("facade.ReconciliationFacadeServices.list#SavedRuns").parameters([pageIndex: 0, pageSize: 200]).call()
        Map run = ((List<Map>) listed.savedRuns).find { it.savedRunId == created.savedRun.savedRunId }
        Map side1 = ((List<Map>) run.systemOptions).find { it.fileSide == "FILE_1" }, side2 = ((List<Map>) run.systemOptions).find { it.fileSide == "FILE_2" }
        assertEquals('{"onlyCancelledReturns":true}', side1.extractParametersJson)
        assertEquals("FINDING", side2.duplicateKeyHandling)
    }

    @Test
    void refusesAnOptionTheSidesConnectorDoesNotTake() {
        Map wrongSide = create([file2ExtractOptions: [onlyCancelledReturns: true]])
        assertTrue(wrongSide.hadError as boolean)
        assertTrue((wrongSide.errorText as String).contains("File 2 has no option 'onlyCancelledReturns'"), wrongSide.errorText as String)
        Map wrongValue = create([file1DuplicateKeyHandling: "COLLAPSE"])
        assertTrue(wrongValue.hadError as boolean)
        assertTrue((wrongValue.errorText as String).contains("repeated-key handling"), wrongValue.errorText as String)
    }

    /** The run builder learns a side's options from list#AutomationSourceOptions, whose rows project the connector. */
    @Test
    void theSourceOptionRowsCarryWhatTheConnectorDeclares() {
        List<Map<String, Object>> connectors = AutomationFacadeSupport.connectorRowsForConfigEntity(ec, "darpan.hotwax.HotWaxOmsRestSourceConfig")
        Map<String, Object> returns = connectors.find { it.systemEnumId == "OMS_RETURNS_GQL" }
        assertEquals(["includeReturnsWithoutShopifyRef", "onlyCancelledReturns"],
                darpan.reconciliation.automation.SourceExtractOptionsSupport.declaredOptions(returns)*.name)
    }

    /** A sync re-derives an automation from its run; the run side's options are part of what it derives. */
    @Test
    void aSyncKeepsTheRunSidesOptions() {
        Map created = create([file1ExtractOptions: [onlyCancelledReturns: true]])
        assertFalse(created.hadError as boolean, created.errorText as String)
        Map derived = AutomationFacadeSupport.deriveRunAuthoritativeConfig(ec, [savedRunId: created.savedRun.savedRunId, savedRunType: "ruleset"])
        Map file1 = (Map) ((Map) derived.sourcesByFileSide).FILE_1
        assertEquals([onlyCancelledReturns: true], new groovy.json.JsonSlurper().parseText(file1.safeMetadataJson as String).parameters)
        assertNull(((Map) ((Map) derived.sourcesByFileSide).FILE_2).safeMetadataJson, "a side with no options derives none")
    }

    /** A registry-driven API side (SuiteQL, OMS GraphQL) has no remote and no RESTlet; its config id identifies it. */
    @Test
    void aRegistryDrivenSideSavesOnItsConfigIdAlone() {
        String suiteQl = "reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders"
        AutomationFacadeSupport.validateApiSource(ec, [fileSide: "FILE_2", systemEnumId: "NETSUITE_SUITEQL", sourceTypeEnumId: "AUT_SRC_API",
                safeMetadataJson: groovy.json.JsonOutput.toJson([extractServiceName: suiteQl, parameters: [nsSuiteQlSourceQueryId: "NS_OPEN_RMA"]])])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        AutomationFacadeSupport.validateApiSource(ec, [fileSide: "FILE_2", systemEnumId: "NETSUITE_SUITEQL", sourceTypeEnumId: "AUT_SRC_API",
                safeMetadataJson: groovy.json.JsonOutput.toJson([extractServiceName: suiteQl, parameters: [:]])])
        assertTrue(ec.message.errorsString.contains("requires its nsSuiteQlSourceQueryId"), ec.message.errorsString)
        ec.message.clearErrors()
        AutomationFacadeSupport.validateApiSource(ec, [fileSide: "FILE_1", systemEnumId: "NO_SUCH_SYSTEM", sourceTypeEnumId: "AUT_SRC_API"])
        assertTrue(ec.message.errorsString.contains("requires systemMessageRemoteId or nsRestletConfigId"), "an unknown system keeps the old rule")
        ec.message.clearErrors()
    }

    /** An automation over a one-system run has one source; Sync (save#Automation) must accept it. */
    @Test
    void aOneSystemAutomationSyncs() {
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TSIDE_AUTH1", authType: "NONE", isActive: "Y"])
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "TSIDE_EVAL_Q",
                    nsAuthConfigId: "TSIDE_AUTH1", recordType: "CustCred", companyUserGroupId: "KREWE", isActive: "Y", description: "eval query"])
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.NsSuiteQlSourceQueryField",
                    [nsSuiteQlSourceQueryId: "TSIDE_EVAL_Q", recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1L])
        } finally {
            if (!was) ec.artifactExecution.enableAuthz()
        }
        Map run = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun").parameters([
                runName: "One system ${UUID.randomUUID()}".toString(), scopeMode: "EVALUATE",
                file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API", file1SourceConfigId: "TSIDE_EVAL_Q",
                file1SourceConfigType: "NETSUITE_SUITEQL", file1PrimaryIdExpression: '$.records[*].tranId', rules: []]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        String runId = run.savedRun.savedRunId
        def scope = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", runId).disableAuthz().useCache(false).list().find()
        was = ec.artifactExecution.disableAuthz()
        try {
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationAutomation", [automationId: "TSIDE_AUTO",
                    automationName: "one system", companyUserGroupId: "KREWE", inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: runId,
                    savedRunType: "ruleset", ruleSetId: runId, compareScopeId: scope.compareScopeId, isActive: "N",
                    relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY", windowTimeZone: "UTC", scheduleExpr: "0 0 6 * * ?"])
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationAutomationSource", [automationId: "TSIDE_AUTO",
                    fileSide: "FILE_1", companyUserGroupId: "KREWE", sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                    safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"TSIDE_EVAL_Q"}}'])
        } finally {
            if (!was) ec.artifactExecution.enableAuthz()
        }
        // Its rules board saves through save#RuleSetRun with no second side (Aditi: "if the invoice does not have HC Order
        // ID ... it is not ours" -> an only-HotWax filter on a one-system run).
        ec.service.sync().name("facade.ReconciliationFacadeServices.save#RuleSetRun").parameters([savedRunId: runId,
                runName: "One system", file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API",
                file1SourceConfigId: "TSIDE_EVAL_Q", file1SourceConfigType: "NETSUITE_SUITEQL", file1PrimaryIdExpression: '$.records[*].tranId',
                file1ExcludeFilters: [[fieldExpression: '$.records[*].memoOrigin', operator: "INCLUDE_IN", values: ["HOTWAX"]]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(["INCLUDE_IN"], ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition("compareScopeId", scope.compareScopeId)
                .disableAuthz().useCache(false).list()*.operator)
        ec.service.sync().name("facade.ReconciliationFacadeServices.sync#Automation").parameters([automationId: "TSIDE_AUTO"]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List sources = ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("automationId", "TSIDE_AUTO")
                .disableAuthz().useCache(false).list()
        assertEquals(["FILE_1"], sources*.fileSide)
        assertTrue((sources[0].safeMetadataJson as String).contains("TSIDE_EVAL_Q"))
    }

    /** A SuiteQL query's own projection is its field list: the key card and the rules board read it. */
    @Test
    void aSuiteQlQueryOffersItsProjectionAsFields() {
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TSIDE_AUTH", authType: "NONE", isActive: "Y"])
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "TSIDE_Q",
                    nsAuthConfigId: "TSIDE_AUTH", recordType: "CustCred", companyUserGroupId: "KREWE", isActive: "Y", description: "side query",
                    originFieldName: "memoOrigin"])
            [["tranId", "tranid"], ["status", "BUILTIN.DF(status) AS status_words"]].eachWithIndex { List f, int i ->
                ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.NsSuiteQlSourceQueryField",
                        [nsSuiteQlSourceQueryId: "TSIDE_Q", recordFieldName: f[0], sourceColumn: f[1], sequenceNum: (i + 1) as Long])
            }
        } finally {
            if (!was) ec.artifactExecution.enableAuthz()
        }
        Map option = AutomationFacadeSupport.listNsSuiteQlQueryOptions(ec).find { it.sourceConfigId == "TSIDE_Q" }
        assertEquals(['$.records[*].tranId', '$.records[*].status', '$.records[*].memoOrigin'], option.fieldOptions*.fieldPath,
                "the origin field is filterable: an only-HotWax filter reads it")
        assertEquals(['$.records[*].tranId', '$.records[*].status'], option.primaryIdOptions*.fieldPath, "but it is no key")
        assertEquals(true, option.supportsExcludeFilters, "the SuiteQL connector takes source filters")
    }

    @Test
    void theManualRunHandsTheOptionsToTheExtractUnderItsOwnParams() {
        Map created = create([file1ExtractOptions: [onlyCancelledReturns: true]])
        assertFalse(created.hadError as boolean, created.errorText as String)
        Map row = source(created, "FILE_1").getMap()
        Map api = new LinkedHashMap(row + [sourceConfigId: "gorjana_prod", sourceConfigType: "HOTWAX_OMS_GQL_RETURNS",
                                            extractParametersJson: '{"onlyCancelledReturns":true,"omsRestSourceConfigId":"other"}'])
        Map captured = null
        RunExtractSupport.extractApiSource(ec, api, "FILE_1", "File 1", [location: "dbresource://tmp/run-side-options", runToken: "t"],
                [windowStartDate: Timestamp.valueOf("2026-10-21 07:00:00"), windowEndDate: Timestamp.valueOf("2026-10-22 07:00:00")],
                null, { String service, Map params -> captured = params; [:] })
        ec.message.clearErrors()
        assertEquals(true, captured.onlyCancelledReturns)
        assertEquals("gorjana_prod", captured.omsRestSourceConfigId, "an option never displaces the config id")
    }
}
