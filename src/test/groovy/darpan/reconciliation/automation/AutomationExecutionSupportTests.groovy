package darpan.reconciliation.automation

import darpan.reconciliation.notification.TenantNotificationSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

class AutomationExecutionSupportTests {
    private static final Timestamp NOW = timestamp("2026-05-01T10:00:00Z")

    @Test
    void previousDayWindowUsesAutomationTimezone() {
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                windowTimeZone          : "America/Los_Angeles",
        ]

        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(automation, [
                scheduledFireTime: timestamp("2026-05-01T10:00:00Z"),
        ])

        assertEquals(1, windows.size())
        assertEquals(timestamp("2026-04-30T07:00:00Z"), windows[0].childWindowStartDate)
        assertEquals(timestamp("2026-05-01T07:00:00Z"), windows[0].childWindowEndDate)
    }

    @Test
    void customBackfillSplitsOnCalendarMonthBoundaries() {
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_CUSTOM,
                windowTimeZone          : "UTC",
        ]

        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(automation, [
                windowStartDate: timestamp("2026-01-15T00:00:00Z"),
                windowEndDate  : timestamp("2026-04-10T00:00:00Z"),
        ])

        assertEquals(4, windows.size())
        assertEquals(timestamp("2026-01-15T00:00:00Z"), windows[0].childWindowStartDate)
        assertEquals(timestamp("2026-02-01T00:00:00Z"), windows[0].childWindowEndDate)
        assertEquals(timestamp("2026-02-01T00:00:00Z"), windows[1].childWindowStartDate)
        assertEquals(timestamp("2026-03-01T00:00:00Z"), windows[1].childWindowEndDate)
        assertEquals(timestamp("2026-03-01T00:00:00Z"), windows[2].childWindowStartDate)
        assertEquals(timestamp("2026-04-01T00:00:00Z"), windows[2].childWindowEndDate)
        assertEquals(timestamp("2026-04-01T00:00:00Z"), windows[3].childWindowStartDate)
        assertEquals(timestamp("2026-04-10T00:00:00Z"), windows[3].childWindowEndDate)
    }

    @Test
    void lastNDaysUsesBoundedCalendarDaysBeforeScheduledFire() {
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_LAST_DAYS,
                relativeWindowCount     : 3,
                windowTimeZone          : "UTC",
        ]

        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(automation, [
                scheduledFireTime: timestamp("2026-05-01T10:00:00Z"),
        ])

        assertEquals(1, windows.size())
        assertEquals(timestamp("2026-04-28T00:00:00Z"), windows[0].childWindowStartDate)
        assertEquals(timestamp("2026-05-01T00:00:00Z"), windows[0].childWindowEndDate)
    }

    @Test
    void lastNMonthsUsesCalendarMonthBoundariesBeforeScheduledFire() {
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_LAST_MONTHS,
                relativeWindowCount     : 2,
                windowTimeZone          : "UTC",
        ]

        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(automation, [
                scheduledFireTime: timestamp("2026-05-18T10:00:00Z"),
        ])

        assertEquals(2, windows.size())
        assertEquals(timestamp("2026-03-01T00:00:00Z"), windows[0].childWindowStartDate)
        assertEquals(timestamp("2026-04-01T00:00:00Z"), windows[0].childWindowEndDate)
        assertEquals(timestamp("2026-04-01T00:00:00Z"), windows[1].childWindowStartDate)
        assertEquals(timestamp("2026-05-01T00:00:00Z"), windows[1].childWindowEndDate)
    }

    @Test
    void customWindowCanComeFromAutomationConfiguration() {
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_CUSTOM,
                customWindowStartDate   : timestamp("2026-04-01T00:00:00Z"),
                customWindowEndDate     : timestamp("2026-05-01T00:00:00Z"),
                windowTimeZone          : "UTC",
        ]

        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(automation, [
                scheduledFireTime: timestamp("2026-05-18T10:00:00Z"),
        ])

        assertEquals(1, windows.size())
        assertEquals(timestamp("2026-04-01T00:00:00Z"), windows[0].childWindowStartDate)
        assertEquals(timestamp("2026-05-01T00:00:00Z"), windows[0].childWindowEndDate)
    }

    @Test
    void apiExecutionCreatesIdempotentRowsAndCallsConfiguredSourceServices() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == "fixture.extractSource") {
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : call.params.fileSide == "FILE_1" ? 10 : 9,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                return [
                        reconciliationType: "ORDER",
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                        onlyInFile1Count  : 1,
                        onlyInFile2Count  : 3,
                        validationErrors  : [],
                        processingWarnings: [],
                ]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : "AUTO_API",
                scheduledFireTime: NOW,
        ])

        assertEquals(1, result.executedCount)
        assertEquals(0, result.skippedDuplicateCount)
        assertEquals(3, ec.service.calls.size())

        FakeServiceCall file1Extract = ec.service.calls.find { it.serviceName == "fixture.extractSource" && it.params.fileSide == "FILE_1" }
        assertNotNull(file1Extract)
        assertEquals("TENANT_A", file1Extract.params.companyUserGroupId)
        assertEquals(timestamp("2026-04-30T00:00:00Z"), file1Extract.params.updatedFrom)
        assertEquals(timestamp("2026-05-01T00:00:00Z"), file1Extract.params.updatedTo)

        FakeServiceCall reconcile = ec.service.calls.find { it.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope" }
        assertEquals("RS_ORDER", reconcile.params.ruleSetId)
        assertEquals("SCOPE_ORDER", reconcile.params.compareScopeId)
        assertEquals("runtime://tmp/FILE_1.json", reconcile.params.file1Location)
        assertEquals("runtime://tmp/FILE_2.json", reconcile.params.file2Location)

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
        assertEquals("FILE_1.json", execution.file1Name)
        assertEquals("FILE_2.json", execution.file2Name)
        assertEquals("result.json", execution.resultFileName)
        assertEquals("reconciliation-runs/AUTO_API/20260501/result.json", execution.resultDataManagerPath)
        assertEquals(4, execution.differenceCount)
        assertEquals("RUN_RESULT_1", execution.reconciliationRunResultId)

        FakeValue runResult = ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult")[0]
        assertEquals("RS_ORDER", runResult.savedRunId)
        assertEquals("runtime://tmp/FILE_1.json", runResult.file1DataManagerPath)
        assertEquals("reconciliation-runs/AUTO_API/20260501/result.json", runResult.resultDataManagerPath)

        Map duplicateResult = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : "AUTO_API",
                scheduledFireTime: NOW,
        ])

        assertEquals(1, duplicateResult.skippedDuplicateCount)
        assertEquals(1, ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution").size())
        assertEquals(3, ec.service.calls.size())
        assertEquals(AutomationExecutionSupport.STATUS_SKIPPED_DUPLICATE,
                ((List<Map>) duplicateResult.executionResults)[0].statusEnumId)
    }

    @Test
    void apiExecutionPersistsRunResultWhenCompareReturnsOnlyDiffFileName() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == "fixture.extractSource") {
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                return [
                        reconciliationType: "ORDER",
                        diffFileName      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        differenceCount   : 4,
                        onlyInFile1Count  : 1,
                        onlyInFile2Count  : 3,
                ]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : "AUTO_API",
                scheduledFireTime: NOW,
        ])

        assertEquals(1, result.executedCount)
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
        assertEquals("result.json", execution.resultFileName)
        assertEquals("reconciliation-runs/AUTO_API/20260501/result.json", execution.resultDataManagerPath)
        assertEquals("RUN_RESULT_1", execution.reconciliationRunResultId)

        FakeValue runResult = ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult")[0]
        assertEquals("reconciliation-runs/AUTO_API/20260501/result.json", runResult.resultDataManagerPath)
    }

    @Test
    void apiExecutionInfersShopifyExtractorForLegacySourceRows() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        FakeValue shopifySource = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"].find {
            it.fileSide == "FILE_1"
        }
        shopifySource.remove("safeMetadataJson")
        shopifySource.remove("dateFromParameterName")
        shopifySource.remove("dateToParameterName")
        ec.entity.add("darpan.shopify.ShopifyAuthConfig", [
                shopifyAuthConfigId: "SHOPIFY_MAIN",
                companyUserGroupId : "TENANT_A",
                isActive           : "Y",
                canReadOrders      : "Y",
        ])
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/shopify-orders.json",
                        fileName     : "shopify-orders.json",
                        recordCount  : 7,
                ]
            }
            if (call.serviceName == "fixture.extractSource") {
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 6,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                return [
                        diffLocation    : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName    : "result.json",
                        differenceCount : 0,
                        validationErrors: [],
                ]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : "AUTO_API",
                scheduledFireTime: NOW,
        ])

        assertEquals(1, result.executedCount)
        FakeServiceCall shopifyExtract = ec.service.calls.find {
            it.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE
        }
        assertNotNull(shopifyExtract)
        assertEquals("SHOPIFY_MAIN", shopifyExtract.params.shopifyAuthConfigId)
        assertEquals(timestamp("2026-04-30T00:00:00Z"), shopifyExtract.params.windowStart)
        assertEquals(timestamp("2026-05-01T00:00:00Z"), shopifyExtract.params.windowEnd)
    }

    @Test
    void successfulApiExecutionSendsConfiguredTenantRunCompletionNotification() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add("moqui.security.UserGroup", [
                userGroupId    : "TENANT_A",
                groupTypeEnumId: "UgtDarpanCompany",
                description    : "Tenant A",
        ])
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantNotificationSetting", [
                companyUserGroupId   : "TENANT_A",
                createdByUserId      : "tester",
                googleChatWebhookUrl : webhookUrl,
                isActive             : "Y",
                createdDate          : NOW,
                lastUpdatedDate      : NOW,
        ])
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == "fixture.extractSource") {
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : call.params.fileSide == "FILE_1" ? 10 : 9,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                return [
                        reconciliationType: "ORDER",
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                        onlyInFile1Count  : 1,
                        onlyInFile2Count  : 3,
                        validationErrors  : [],
                        processingWarnings: [],
                ]
            }
            return [:]
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map result = AutomationExecutionSupport.executeAutomation(ec, [
                    automationId     : "AUTO_API",
                    scheduledFireTime: NOW,
            ])

            assertEquals(1, result.executedCount)
            assertEquals(1, deliveries.size())
            assertEquals(webhookUrl, deliveries[0].webhookUrl)
            String text = deliveries[0].payload.text as String
            assertTrue(text.contains("Darpan run completed: API Automation"))
            assertTrue(text.contains("Tenant: Tenant A"))
            assertTrue(text.contains("Result ID: RUN_RESULT_1"))
            assertTrue(text.contains("Run result: <https://hotwax-darpan-dev.web.app/reconciliation/run-result/RS_ORDER/reconciliation-runs%2FAUTO_API%2F20260501%2Fresult.json?runName=API+Automation&file1SystemLabel=SHOPIFY&file2SystemLabel=OMS|Open run result>"))
            assertTrue(text.contains("Differences: 4"))
            assertTrue(text.contains("Only in SHOPIFY: 1"))
            assertTrue(text.contains("Only in OMS: 3"))
            assertFalse(text.contains("Only in file 1"))
            assertFalse(text.contains("Only in file 2"))
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void apiExecutionRecordsNoDataWhenOneSourceIsEmpty() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == "fixture.extractSource") {
                return [
                        dataAvailable: call.params.fileSide == "FILE_1",
                        fileLocation : call.params.fileSide == "FILE_1" ? "runtime://tmp/file1.json" : null,
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : call.params.fileSide == "FILE_1" ? 10 : 0,
                ]
            }
            throw new IllegalStateException("Reconcile should not run for no-data windows")
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : "AUTO_API",
                scheduledFireTime: NOW,
        ])

        assertEquals(1, result.noDataCount)
        assertFalse(ec.service.calls.any { it.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope" })
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_NO_DATA, execution.statusEnumId)
        assertTrue(execution.safeMetadataJson.contains("file2DataAvailable"))
    }

    @Test
    void scannerFindsDueAutomationsCallsExecutorAndAdvancesNextFireTime() {
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId          : "AUTO_DUE",
                automationName        : "Due automation",
                companyUserGroupId    : "TENANT_A",
                inputModeEnumId       : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                scheduleExpr          : "0 0 * * * ?",
                windowTimeZone        : "UTC",
                isActive              : "Y",
                nextScheduledFireTime : timestamp("2026-05-01T09:00:00Z"),
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId          : "AUTO_FUTURE",
                automationName        : "Future automation",
                companyUserGroupId    : "TENANT_A",
                inputModeEnumId       : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                scheduleExpr          : "0 0 * * * ?",
                windowTimeZone        : "UTC",
                isActive              : "Y",
                nextScheduledFireTime : timestamp("2026-05-01T11:00:00Z"),
        ])
        ec.service.responder = { FakeServiceCall call ->
            [executedCount: 1, automationId: call.params.automationId]
        }

        Map result = AutomationExecutionSupport.scanDueAutomations(ec, [
                nowTimestamp: NOW,
                limit       : 100,
        ])

        assertEquals(1, result.dueCount)
        assertEquals(1, ec.service.calls.size())
        FakeServiceCall executeCall = ec.service.calls[0]
        assertEquals("reconciliation.ReconciliationAutomationServices.execute#Automation", executeCall.serviceName)
        assertEquals("AUTO_DUE", executeCall.params.automationId)
        assertEquals(timestamp("2026-05-01T09:00:00Z"), executeCall.params.scheduledFireTime)

        FakeValue dueAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_DUE"
        }
        assertEquals(timestamp("2026-05-01T09:00:00Z"), dueAutomation.lastScheduledFireTime)
        assertEquals(timestamp("2026-05-01T11:00:00Z"), dueAutomation.nextScheduledFireTime)
        assertTrue(dueAutomation.@updated)
    }

    @Test
    void scannerUsesMoquiCronExpressionWhenNextFireIsNotPrecomputed() {
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId           : "AUTO_CRON_DUE",
                automationName         : "Cron due automation",
                companyUserGroupId     : "TENANT_A",
                inputModeEnumId        : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                scheduleExpr           : "0 0 * * * ?",
                windowTimeZone         : "UTC",
                isActive               : "Y",
                lastScheduledFireTime  : timestamp("2026-05-01T09:00:00Z"),
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId           : "AUTO_CRON_CURRENT",
                automationName         : "Cron current automation",
                companyUserGroupId     : "TENANT_A",
                inputModeEnumId        : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                scheduleExpr           : "0 0 * * * ?",
                windowTimeZone         : "UTC",
                isActive               : "Y",
                lastScheduledFireTime  : timestamp("2026-05-01T10:00:00Z"),
        ])
        ec.service.responder = { FakeServiceCall call ->
            [executedCount: 1, automationId: call.params.automationId]
        }

        Map result = AutomationExecutionSupport.scanDueAutomations(ec, [
                nowTimestamp: timestamp("2026-05-01T10:05:00Z"),
                limit       : 100,
        ])

        assertEquals(1, result.dueCount)
        assertEquals(1, ec.service.calls.size())
        FakeServiceCall executeCall = ec.service.calls[0]
        assertEquals("AUTO_CRON_DUE", executeCall.params.automationId)
        assertEquals(timestamp("2026-05-01T10:00:00Z"), executeCall.params.scheduledFireTime)

        FakeValue dueAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_CRON_DUE"
        }
        assertEquals(timestamp("2026-05-01T10:00:00Z"), dueAutomation.lastScheduledFireTime)
        assertEquals(timestamp("2026-05-01T11:00:00Z"), dueAutomation.nextScheduledFireTime)
    }

    private static FakeEc fakeEc() {
        return new FakeEc(
                entity: new FakeEntityFacade(),
                service: new FakeServiceFacade(),
                transaction: new FakeTransactionFacade(),
                resource: new Expando(properties: [:]),
                user: new Expando(nowTimestamp: NOW, userId: "tester"),
        )
    }

    private static void seedApiAutomation(FakeEc ec) {
        ec.entity.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId             : "AUTO_API",
                automationName           : "API Automation",
                companyUserGroupId       : "TENANT_A",
                createdByUserId          : "tester",
                inputModeEnumId          : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId               : "RS_ORDER",
                savedRunType             : "ruleset",
                ruleSetId                : "RS_ORDER",
                compareScopeId           : "SCOPE_ORDER",
                relativeWindowTypeEnumId : AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount      : 1,
                windowTimeZone           : "UTC",
                isActive                 : "Y",
        ])
        [AutomationExecutionSupport.FILE_SIDE_1, AutomationExecutionSupport.FILE_SIDE_2].each { String fileSide ->
            ec.entity.add("darpan.reconciliation.ReconciliationAutomationSource", [
                    automationId          : "AUTO_API",
                    fileSide              : fileSide,
                    companyUserGroupId    : "TENANT_A",
                    sourceTypeEnumId      : AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                    systemEnumId          : fileSide == "FILE_1" ? "SHOPIFY" : "OMS",
                    fileTypeEnumId        : "DftJson",
                    schemaFileName        : "${fileSide}.schema.json".toString(),
                    dateFromParameterName : "updatedFrom",
                    dateToParameterName   : "updatedTo",
                    safeMetadataJson      : '{"extractServiceName":"fixture.extractSource"}',
            ])
        }
    }

    private static Timestamp timestamp(String instantText) {
        return Timestamp.from(Instant.parse(instantText))
    }

    private static class FakeEc {
        FakeEntityFacade entity
        FakeServiceFacade service
        FakeTransactionFacade transaction
        Object resource
        Object user
    }

    private static class FakeEntityFacade {
        Map<String, List<FakeValue>> rows = [:].withDefault { [] }
        int automationExecutionSeq = 1
        int runResultSeq = 1

        FakeFind find(String entityName) {
            return new FakeFind(entity: this, entityName: entityName)
        }

        FakeValue makeValue(String entityName) {
            return new FakeValue([:], entityName, this)
        }

        void add(String entityName, Map fields) {
            rows[entityName] << new FakeValue(fields, entityName, this)
        }

        List<FakeValue> createdValues(String entityName) {
            return rows[entityName].findAll { it.@created }
        }
    }

    private static class FakeFind {
        FakeEntityFacade entity
        String entityName
        Map<String, Object> conditions = [:]

        FakeFind condition(String fieldName, Object value) {
            conditions[fieldName] = value
            return this
        }

        FakeFind disableAuthz() { return this }

        FakeFind useCache(boolean ignored) { return this }

        FakeValue one() {
            return list().find()
        }

        List<FakeValue> list() {
            return entity.rows[entityName].findAll { value ->
                conditions.every { fieldName, expected ->
                    value[fieldName] == expected
                }
            }
        }
    }

    private static class FakeValue extends LinkedHashMap<String, Object> {
        String entityName
        FakeEntityFacade entity
        boolean created
        boolean updated

        FakeValue(Map fields = [:], String entityName = null, FakeEntityFacade entity = null) {
            super(fields)
            this.entityName = entityName
            this.entity = entity
        }

        FakeValue set(String fieldName, Object value) {
            put(fieldName, value)
            return this
        }

        FakeValue setSequencedIdPrimary() {
            if (entityName == "darpan.reconciliation.ReconciliationAutomationExecution") {
                put("automationExecutionId", "AUTO_EXEC_${entity.automationExecutionSeq++}".toString())
            } else if (entityName == "darpan.reconciliation.ReconciliationRunResult") {
                put("reconciliationRunResultId", "RUN_RESULT_${entity.runResultSeq++}".toString())
            }
            return this
        }

        FakeValue create() {
            created = true
            entity.rows[entityName] << this
            return this
        }

        FakeValue update() {
            updated = true
            return this
        }
    }

    private static class FakeServiceFacade {
        Closure responder = { FakeServiceCall ignored -> [:] }
        List<FakeServiceCall> calls = []

        FakeServiceCall sync() {
            return new FakeServiceCall(service: this)
        }
    }

    private static class FakeServiceCall {
        FakeServiceFacade service
        String serviceName
        Map<String, Object> params = [:]

        FakeServiceCall name(String serviceName) {
            this.serviceName = serviceName
            return this
        }

        FakeServiceCall parameters(Map<String, Object> params) {
            this.params = params
            return this
        }

        FakeServiceCall disableAuthz() { return this }

        Map<String, Object> call() {
            service.calls << this
            return (service.responder.call(this) ?: [:]) as Map<String, Object>
        }
    }

    private static class FakeTransactionFacade {
        Object runUseOrBegin(Integer timeout, String message, Closure work) {
            return work.call()
        }
    }
}
