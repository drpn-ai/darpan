package darpan.facade.reconciliation

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationFacadeSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")
    private static final Timestamp RUN_NOW_TIME = Timestamp.valueOf("2026-05-01 10:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "automation-facade-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        seedLegacyPollInputMode()
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
        ReconciliationSmokeTestSupport.seedSftpServerFixtures(ec)
        seedTenant(GORJANA, "Gorjana")
        seedSystemRemotes()
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetExecutionHooks()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        AutomationExecutionSupport.resetExecutionHooks()
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedOmsRestSourceConfig(KREWE, "KREWE_OMS")
        seedShopifyAuthConfig(KREWE, "KREWE_SHOPIFY")
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        ec.message.clearErrors()
    }

    @Test
    void automationFacadeSupportsDashboardWorkflowAndExecutionHistory() {
        configureApiExecutionHooks()

        Map<String, Object> saveResult = callFacade("facade.ReconciliationFacadeServices.save#Automation", [
                automationName          : "Daily API Orders",
                description             : "Pull OMS and Shopify order windows",
                inputModeEnumId         : "AUT_IN_API_RANGE",
                savedRun                : [
                        createMode        : "csv",
                        runName           : "Daily API Saved Run",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ],
                scheduleExpr            : "PT1H",
                relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY",
                relativeWindowCount     : 1,
                windowTimeZone          : "UTC",
                sources                 : [
                        [
                                fileSide             : "FILE_1",
                                sourceTypeEnumId     : "AUT_SRC_API",
                                systemEnumId         : "OMS",
                                fileTypeEnumId       : "DftCsv",
                                systemMessageRemoteId: "OMS_REMOTE",
                                dateFromParameterName: "fromDate",
                                dateToParameterName  : "toDate",
                        ],
                        [
                                fileSide             : "FILE_2",
                                sourceTypeEnumId     : "AUT_SRC_API",
                                systemEnumId         : "SHOPIFY",
                                fileTypeEnumId       : "DftCsv",
                                systemMessageRemoteId: "SHOPIFY_REMOTE",
                                dateFromParameterName: "fromDate",
                                dateToParameterName  : "toDate",
                        ],
                ],
        ])

        assertTrue((Boolean) saveResult.ok, saveResult.errors?.toString())
        Map<String, Object> automation = (Map<String, Object>) saveResult.automation
        assertNotNull(automation.automationId)
        assertNotNull(automation.savedRunId)
        assertEquals("Daily API Saved Run", automation.savedRunName)
        assertEquals("API: HotWax via OMS_REMOTE -> SHOPIFY via SHOPIFY_REMOTE", automation.sourceSummary)
        assertEquals("PT1H", automation.scheduleSummary)
        assertEquals(true, ((Map) automation.permissions).canRunNow)
        assertEquals(2, ((List) automation.sources).size())
        Map<String, Object> file1Source = ((List<Map<String, Object>>) automation.sources).find { it.fileSide == "FILE_1" }
        Map<String, Object> file1Metadata = (Map<String, Object>) new JsonSlurper().parseText(file1Source.safeMetadataJson as String)
        assertEquals(AutomationFacadeSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE, file1Metadata.extractServiceName)
        assertEquals("KREWE_OMS", ((Map) file1Metadata.parameters).omsRestSourceConfigId)
        assertEquals("windowStart", file1Source.dateFromParameterName)
        assertEquals("windowEnd", file1Source.dateToParameterName)
        Map<String, Object> file2Source = ((List<Map<String, Object>>) automation.sources).find { it.fileSide == "FILE_2" }
        Map<String, Object> file2Metadata = (Map<String, Object>) new JsonSlurper().parseText(file2Source.safeMetadataJson as String)
        assertEquals(AutomationFacadeSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE, file2Metadata.extractServiceName)
        assertEquals("KREWE_SHOPIFY", ((Map) file2Metadata.parameters).shopifyAuthConfigId)
        assertEquals("windowStart", file2Source.dateFromParameterName)
        assertEquals("windowEnd", file2Source.dateToParameterName)

        String automationId = automation.automationId as String
        Map<String, Object> listResult = callFacade("facade.ReconciliationFacadeServices.list#Automations", [
                query    : "Daily API",
                pageIndex: 0,
                pageSize : 10,
        ])
        assertTrue((Boolean) listResult.ok, listResult.errors?.toString())
        Map<String, Object> listRow = ((List<Map<String, Object>>) listResult.automations).find {
            it.automationId == automationId
        }
        assertNotNull(listRow)
        assertEquals("Daily API Saved Run", listRow.savedRunName)
        assertEquals(null, listRow.lastExecution)

        Map<String, Object> pauseResult = callFacade("facade.ReconciliationFacadeServices.pause#Automation", [
                automationId: automationId,
        ])
        assertTrue((Boolean) pauseResult.ok, pauseResult.errors?.toString())
        assertEquals(false, ((Map) pauseResult.automation).active)
        assertEquals(true, ((Map) ((Map) pauseResult.automation).permissions).canResume)

        ec.message.clearErrors()
        Map<String, Object> resumeResult = callFacade("facade.ReconciliationFacadeServices.resume#Automation", [
                automationId: automationId,
        ])
        assertTrue((Boolean) resumeResult.ok, resumeResult.errors?.toString())
        assertEquals(true, ((Map) resumeResult.automation).active)

        ec.message.clearErrors()
        Map<String, Object> runResult = callFacade("facade.ReconciliationFacadeServices.run#AutomationNow", [
                automationId      : automationId,
                scheduledFireTime : RUN_NOW_TIME,
                sparkAppName      : "AutomationFacadeSmoke",
        ])
        assertTrue((Boolean) runResult.ok, runResult.errors?.toString())
        assertEquals(1, ((Map) runResult.runResult).executedCount)
        assertEquals(0, ((Map) runResult.runResult).failedCount)

        ec.message.clearErrors()
        Map<String, Object> historyResult = callFacade("facade.ReconciliationFacadeServices.list#AutomationExecutions", [
                automationId: automationId,
                pageIndex   : 0,
                pageSize    : 10,
        ])
        assertTrue((Boolean) historyResult.ok, historyResult.errors?.toString())
        List<Map<String, Object>> executions = (List<Map<String, Object>>) historyResult.executions
        assertEquals(1, executions.size())
        assertEquals("AUT_STAT_SUCCESS", executions[0].statusEnumId)
        assertEquals(2, executions[0].differenceCount)
        assertEquals("result.json", executions[0].resultFileName)
        assertEquals("reconciliation-runs/${automationId}/result.json".toString(), executions[0].resultDataManagerPath)
        assertNotNull(executions[0].reconciliationRunResultId)

        Timestamp rerunTime = Timestamp.valueOf("2026-05-01 10:15:00")
        ec.message.clearErrors()
        Map<String, Object> rerunResult = callFacade("facade.ReconciliationFacadeServices.run#AutomationNow", [
                automationId      : automationId,
                scheduledFireTime : rerunTime,
                sparkAppName      : "AutomationFacadeSmoke",
        ])
        assertTrue((Boolean) rerunResult.ok, rerunResult.errors?.toString())
        assertEquals(1, ((Map) rerunResult.runResult).executedCount)

        ec.message.clearErrors()
        Map<String, Object> collapsedHistoryResult = callFacade("facade.ReconciliationFacadeServices.list#AutomationExecutions", [
                automationId: automationId,
                pageIndex   : 0,
                pageSize    : 10,
        ])
        assertTrue((Boolean) collapsedHistoryResult.ok, collapsedHistoryResult.errors?.toString())
        executions = (List<Map<String, Object>>) collapsedHistoryResult.executions
        assertEquals(1, executions.size())
        assertEquals(rerunTime, executions[0].scheduledDate)

        def executionValue = findOne("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: executions[0].automationExecutionId,
        ])
        executionValue.set("resultFileName", null)
        executionValue.set("resultDataManagerPath", null)
        executionValue.update()

        ec.message.clearErrors()
        Map<String, Object> fallbackHistoryResult = callFacade("facade.ReconciliationFacadeServices.list#AutomationExecutions", [
                automationId: automationId,
                pageIndex   : 0,
                pageSize    : 10,
        ])
        assertTrue((Boolean) fallbackHistoryResult.ok, fallbackHistoryResult.errors?.toString())
        List<Map<String, Object>> fallbackExecutions = (List<Map<String, Object>>) fallbackHistoryResult.executions
        assertEquals("result.json", fallbackExecutions[0].resultFileName)
        assertEquals("reconciliation-runs/${automationId}/result.json".toString(), fallbackExecutions[0].resultDataManagerPath)

        ec.message.clearErrors()
        Map<String, Object> refreshedList = callFacade("facade.ReconciliationFacadeServices.list#Automations", [
                query    : "Daily API",
                pageIndex: 0,
                pageSize : 10,
        ])
        Map<String, Object> refreshedRow = ((List<Map<String, Object>>) refreshedList.automations).find {
            it.automationId == automationId
        }
        assertEquals("AUT_STAT_SUCCESS", ((Map) refreshedRow.lastExecution).statusEnumId)
        assertEquals(1, refreshedRow.executionCount)

        ec.message.clearErrors()
        Map<String, Object> deleteResult = callFacade("facade.ReconciliationFacadeServices.delete#Automation", [
                automationId: automationId,
        ])
        assertTrue((Boolean) deleteResult.ok, deleteResult.errors?.toString())
        assertEquals(true, deleteResult.deleted)
        assertEquals(2, deleteResult.deletedSourceCount)
        assertEquals(2, deleteResult.deletedExecutionCount)
        assertNull(findOne("darpan.reconciliation.ReconciliationAutomation", [automationId: automationId]))
    }

    @Test
    void automationFacadeEnforcesTenantAndViewOnlyBoundaries() {
        Map<String, Object> saveResult = saveSftpAutomation("Tenant Guard SFTP")
        assertTrue((Boolean) saveResult.ok, saveResult.errors?.toString())
        String automationId = ((Map) saveResult.automation).automationId as String

        ec.message.clearErrors()
        Map<String, Object> optionsResult = callFacade("facade.ReconciliationFacadeServices.list#AutomationSourceOptions", [:])
        assertTrue((Boolean) optionsResult.ok, optionsResult.errors?.toString())
        List<String> inputModeIds = ((List<Map<String, Object>>) optionsResult.inputModes).collect { it.enumId as String }
        assertEquals(["AUT_IN_API_RANGE", "AUT_IN_SFTP_FILES"] as Set, inputModeIds as Set)
        assertFalse(inputModeIds.contains("AUT_IN_SFTP_POLL"))
        List<Map<String, Object>> sftpServers = (List<Map<String, Object>>) optionsResult.sftpServers
        assertTrue(sftpServers.any { it.sftpServerId == "SHOPIFY_TEST_SFTP" })
        assertTrue(sftpServers.any { it.sftpServerId == "SHARED_TEST_SFTP" })
        assertFalse(sftpServers.any { it.sftpServerId == "ADMIN_TEST_SFTP" })
        List<Map<String, Object>> sourceConfigs = (List<Map<String, Object>>) optionsResult.sourceConfigs
        assertTrue(sourceConfigs.any { it.sourceConfigId == "KREWE_OMS" && it.sourceConfigType == "HOTWAX_OMS_REST" && it.systemEnumId == "OMS" })
        assertTrue(sourceConfigs.any { it.sourceConfigId == "KREWE_SHOPIFY" && it.sourceConfigType == "SHOPIFY_AUTH" && it.systemEnumId == "SHOPIFY" })
        Map<String, Object> omsSourceOption = ((List<Map<String, Object>>) optionsResult.systemRemotes).find {
            it.optionKey == "KREWE_OMS"
        }
        assertNotNull(omsSourceOption)
        assertEquals("HOTWAX_ORDERS_API", omsSourceOption.systemMessageRemoteId)
        assertEquals("Orders API", omsSourceOption.label)
        assertEquals("KREWE_OMS", omsSourceOption.sourceConfigId)
        assertEquals("HOTWAX_OMS_REST", omsSourceOption.sourceConfigType)
        assertEquals(AutomationFacadeSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                ((Map) new JsonSlurper().parseText(omsSourceOption.safeMetadataJson as String)).extractServiceName)
        assertEquals("windowStart", omsSourceOption.dateFromParameterName)
        assertEquals("windowEnd", omsSourceOption.dateToParameterName)
        List<Map<String, Object>> primaryIdOptions = (List<Map<String, Object>>) omsSourceOption.primaryIdOptions
        assertTrue(primaryIdOptions.any { it.fieldPath == "\$.records[*].orderId" && it.label == "Order ID" })
        Map<String, Object> shopifySourceOption = ((List<Map<String, Object>>) optionsResult.systemRemotes).find {
            it.systemMessageRemoteId == "SHOPIFY_REMOTE"
        }
        assertNotNull(shopifySourceOption)
        assertEquals("SHOPIFY", shopifySourceOption.systemEnumId)
        assertEquals("Admin GraphQL Orders", shopifySourceOption.label)
        assertEquals("KREWE_SHOPIFY", shopifySourceOption.sourceConfigId)
        assertEquals("SHOPIFY_AUTH", shopifySourceOption.sourceConfigType)
        Map<String, Object> shopifyMetadata = (Map<String, Object>) new JsonSlurper().parseText(shopifySourceOption.safeMetadataJson as String)
        assertEquals(AutomationFacadeSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE, shopifyMetadata.extractServiceName)
        assertEquals("KREWE_SHOPIFY", ((Map) shopifyMetadata.parameters).shopifyAuthConfigId)
        assertEquals("windowStart", shopifySourceOption.dateFromParameterName)
        assertEquals("windowEnd", shopifySourceOption.dateToParameterName)
        List<Map<String, Object>> shopifySourceOptions = ((List<Map<String, Object>>) optionsResult.systemRemotes).findAll {
            it.systemEnumId == "SHOPIFY"
        }
        assertEquals(["SHOPIFY_REMOTE"], shopifySourceOptions.collect { it.systemMessageRemoteId })
        List<Map<String, Object>> shopifyPrimaryIdOptions = (List<Map<String, Object>>) shopifySourceOption.primaryIdOptions
        assertTrue(shopifyPrimaryIdOptions.any { it.fieldPath == "\$.records[*].id" && it.label == "Order ID" })
        assertTrue(shopifyPrimaryIdOptions.any { it.fieldPath == "\$.records[*].name" && it.label == "Order name" })
        assertTrue(((List<Map<String, Object>>) optionsResult.savedRuns).any { it.savedRunId == "OrderIdSchemaMap" })

        ec.message.clearErrors()
        Map<String, Object> blockedSftpResult = callFacade("facade.ReconciliationFacadeServices.save#Automation", [
                automationName : "Blocked cross-tenant SFTP",
                inputModeEnumId: "AUT_IN_SFTP_FILES",
                savedRunId     : "OrderIdSchemaMap",
                savedRunType   : "mapping",
                scheduleExpr   : "0 0 * * * ?",
                sources        : [
                        [
                                fileSide          : "FILE_1",
                                sourceTypeEnumId  : "AUT_SRC_SFTP",
                                systemEnumId      : "OMS",
                                fileTypeEnumId    : "DftCsv",
                                sftpServerId      : "GORJANA_TEST_SFTP",
                                remotePathTemplate: "/incoming/oms",
                        ],
                        [
                                fileSide          : "FILE_2",
                                sourceTypeEnumId  : "AUT_SRC_SFTP",
                                systemEnumId      : "SHOPIFY",
                                fileTypeEnumId    : "DftCsv",
                                sftpServerId      : "SHOPIFY_TEST_SFTP",
                                remotePathTemplate: "/incoming/shopify",
                        ],
                ],
        ])
        assertFalse((Boolean) blockedSftpResult.ok)
        assertTrue((blockedSftpResult.errors ?: []).join(" ").contains("not available to tenant"))

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        Map<String, Object> gorjanaList = callFacade("facade.ReconciliationFacadeServices.list#Automations", [
                pageIndex: 0,
                pageSize : 20,
        ])
        assertTrue((Boolean) gorjanaList.ok, gorjanaList.errors?.toString())
        assertFalse(((List<Map<String, Object>>) gorjanaList.automations).any { it.automationId == automationId })

        ec.message.clearErrors()
        Map<String, Object> gorjanaGet = callFacade("facade.ReconciliationFacadeServices.get#Automation", [
                automationId: automationId,
        ])
        assertFalse((Boolean) gorjanaGet.ok)
        assertTrue((gorjanaGet.errors ?: []).join(" ").contains("was not found"))

        ec.message.clearErrors()
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)

        Map<String, Object> viewOnlyList = callFacade("facade.ReconciliationFacadeServices.list#Automations", [
                pageIndex: 0,
                pageSize : 20,
        ])
        assertTrue((Boolean) viewOnlyList.ok, viewOnlyList.errors?.toString())
        assertTrue(((List<Map<String, Object>>) viewOnlyList.automations).any { it.automationId == automationId })

        ec.message.clearErrors()
        Map<String, Object> viewOnlyPause = callFacade("facade.ReconciliationFacadeServices.pause#Automation", [
                automationId: automationId,
        ])
        assertFalse((Boolean) viewOnlyPause.ok)
        assertTrue((viewOnlyPause.errors ?: []).join(" ").contains("view access"))

        ec.message.clearErrors()
        Map<String, Object> viewOnlyRun = callFacade("facade.ReconciliationFacadeServices.run#AutomationNow", [
                automationId: automationId,
        ])
        assertFalse((Boolean) viewOnlyRun.ok)
        assertTrue((viewOnlyRun.errors ?: []).join(" ").contains("view access"))

        ec.message.clearErrors()
        Map<String, Object> viewOnlyHistory = callFacade("facade.ReconciliationFacadeServices.list#AutomationExecutions", [
                automationId: automationId,
                pageIndex   : 0,
                pageSize    : 10,
        ])
        assertTrue((Boolean) viewOnlyHistory.ok, viewOnlyHistory.errors?.toString())
    }

    @Test
    void apiRuleSetRunRequiresAndPersistsSelectedPrimaryIdExpression() {
        Map<String, Object> createResult = callFacade("facade.ReconciliationFacadeServices.create#RuleSetRun", [
                runName                   : "Automation API Primary ID",
                file1SystemEnumId         : "OMS",
                file1SourceTypeEnumId     : "AUT_SRC_API",
                file1SystemMessageRemoteId: "OMS_REMOTE",
                file1SourceConfigId       : "KREWE_OMS",
                file1SourceConfigType     : "HOTWAX_OMS_REST",
                file1PrimaryIdExpression  : "\$.records[*].orderId",
                file2SystemEnumId         : "SHOPIFY",
                file2FileTypeEnumId       : "DftCsv",
                file2PrimaryIdExpression  : "order_id",
                rules                     : [],
        ])

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        Map<String, Object> savedRun = (Map<String, Object>) createResult.savedRun
        Map<String, Object> file1Option = ((List<Map<String, Object>>) savedRun.systemOptions)
                .find { it.fileSide == "FILE_1" }
        assertEquals("AUT_SRC_API", file1Option.sourceTypeEnumId)
        assertEquals("OMS_REMOTE", file1Option.systemMessageRemoteId)
        assertEquals("KREWE_OMS", file1Option.sourceConfigId)
        assertEquals("HOTWAX_OMS_REST", file1Option.sourceConfigType)
        assertEquals("\$.records[*].orderId", file1Option.idFieldExpression)

        def file1Source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", savedRun.compareScopeId)
                .condition("fileSide", "FILE_1")
                .disableAuthz()
                .useCache(false)
                .one()
        assertEquals("AUT_SRC_API", file1Source.sourceTypeEnumId)
        assertEquals("OMS_REMOTE", file1Source.systemMessageRemoteId)
        assertEquals("KREWE_OMS", file1Source.sourceConfigId)
        assertEquals("HOTWAX_OMS_REST", file1Source.sourceConfigType)
        assertEquals("\$.records[*].orderId", file1Source.primaryIdExpression)

        ec.message.clearErrors()
        Map<String, Object> missingPrimaryIdResult = callFacade("facade.ReconciliationFacadeServices.create#RuleSetRun", [
                runName                   : "Automation Missing API Primary ID",
                file1SystemEnumId         : "OMS",
                file1SourceTypeEnumId     : "AUT_SRC_API",
                file1SystemMessageRemoteId: "OMS_REMOTE",
                file2SystemEnumId         : "SHOPIFY",
                file2FileTypeEnumId       : "DftCsv",
                file2PrimaryIdExpression  : "order_id",
                rules                     : [],
        ])

        assertEquals(false, missingPrimaryIdResult.ok)
        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.contains("file1PrimaryIdExpression is required") })
    }

    private Map<String, Object> saveSftpAutomation(String automationName) {
        return callFacade("facade.ReconciliationFacadeServices.save#Automation", [
                automationName : automationName,
                inputModeEnumId: "AUT_IN_SFTP_FILES",
                savedRunId     : "OrderIdSchemaMap",
                savedRunType   : "mapping",
                scheduleExpr   : "0 0 * * * ?",
                sources        : [
                        [
                                fileSide          : "FILE_1",
                                sourceTypeEnumId  : "AUT_SRC_SFTP",
                                systemEnumId      : "OMS",
                                fileTypeEnumId    : "DftCsv",
                                sftpServerId      : "OMS_TEST_SFTP",
                                remotePathTemplate: "/incoming/oms",
                        ],
                        [
                                fileSide          : "FILE_2",
                                sourceTypeEnumId  : "AUT_SRC_SFTP",
                                systemEnumId      : "SHOPIFY",
                                fileTypeEnumId    : "DftCsv",
                                sftpServerId      : "SHOPIFY_TEST_SFTP",
                                remotePathTemplate: "/incoming/shopify",
                        ],
                ],
        ])
    }

    private void configureApiExecutionHooks() {
        AutomationExecutionSupport.setSourceExtractor { Object ecArg, Object automation, Object source,
                Map<String, Object> window, Map<String, Object> params ->
            String fileSide = source.get("fileSide") as String
            return [
                    dataAvailable: true,
                    fileLocation : "runtime://tmp/automation/${fileSide}.csv".toString(),
                    fileName     : "${fileSide}.csv".toString(),
                    recordCount  : fileSide == "FILE_1" ? 5 : 4,
            ]
        }
        AutomationExecutionSupport.setReconcileRunner { Object ecArg, Object automation, Object file1Source,
                Object file2Source, Map<String, Object> file1Result, Map<String, Object> file2Result,
                Map<String, Object> window, Map<String, Object> params ->
            return [
                    reconciliationType: "ORDER",
                    diffLocation      : "reconciliation-runs/${automation.get("automationId")}/result.json".toString(),
                    diffFileName      : "result.json",
                    differenceCount   : 2,
                    onlyInFile1Count  : 1,
                    onlyInFile2Count  : 1,
                    validationErrors  : [],
                    processingWarnings: [],
            ]
        }
    }

    private Map<String, Object> callFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }

    private def findOne(String entityName, Map<String, Object> pkFields) {
        return ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private void seedSystemRemotes() {
        upsertEntity("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "OMS_REMOTE"], [
                systemMessageRemoteId: "OMS_REMOTE",
                description          : "OMS API source",
                sendUrl              : "https://oms.example.invalid/api/orders?password=secret",
        ])
        upsertEntity("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "HOTWAX_ORDERS_API"], [
                systemMessageRemoteId: "HOTWAX_ORDERS_API",
                description          : "Orders API",
                sendUrl              : "{baseUrl}/rest/s1/oms/orders",
                sendServiceName      : AutomationFacadeSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
        ])
        upsertEntity("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "SHOPIFY_REMOTE"], [
                systemMessageRemoteId: "SHOPIFY_REMOTE",
                description          : "Admin GraphQL Orders",
                sendUrl              : "https://shopify.example.invalid/admin/api/graphql.json",
                sendServiceName      : AutomationFacadeSupport.SHOPIFY_GRAPHQL_EXECUTE_SERVICE,
        ])
        upsertEntity("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "SHOPIFY"], [
                systemMessageRemoteId: "SHOPIFY",
                description          : "Shopify",
                sendUrl              : "https://shopify.uat.example.invalid",
        ])
    }

    private void seedOmsRestSourceConfig(String tenantId, String configId) {
        upsertEntityValue("darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: configId], [
                omsRestSourceConfigId : configId,
                description           : "Krewe OMS Orders",
                companyUserGroupId    : tenantId,
                createdByUserId       : TEST_USER_ID,
                baseUrl               : "https://oms.example.invalid",
                ordersPath            : "/rest/s1/oms/orders",
                authType              : "NONE",
                connectTimeoutSeconds : 30L,
                readTimeoutSeconds    : 60L,
                isActive              : "Y",
                canReadOrders         : "Y",
                createdDate           : TEST_FROM_DATE,
                lastUpdatedDate       : TEST_FROM_DATE,
        ])
    }

    private void seedShopifyAuthConfig(String tenantId, String configId) {
        upsertEntityValue("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: configId], [
                shopifyAuthConfigId: configId,
                description        : "Krewe Shopify",
                companyUserGroupId : tenantId,
                createdByUserId    : TEST_USER_ID,
                shopApiUrl         : "https://krewe.myshopify.com",
                apiVersion         : "2026-01",
                accessToken        : "shpat_test",
                isActive           : "Y",
                canReadOrders      : "Y",
        ])
    }

    private void seedLegacyPollInputMode() {
        upsertEntityValue("moqui.basic.Enumeration", [enumId: "AUT_IN_SFTP_POLL"], [
                enumId     : "AUT_IN_SFTP_POLL",
                enumTypeId : "AutomationInputMode",
                enumCode   : "SFTP_POLL",
                description: "SFTP_POLL",
                sequenceNum: 99L,
        ])
    }

    private void seedTenant(String tenantId, String label) {
        upsertEntity("moqui.security.UserGroup", [userGroupId: tenantId], [
                userGroupId    : tenantId,
                description    : label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertEntityValue("moqui.security.UserGroupMember", [
                userGroupId: tenantId,
                userId     : TEST_USER_ID,
                fromDate   : TEST_FROM_DATE,
        ], [
                userGroupId: tenantId,
                userId     : TEST_USER_ID,
                fromDate   : TEST_FROM_DATE,
        ])
        replaceTenantPermission(tenantId, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
    }

    private void replaceTenantPermission(String tenantId, String permissionGroupId) {
        seedPermissionGroup(permissionGroupId, permissionGroupId)
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "replaceAutomationTenantPermission",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                    .condition("tenantUserGroupId", tenantId)
                    .condition("userId", TEST_USER_ID)
                    .disableAuthz()
                    .useCache(false)
                    .list()
                    .each { it.delete() }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        upsertEntityValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: permissionGroupId,
                fromDate             : TEST_FROM_DATE,
        ], [
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: permissionGroupId,
                fromDate             : TEST_FROM_DATE,
        ])
    }

    private void seedPermissionGroup(String permissionGroupId, String description) {
        upsertEntity("moqui.security.UserGroup", [userGroupId: permissionGroupId], [
                userGroupId    : permissionGroupId,
                description    : description,
                groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID,
        ])
    }

    private void upsertEntity(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        def existing = ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
        if (existing != null) return

        ec.service.sync()
                .name("store#${entityName}")
                .parameters(fields)
                .disableAuthz()
                .call()
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        def existing = ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
        if (existing != null) return

        ec.entity.makeValue(entityName)
                .setAll(fields)
                .create()
    }
}
