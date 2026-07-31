package darpan.reconciliation.automation

import darpan.reconciliation.notification.TenantNotificationSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class AutomationExecutionSupportTests {
    // Provide an explicit app base URL. In production this comes from DARPAN_APP_BASE_URL env or
    // the darpan.app.baseUrl resource property; audit H11.2 removed the hardcoded dev fallback
    // (https://hotwax-darpan-dev.web.app) so tests must set their own.
    static {
        System.setProperty("darpan.app.baseUrl", "https://hotwax-darpan-dev.web.app")
    }

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
    void customWindowSpanBeyondDefaultMaxIsRejected() {
        // HIGH gap 2: a caller-supplied 1970..9999 custom range would otherwise emit ~120k month
        // segments. resolveWindows must throw before any execution rows are produced.
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_CUSTOM,
                windowTimeZone          : "UTC",
        ]
        try {
            AutomationExecutionSupport.resolveWindows(automation, [
                    windowStartDate: timestamp("1970-01-01T00:00:00Z"),
                    windowEndDate  : timestamp("9999-12-31T00:00:00Z"),
            ])
            throw new AssertionError("Expected IllegalArgumentException for an oversized custom window span")
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.message.contains("exceeds the maximum"), "Unexpected message: ${expected.message}")
        }
    }

    @Test
    void customWindowSpanWithinMaxIsAccepted() {
        // A normal one-year-ish window (under the 366-day default) must still resolve successfully.
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_CUSTOM,
                windowTimeZone          : "UTC",
        ]
        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(automation, [
                windowStartDate: timestamp("2026-01-01T00:00:00Z"),
                windowEndDate  : timestamp("2026-06-01T00:00:00Z"),
        ])
        assertTrue(windows.size() > 0)
    }

    @Test
    void windowSpanDosCapDoesNotUseOperationalMaxWindowDays() {
        // HIGH gap 2 (reworked): `maxWindowDays` is the OPERATIONAL window default (entity default=28),
        // NOT a security cap. An automation carrying the REAL entity default of 28 must still ACCEPT a
        // normal 31-day calendar-month window (Jan 1 .. Feb 1 = 31 days). The earlier attempt used
        // maxWindowDays as the DoS guard and wrongly rejected this legitimate monthly window.
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_CUSTOM,
                windowTimeZone          : "UTC",
                maxWindowDays           : 28,
        ]
        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(automation, [
                windowStartDate: timestamp("2026-01-01T00:00:00Z"),
                windowEndDate  : timestamp("2026-02-01T00:00:00Z"),
        ])
        assertEquals(1, windows.size())
        assertEquals(timestamp("2026-01-01T00:00:00Z"), windows[0].childWindowStartDate)
        assertEquals(timestamp("2026-02-01T00:00:00Z"), windows[0].childWindowEndDate)
    }

    @Test
    void windowSpanDosCapRejectsMultiMillionDaySpanEvenWithOperationalDefault() {
        // The pathological 1970..9999 (~2.9M-day) range must still be REJECTED by the hard sanity
        // ceiling, even when the automation carries the operational maxWindowDays default of 28.
        Map automation = [
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_CUSTOM,
                windowTimeZone          : "UTC",
                maxWindowDays           : 28,
        ]
        try {
            AutomationExecutionSupport.resolveWindows(automation, [
                    windowStartDate: timestamp("1970-01-01T00:00:00Z"),
                    windowEndDate  : timestamp("9999-12-31T00:00:00Z"),
            ])
            throw new AssertionError("Expected IllegalArgumentException for a multi-million-day window span")
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.message.contains("exceeds the maximum of ${AutomationExecutionSupport.MAX_WINDOW_SPAN_DAYS}"),
                    "Unexpected message: ${expected.message}")
        }
    }

    @Test
    void apiExecutionCreatesIdempotentRowsAndCallsConfiguredSourceServices() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
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

        FakeServiceCall file1Extract = ec.service.calls.find { it.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE && it.params.fileSide == "FILE_1" }
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
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
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
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
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
    void resolveSourceExtractorMetadataResolvesArbitrarySystemFromRegistry() {
        // Phase 2 (DAR-294): a brand-new systemEnumId with NO OMS/SHOPIFY code branch must resolve its
        // extractor + config binding purely from a SourceSystemConnector row (config over code).
        FakeEc ec = fakeEc()
        ec.entity.add(SourceSystemConnectorSupport.ENTITY_NAME, [
                systemEnumId         : "ACME",
                extractServiceName   : "reconciliation.AcmeExtractionServices.extract#AcmeOrders",
                dateFromParameterName: "windowStart",
                dateToParameterName  : "windowEnd",
                configParameterName  : "acmeConfigId",
                configEntityName     : "darpan.acme.AcmeConfig",
                enabled              : "Y",
        ])
        ec.entity.add("darpan.acme.AcmeConfig", [
                acmeConfigId      : "ACME_1",
                companyUserGroupId: "TENANT_A",
                isActive          : "Y",
                canReadOrders     : "Y",
        ])
        Map<String, Object> source = [
                systemEnumId      : "ACME",
                companyUserGroupId: "TENANT_A",
        ]

        Map<String, Object> metadata = AutomationExecutionSupport.resolveSourceExtractorMetadata(ec, source, [:])

        assertEquals("reconciliation.AcmeExtractionServices.extract#AcmeOrders", metadata.extractServiceName)
        assertEquals("ACME_1", ((Map) metadata.parameters).acmeConfigId)
    }

    @Test
    void apiExecutionReusesSingleActiveSourceConfigDiscoveryAcrossLegacySources() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"].each { FakeValue source ->
            source.systemEnumId = "SHOPIFY"
            source.remove("safeMetadataJson")
            source.remove("dateFromParameterName")
            source.remove("dateToParameterName")
        }
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
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 7,
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
        assertEquals(2, ec.service.calls.count {
            it.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE
        })
        assertEquals(1, ec.entity.listCount("darpan.shopify.ShopifyAuthConfig"))
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
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        FakeValue notifyAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_API"
        }
        notifyAutomation.put("chatSpaceId", "CS_OPS")
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
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
    void automationFailureNotifiesWithFailedStatus() {
        // Task 7: a run-result row IS minted (persist succeeds) but the SUCCESS-path execution-row write
        // that follows fails — mintedRunResultId must still be visible in the catch so the NEW failure-path
        // notify fires with STATUS_FAILED exactly once. (The success-path notify at line ~261 is never
        // reached here, so this is the only delivery — no double-notify / CAS burn risk.)
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add("moqui.security.UserGroup", [
                userGroupId    : "TENANT_A",
                groupTypeEnumId: "UgtDarpanCompany",
                description    : "Tenant A",
        ])
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        FakeValue notifyAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_API"
        }
        notifyAutomation.put("chatSpaceId", "CS_OPS")
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
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
        // Simulate a transient write failure on the execution row's SUCCESS-fields update, strictly AFTER
        // persistAutomationRunResult already committed the run-result row.
        ec.entity.updateHook = { FakeValue value ->
            if (value.entityName == "darpan.reconciliation.ReconciliationAutomationExecution" &&
                    value.statusEnumId == AutomationExecutionSupport.STATUS_SUCCEEDED) {
                throw new IllegalArgumentException("execution status write failed")
            }
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

            assertEquals(1, result.failedCount)
            assertEquals(1, ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult").size())
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_FAILED, execution.statusEnumId)
            assertEquals(1, deliveries.size())
            assertEquals(webhookUrl, deliveries[0].webhookUrl)
            String text = deliveries[0].payload.text as String
            assertTrue(text.contains("Status: FAILED"))
            assertTrue(text.contains("execution status write failed"))
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void noDataRunDoesNotNotify() {
        // Task 7: NO_DATA never mints a run-result row, so the failure/success notify wiring must stay
        // silent even when the automation has a configured chatSpaceId.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add("moqui.security.UserGroup", [
                userGroupId    : "TENANT_A",
                groupTypeEnumId: "UgtDarpanCompany",
                description    : "Tenant A",
        ])
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        FakeValue notifyAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_API"
        }
        notifyAutomation.put("chatSpaceId", "CS_OPS")
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                return [
                        dataAvailable: call.params.fileSide == "FILE_1",
                        fileLocation : call.params.fileSide == "FILE_1" ? "runtime://tmp/file1.json" : null,
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : call.params.fileSide == "FILE_1" ? 10 : 0,
                ]
            }
            throw new IllegalStateException("Reconcile should not run for no-data windows")
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

            assertEquals(1, result.noDataCount)
            assertTrue(deliveries.isEmpty())
            assertEquals(0, ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult").size())
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void notifiesAutomationSpaceAndSubscriberSpacesDeduped() {
        // Task 6 fan-out: the automation's own chat space (CS_OPS) plus notify-me subscriber spaces
        // (CS_ME via USER_A, CS_OPS again via USER_B) must be deduped into exactly 2 deliveries.
        FakeEc ec = fakeEc()
        String opsWebhookUrl = "https://chat.googleapis.com/v1/spaces/OPS_SPACE/messages?key=test-key&token=test-token"
        String meWebhookUrl = "https://chat.googleapis.com/v1/spaces/ME_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: opsWebhookUrl,
                isActive            : "Y",
        ])
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Me",
                googleChatWebhookUrl: meWebhookUrl,
                isActive            : "Y",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "RUN_RESULT_1",
                companyUserGroupId       : "TENANT_A",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: "RUN_RESULT_1",
                userId                   : "USER_A",
                chatSpaceId              : "CS_ME",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: "RUN_RESULT_1",
                userId                   : "USER_B",
                chatSpaceId              : "CS_OPS",
        ])
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map<String, Object> result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
                    chatSpaceId              : "CS_OPS",
                    runName                  : "API Automation",
            ])

            assertEquals(2, deliveries.size())
            Set<String> deliveredUrls = deliveries*.webhookUrl as Set<String>
            assertTrue(deliveredUrls.contains(opsWebhookUrl))
            assertTrue(deliveredUrls.contains(meWebhookUrl))
            assertTrue((boolean) result.ok)
            assertEquals(2, result.deliveredCount)
            assertEquals(0, result.failedCount)

            // Final-review fix, finding 1: both subscription rows for this run must be purged once
            // the run is notified — otherwise they linger forever (permanent TenantChatSpace inUse,
            // stale mySubscription:true, unbounded growth).
            assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
                    .findAll { it.reconciliationRunResultId == "RUN_RESULT_1" }.isEmpty())
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void skipsWhenAlreadyNotified() {
        // Dedupe guard: a run-result row that already carries notifiedDate must be skipped entirely —
        // no destinations resolved, no deliveries attempted.
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "RUN_RESULT_1",
                companyUserGroupId       : "TENANT_A",
                notifiedDate             : NOW,
        ])
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map<String, Object> result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
            ])

            assertEquals("ALREADY_NOTIFIED", result.skippedReason)
            assertFalse((boolean) result.attempted)
            assertTrue(deliveries.isEmpty())
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void inactiveOrForeignSpacesAreDropped() {
        // A subscription pointing at an inactive space and one at a space owned by another tenant must
        // both be dropped — zero deliveries — but notifiedDate is still stamped (the run WAS processed;
        // there was simply nothing valid to deliver to).
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_INACTIVE",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Inactive",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/INACTIVE_SPACE/messages?key=test-key&token=test-token",
                isActive            : "N",
        ])
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_FOREIGN",
                companyUserGroupId  : "TENANT_B",
                spaceName           : "Foreign",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/FOREIGN_SPACE/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "RUN_RESULT_1",
                companyUserGroupId       : "TENANT_A",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: "RUN_RESULT_1",
                userId                   : "USER_A",
                chatSpaceId              : "CS_INACTIVE",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: "RUN_RESULT_1",
                userId                   : "USER_B",
                chatSpaceId              : "CS_FOREIGN",
        ])
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map<String, Object> result
        try {
            result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
            ])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals("NO_DESTINATIONS", result.skippedReason)
        assertTrue(deliveries.isEmpty())
        FakeValue runResultRow = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"].find {
            it.reconciliationRunResultId == "RUN_RESULT_1"
        }
        assertNotNull(runResultRow.notifiedDate)

        // Final-review fix, finding 1: even though neither subscription resolved to a deliverable
        // space, the claim still succeeded (NO_DESTINATIONS, not ALREADY_NOTIFIED) — so both stale
        // rows must be purged, not left behind forever.
        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
                .findAll { it.reconciliationRunResultId == "RUN_RESULT_1" }.isEmpty())
    }

    @Test
    void deliveryFailureIsWarnOnly() {
        // A hard delivery failure (non-2xx) must be recorded as a failed count, not propagate as an
        // exception — notification is best-effort and must never break the run it is reporting on.
        FakeEc ec = fakeEc()
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/FAIL_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "RUN_RESULT_1",
                companyUserGroupId       : "TENANT_A",
        ])
        // Final-review fix, finding 1: a subscriber on the SAME space the delivery will fail against —
        // the claim is still consumed (the row transitions past "notify attempted"), so the
        // subscription row must be purged even though delivery itself failed.
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: "RUN_RESULT_1",
                userId                   : "USER_A",
                chatSpaceId              : "CS_OPS",
        ])
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            return [ok: false, statusCode: 500]
        }

        Map<String, Object> result
        try {
            result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
                    chatSpaceId              : "CS_OPS",
            ])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertFalse((boolean) result.ok)
        assertTrue((boolean) result.attempted)
        assertEquals(1, result.failedCount)
        assertEquals(0, result.deliveredCount)
        FakeValue runResultRow = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"].find {
            it.reconciliationRunResultId == "RUN_RESULT_1"
        }
        assertNotNull(runResultRow.notifiedDate)
        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
                .findAll { it.reconciliationRunResultId == "RUN_RESULT_1" }.isEmpty())
    }

    @Test
    void automationLinkedSpaceDropIsWarnedAndSubscriberDeliveryStillHappens() {
        // Final-review fix, finding 3: the spec promised a warn log when the AUTOMATION's own linked
        // space is dropped (deactivated here); functionally this must exclude CS_OPS from deliveries
        // while the unrelated subscriber space CS_ME still gets notified normally. (The warn log
        // itself is not asserted here — no log-capture harness exists in this test suite yet — but
        // the code path that triggers it is exercised: automationChatSpaceId resolves to a real,
        // inactive space, distinct from the "space simply not found" case already covered by
        // inactiveOrForeignSpacesAreDropped, which uses a subscriber-only chatSpaceId.)
        FakeEc ec = fakeEc()
        String meWebhookUrl = "https://chat.googleapis.com/v1/spaces/ME_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/OPS_SPACE/messages?key=test-key&token=test-token",
                isActive            : "N",
        ])
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Me",
                googleChatWebhookUrl: meWebhookUrl,
                isActive            : "Y",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "RUN_RESULT_1",
                companyUserGroupId       : "TENANT_A",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: "RUN_RESULT_1",
                userId                   : "USER_A",
                chatSpaceId              : "CS_ME",
        ])
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map<String, Object> result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
                    chatSpaceId              : "CS_OPS",
            ])

            assertEquals(1, deliveries.size())
            assertEquals(meWebhookUrl, deliveries[0].webhookUrl)
            assertTrue((boolean) result.ok)
            assertEquals(1, result.deliveredCount)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
                .findAll { it.reconciliationRunResultId == "RUN_RESULT_1" }.isEmpty())
    }

    @Test
    void missingReconciliationRunResultIdSkipsAsNoResultId() {
        // Review fix round 1, finding 1: NO_RESULT_ID is new logic (the old single-webhook code had no
        // resultId-shaped guard at all) — a context map with no reconciliationRunResultId must skip
        // before any entity read, with zero deliveries.
        FakeEc ec = fakeEc()
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map<String, Object> result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId: "TENANT_A",
            ])

            assertEquals("NO_RESULT_ID", result.skippedReason)
            assertFalse((boolean) result.attempted)
            assertTrue(deliveries.isEmpty())
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void resultNotFoundWhenRunResultRowIsMissing() {
        // Review fix round 1, finding 1: a resultId that resolves to no row (RESULT_NOT_FOUND) must
        // skip cleanly rather than NPE or fall through to destination resolution.
        FakeEc ec = fakeEc()
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map<String, Object> result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_GHOST",
            ])

            assertEquals("RESULT_NOT_FOUND", result.skippedReason)
            assertFalse((boolean) result.attempted)
            assertTrue(deliveries.isEmpty())
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void tenantMismatchSkipsWithoutStampingNotifiedDate() {
        // Review fix round 1, finding 1: the run-result row belongs to TENANT_B but the caller's
        // context claims TENANT_A — this is precisely the cross-tenant guard call out in the review
        // ("a future refactor could silently invert or drop the comparison"). Must refuse with
        // TENANT_MISMATCH, zero deliveries, and critically must NOT stamp notifiedDate on a row the
        // call was refused access to (a spoofed tenantId must not be able to burn a real dedupe stamp).
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "RUN_RESULT_1",
                companyUserGroupId       : "TENANT_B",
        ])
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map<String, Object> result
        try {
            result = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
            ])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals("TENANT_MISMATCH", result.skippedReason)
        assertFalse((boolean) result.attempted)
        assertTrue(deliveries.isEmpty())
        FakeValue runResultRow = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"].find {
            it.reconciliationRunResultId == "RUN_RESULT_1"
        }
        assertNull(runResultRow.notifiedDate)
    }

    @Test
    void secondCallForSameRunAfterDestinationsResolvedIsAlreadyNotified() {
        // Review fix round 1, finding 2: atomic claim-then-deliver dedupe. Two sequential calls for the
        // SAME run-result with a valid destination must deliver exactly once — the first call's
        // claimNotification() wins the conditional update (notifiedDate IS NULL -> set), the second
        // call's claim then matches zero rows (notifiedDate no longer null) and returns
        // ALREADY_NOTIFIED without delivering again. This is the TOCTOU guard: a plain read-then-write
        // would let both calls pass the read-only ALREADY_NOTIFIED check before either stamped.
        FakeEc ec = fakeEc()
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/OPS_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "RUN_RESULT_1",
                companyUserGroupId       : "TENANT_A",
        ])
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map<String, Object> firstResult
        Map<String, Object> secondResult
        try {
            firstResult = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
                    chatSpaceId              : "CS_OPS",
            ])
            secondResult = TenantNotificationSupport.notifyRunCompleted(ec, [
                    companyUserGroupId       : "TENANT_A",
                    reconciliationRunResultId: "RUN_RESULT_1",
                    chatSpaceId              : "CS_OPS",
            ])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals(1, deliveries.size())
        assertTrue((boolean) firstResult.ok)
        assertEquals(1, firstResult.deliveredCount)
        assertEquals("ALREADY_NOTIFIED", secondResult.skippedReason)
        assertFalse((boolean) secondResult.attempted)
    }

    @Test
    void apiExecutionRecordsNoDataWhenOneSourceIsEmpty() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
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
    void apiExecutionRejectsPersistedExtractServiceNameOutsideAllowlist() {
        // HIGH gap 6 (defense-in-depth): even if a row carries an extractServiceName outside the
        // allowlist (pre-existing data, or a write path that bypassed the save-time check), the
        // execution sink must REFUSE to invoke it with authz disabled. The run records FAILED with the
        // allowlist error, and the disallowed service is never called.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        FakeValue file1Source = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"].find {
            it.fileSide == "FILE_1"
        }
        file1Source.safeMetadataJson = '{"extractServiceName":"system.EvilService.run#Anything"}'
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                return [dataAvailable: true, fileLocation: "runtime://tmp/file.json", fileName: "file.json", recordCount: 5]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : "AUTO_API",
                scheduledFireTime: NOW,
        ])

        assertEquals(0, result.executedCount)
        assertEquals(1, result.failedCount)
        assertFalse(ec.service.calls.any { it.serviceName == "system.EvilService.run#Anything" })
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_FAILED, execution.statusEnumId)
        assertTrue(((execution.errorMessage ?: "") as String).contains("not in the allowed service list"),
                "Unexpected error message: ${execution.errorMessage}")
    }

    @Test
    void brandNewSystemDispatchesEndToEndFromRegistryWithZeroCoreChange() {
        // Whole-effort acceptance (DAR-297): a systemEnumId that exists ONLY as registry data (+ a
        // config row) resolves AND dispatches through the automation path — the registry-derived
        // allow-list auto-permits its extract service — with zero core-Groovy change.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add(SourceSystemConnectorSupport.ENTITY_NAME, [
                systemEnumId          : "ACME",
                extractServiceName    : "reconciliation.AcmeExtractionServices.extract#AcmeOrders",
                dateFromParameterName : "windowStart",
                dateToParameterName   : "windowEnd",
                configParameterName   : "acmeConfigId",
                configEntityName      : "darpan.acme.AcmeConfig",
                remoteSendServiceName : "reconciliation.AcmeExtractionServices.extract#AcmeOrders",
                preserveWindowInstants: "N",
                enabled               : "Y",
        ])
        ec.entity.add("darpan.acme.AcmeConfig", [
                acmeConfigId: "ACME_1", companyUserGroupId: "TENANT_A", isActive: "Y", canReadOrders: "Y",
        ])
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"].each { FakeValue s ->
            s.systemEnumId = "ACME"
            s.remove("safeMetadataJson")
            s.remove("dateFromParameterName")
            s.remove("dateToParameterName")
        }
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == "reconciliation.AcmeExtractionServices.extract#AcmeOrders") {
                return [dataAvailable: true, fileLocation: "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName: "${call.params.fileSide}.json".toString(), recordCount: 4]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                return [diffLocation: "reconciliation-runs/AUTO_API/20260501/result.json", diffFileName: "result.json",
                        differenceCount: 0, validationErrors: []]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(1, result.executedCount)
        FakeServiceCall acmeCall = ec.service.calls.find {
            it.serviceName == "reconciliation.AcmeExtractionServices.extract#AcmeOrders"
        }
        assertNotNull(acmeCall)
        assertEquals("ACME_1", acmeCall.params.acmeConfigId)
    }

    @Test
    void databaseAutomationDispatchResolvesExtractServiceAndCarriesSourceRowQueryId() {
        // Final-blocker regression (AUT_SRC_DB scheduled path): a scheduled automation whose input is a
        // DATABASE source must resolve the DATABASE connector's extract service AND carry the admin-chosen
        // databaseSourceQueryId taken DIRECTLY from the automation source ROW column. Before the fix,
        // dispatch resolved the config id only via safeMetadataJson.parameters (never populated for a DB
        // source) or the canReadOrders-filtered findSingleActiveConfigId (a field that does NOT exist on
        // DatabaseSourceQuery) -> null config id -> no extract service -> "no connector registered", so the
        // saved-run path worked while the scheduled path was silently dead.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        // Registry row shipped by the database-darpan component (mirrors data/DatabaseConnectorSeedData.xml).
        ec.entity.add(SourceSystemConnectorSupport.ENTITY_NAME, [
                systemEnumId            : "DATABASE",
                extractServiceName      : "reconciliation.DatabaseExtractionServices.extract#DatabaseRecords",
                dateFromParameterName   : "windowStart",
                dateToParameterName     : "windowEnd",
                expectedSourceConfigType: "DATABASE_QUERY",
                configParameterName     : "databaseSourceQueryId",
                configEntityName        : "darpan.database.DatabaseSourceQuery",
                systemAliases           : "DATABASE,DB,DAR_SYS_DATABASE",
                preserveWindowInstants  : "N",
                enabled                 : "Y",
        ])
        // Active query rows exist per side, but DatabaseSourceQuery has NO canReadOrders field, so the
        // legacy findSingleActiveConfigId lookup (which filters canReadOrders="Y") can never see them AND
        // there is more than one, so a "single active" pick could not choose the admin's query anyway.
        // The chosen id lives on the source ROW column - that is what dispatch must read.
        ec.entity.add("darpan.database.DatabaseSourceQuery", [
                databaseSourceQueryId: "DBQ_1", companyUserGroupId: "TENANT_A", isActive: "Y",
        ])
        ec.entity.add("darpan.database.DatabaseSourceQuery", [
                databaseSourceQueryId: "DBQ_2", companyUserGroupId: "TENANT_A", isActive: "Y",
        ])
        // Convert both automation sources to DATABASE sources: AUT_SRC_DB, systemEnumId DATABASE, the chosen
        // query id on the ROW column, and NO safeMetadataJson (the DB save path never enriches metadata with
        // an extractServiceName - applyApiSourceMetadataDefaults early-returns for non-API sources).
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"].each { FakeValue s ->
            s.sourceTypeEnumId = AutomationExecutionSupport.AUTOMATION_SOURCE_DB
            s.systemEnumId = "DATABASE"
            s.databaseSourceQueryId = s.fileSide == "FILE_1" ? "DBQ_1" : "DBQ_2"
            s.remove("safeMetadataJson")
            s.remove("dateFromParameterName")
            s.remove("dateToParameterName")
        }
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == "reconciliation.DatabaseExtractionServices.extract#DatabaseRecords") {
                return [dataAvailable: true, fileLocation: "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName: "${call.params.fileSide}.json".toString(), recordCount: 4]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                return [diffLocation: "reconciliation-runs/AUTO_API/20260501/result.json", diffFileName: "result.json",
                        differenceCount: 0, validationErrors: []]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(1, result.executedCount)
        List<FakeServiceCall> dbCalls = ec.service.calls.findAll {
            it.serviceName == "reconciliation.DatabaseExtractionServices.extract#DatabaseRecords"
        }
        assertEquals(2, dbCalls.size())
        FakeServiceCall file1Extract = dbCalls.find { it.params.fileSide == "FILE_1" }
        FakeServiceCall file2Extract = dbCalls.find { it.params.fileSide == "FILE_2" }
        assertNotNull(file1Extract)
        assertNotNull(file2Extract)
        // The admin-chosen query id from the SOURCE ROW column reaches the extract service: NOT null, and
        // each side carries its own configured id (not a single tenant-wide "active" pick).
        assertEquals("DBQ_1", file1Extract.params.databaseSourceQueryId)
        assertEquals("DBQ_2", file2Extract.params.databaseSourceQueryId)
    }

    @Test
    void registryConnectorPointingAtNonExtractorServiceIsRejectedByNamingGuard() {
        // Defense-in-depth: even a registry-registered service name (so it clears the allow-list) must
        // match a recognized extractor/execute shape. A connector row pointing dispatch at an internal
        // service is refused by the naming guard and never invoked.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add(SourceSystemConnectorSupport.ENTITY_NAME, [
                systemEnumId       : "EVILSYS",
                extractServiceName : "store#moqui.security.UserAccount",
                configParameterName: "evilConfigId",
                configEntityName   : "darpan.acme.AcmeConfig",
                enabled            : "Y",
        ])
        ec.entity.add("darpan.acme.AcmeConfig", [
                evilConfigId: "EVIL_1", companyUserGroupId: "TENANT_A", isActive: "Y", canReadOrders: "Y",
        ])
        FakeValue file1Source = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"].find {
            it.fileSide == "FILE_1"
        }
        file1Source.systemEnumId = "EVILSYS"
        file1Source.remove("safeMetadataJson")
        ec.service.responder = { FakeServiceCall call -> return [:] }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(1, result.failedCount)
        assertFalse(ec.service.calls.any { it.serviceName == "store#moqui.security.UserAccount" })
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_FAILED, execution.statusEnumId)
        assertTrue(((execution.errorMessage ?: "") as String).contains("does not match an allowed extractor service name pattern"),
                "Unexpected error message: ${execution.errorMessage}")
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

    @Test
    void transientFailureRequeuesExecutionToPendingWithBackoff() {
        // DAR-300: a transient extractor failure requeues the execution to PENDING with a nextRetryDate
        // (not terminal FAILED), so the scanner can re-drive it.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                throw new RuntimeException("connection reset by peer")
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId)
        assertNotNull(execution.nextRetryDate)
        assertNull(execution.completedDate)
    }

    @Test
    void permanentFailureMarksExecutionFailedWithoutRetry() {
        // DAR-300: a permanent (config/bad-input) failure is terminal — FAILED, no retry scheduled.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                throw new IllegalArgumentException("bad source configuration")
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_FAILED, execution.statusEnumId)
        assertNull(execution.nextRetryDate)
        assertNotNull(execution.completedDate)
    }

    @Test
    void reconcileMessageLevelErrorRecordsFailureNotSilentSuccess() {
        // Gorjana prod 2026-07-28: a message-level error in the compare chain does not throw — Moqui
        // sync calls short-circuit once ec.message has errors and every pipeline stage guards its
        // out-params with !ec.message.hasError(), so the compare returns an EMPTY map. The execution
        // must record the failure with the error text, never SUCCEEDED with no result fields.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 3748,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                ec.message.addError("Compare scope SCOPE_ORDER was not found")
                return [:]
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_FAILED, execution.statusEnumId)
        assertTrue(((String) execution.errorMessage).contains("Compare scope SCOPE_ORDER was not found"))
        assertNotNull(execution.completedDate)
        assertNull(execution.resultDataManagerPath)
        // This used to assert ZERO run-result rows, as a proxy for "no silent success" — a row implied
        // success back when statusEnumId defaulted to AUT_STAT_SUCCESS. A terminal failure now mints its
        // own row, explicitly FAILED, so it can be notified at all (notifyRunCompleted is keyed on this
        // entity) and so it appears in run history (ReconciliationOutputSupport.shouldListRunResultWithoutFile
        // already lists FAILED/no-file rows). The real invariant is unchanged and asserted directly:
        // a failed run must never produce a SUCCESS row.
        List runResults = ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult")
        assertEquals(1, runResults.size())
        assertEquals(AutomationExecutionSupport.STATUS_FAILED, runResults[0].statusEnumId)
        assertNull(runResults[0].resultDataManagerPath)
        // The accumulated errors are consumed so later windows/automations in the same scan are not
        // short-circuited by leftover message-facade state.
        assertFalse(ec.message.hasError())
    }

    @Test
    void emptyReconcileResultWithoutErrorIsRetriedNotSilentSuccess() {
        // Companion invariant: SUCCEEDED must be unrepresentable without compare output. An empty
        // reconcile result with no message error has no permanent-failure marker, so it takes the
        // transient path — requeued PENDING with a backoff, never stamped SUCCEEDED.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId)
        assertNotNull(execution.nextRetryDate)
        assertNull(execution.completedDate)
        assertEquals(0, ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult").size())
    }

    @Test
    void reprocessDueRetriesDeadLettersExhaustedExecution() {
        // DAR-300: a due retry row that has reached maxRetryCount is dead-lettered, not re-driven.
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: "EXEC_DL",
                automationId         : "AUTO_X",
                companyUserGroupId   : "TENANT_A",
                statusEnumId         : AutomationExecutionSupport.STATUS_PENDING,
                retryCount           : 3,
                maxRetryCount        : 3,
                scheduledDate        : NOW,
                nextRetryDate        : timestamp("2026-04-30T00:00:00Z"),
        ])

        AutomationExecutionSupport.reprocessDueRetries(ec, NOW, 100, [:])

        FakeValue row = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"].find {
            it.automationExecutionId == "EXEC_DL"
        }
        assertEquals(AutomationExecutionSupport.STATUS_DEAD_LETTER, row.statusEnumId)
    }

    @Test
    void reprocessDueRetriesClaimsAndRedrivesDueExecution() {
        // DAR-300: a due retry row under the cap advances retryCount + clears nextRetryDate (claimed) and
        // is re-driven through the normal execute path.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: "EXEC_RETRY",
                automationId         : "AUTO_API",
                companyUserGroupId   : "TENANT_A",
                statusEnumId         : AutomationExecutionSupport.STATUS_PENDING,
                retryCount           : 0,
                maxRetryCount        : 3,
                scheduledDate        : NOW,
                nextRetryDate        : timestamp("2026-04-30T00:00:00Z"),
        ])
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                return [dataAvailable: true, fileLocation: "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName: "${call.params.fileSide}.json".toString(), recordCount: 3]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                return [diffLocation: "reconciliation-runs/AUTO_API/x/result.json", diffFileName: "result.json",
                        differenceCount: 0, validationErrors: []]
            }
            return [:]
        }

        AutomationExecutionSupport.reprocessDueRetries(ec, NOW, 100, [:])

        FakeValue row = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"].find {
            it.automationExecutionId == "EXEC_RETRY"
        }
        assertEquals(1, row.retryCount)
        assertNotNull(row.nextRetryDate)
        assertTrue(((Timestamp) row.nextRetryDate).after(NOW))
    }

    @Test
    void reprocessAutomationExecutionResetsFailedRowForRetry() {
        // DAR-300 item 5: operator re-drive resets a FAILED/dead-lettered row to PENDING with a fresh
        // retry budget + nextRetryDate=now so the scanner runs it again.
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: "EXEC_FAIL",
                automationId         : "AUTO_X",
                companyUserGroupId   : "TENANT_A",
                statusEnumId         : AutomationExecutionSupport.STATUS_DEAD_LETTER,
                retryCount           : 3,
                completedDate        : NOW,
                errorMessage         : "boom",
        ])

        Map result = AutomationExecutionSupport.reprocessAutomationExecution(ec, [automationExecutionId: "EXEC_FAIL"])

        assertTrue((boolean) result.requeued)
        FakeValue row = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"].find {
            it.automationExecutionId == "EXEC_FAIL"
        }
        assertEquals(AutomationExecutionSupport.STATUS_PENDING, row.statusEnumId)
        assertEquals(0, row.retryCount)
        assertNotNull(row.nextRetryDate)
    }

    @Test
    void reprocessAutomationExecutionRejectsNonTerminalRow() {
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: "EXEC_RUN",
                automationId         : "AUTO_X",
                companyUserGroupId   : "TENANT_A",
                statusEnumId         : AutomationExecutionSupport.STATUS_RUNNING,
        ])

        Map result = AutomationExecutionSupport.reprocessAutomationExecution(ec, [automationExecutionId: "EXEC_RUN"])

        assertFalse((boolean) result.requeued)
        assertNotNull(result.error)
        FakeValue row = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"].find {
            it.automationExecutionId == "EXEC_RUN"
        }
        assertEquals(AutomationExecutionSupport.STATUS_RUNNING, row.statusEnumId)
    }

    @Test
    void refusesExecutionWriteWhenAutomationHasNoTenant() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        FakeValue automation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }
        automation.put("companyUserGroupId", "")

        IllegalStateException ex = assertThrows(IllegalStateException) {
            AutomationExecutionSupport.executeAutomation(ec, [
                    automationId     : "AUTO_API",
                    scheduledFireTime: NOW,
            ])
        }
        assertTrue(ex.message.contains("companyUserGroupId"))
        assertEquals(0, ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution").size())
        assertEquals(0, ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult").size())
    }

    @Test
    void refusesExecutionWriteWhenAutomationTenantGroupDoesNotExist() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        FakeValue automation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }
        automation.put("companyUserGroupId", "TENANT_GHOST")
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"].each { FakeValue source ->
            source.put("companyUserGroupId", "TENANT_GHOST")
        }

        IllegalStateException ex = assertThrows(IllegalStateException) {
            AutomationExecutionSupport.executeAutomation(ec, [
                    automationId     : "AUTO_API",
                    scheduledFireTime: NOW,
            ])
        }
        assertTrue(ex.message.contains("TENANT_GHOST"))
        assertEquals(0, ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution").size())
    }

    @Test
    void assertSystemWriteTenantReturnsValidatedTenant() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        def automation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }

        assertEquals("TENANT_A", AutomationExecutionSupport.assertSystemWriteTenant(ec, automation))
    }

    private static FakeEc fakeEc() {
        FakeEc ec = new FakeEc(
                entity: new FakeEntityFacade(),
                service: new FakeServiceFacade(),
                transaction: new FakeTransactionFacade(),
                message: new FakeMessageFacade(),
                resource: new Expando(properties: [:]),
                user: new Expando(nowTimestamp: NOW, userId: "tester"),
        )
        ec.service.ec = ec
        // System-write tenant assertion (MACH P0): the write paths validate the automation's
        // companyUserGroupId against moqui.security.UserGroup before stamping rows, so the
        // base fixture registers the standard test tenant as an existing group.
        ec.entity.add("moqui.security.UserGroup", [
                userGroupId    : "TENANT_A",
                groupTypeEnumId: "UgtDarpanCompany",
                description    : "Tenant A",
        ])
        return ec
    }

    private static void seedApiAutomation(FakeEc ec) {
        // Registry rows the dispatch path now reads (mirrors data/SourceSystemConnectorSeedData.xml).
        ec.entity.add(SourceSystemConnectorSupport.ENTITY_NAME, [
                systemEnumId            : "OMS",
                extractServiceName      : AutomationExecutionSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                dateFromParameterName   : "windowStart",
                dateToParameterName     : "windowEnd",
                expectedSourceConfigType: "HOTWAX_OMS_REST",
                configParameterName     : "omsRestSourceConfigId",
                configEntityName        : darpan.common.DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG,
                remoteSendServiceName   : AutomationExecutionSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                preserveWindowInstants  : "N",
                enabled                 : "Y",
        ])
        ec.entity.add(SourceSystemConnectorSupport.ENTITY_NAME, [
                systemEnumId            : "SHOPIFY",
                extractServiceName      : AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE,
                dateFromParameterName   : "windowStart",
                dateToParameterName     : "windowEnd",
                expectedSourceConfigType: "SHOPIFY_AUTH",
                configParameterName     : "shopifyAuthConfigId",
                configEntityName        : darpan.common.DarpanEntityConstants.SHOPIFY_AUTH_CONFIG,
                remoteSendServiceName   : AutomationExecutionSupport.SHOPIFY_GRAPHQL_EXECUTE_SERVICE,
                preserveWindowInstants  : "Y",
                enabled                 : "Y",
        ])
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
                    // HIGH gap 6 (defense-in-depth): the execution sink now re-checks extractServiceName
                    // against the allowlist on every run, so the fixture must use a real allowed service
                    // name (the same value the legitimate save path produces) rather than an arbitrary one.
                    safeMetadataJson      : ('{"extractServiceName":"' + AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE + '"}'),
            ])
        }
    }

    private static Timestamp timestamp(String instantText) {
        return Timestamp.from(Instant.parse(instantText))
    }

    private static Map<String, Object> buildNotificationPayload(FakeEc ec, Map<String, Object> params) {
        String tenantLabel = ((params.companyLabel)?.toString()?.trim()) ?:
                darpan.facade.common.TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, params.companyUserGroupId)
        String runName = ((params.runName)?.toString()?.trim()) ?:
                ((params.savedRunId)?.toString()?.trim()) ?:
                ((params.reconciliationRunId)?.toString()?.trim()) ?:
                "reconciliation run"
        String resultId = ((params.reconciliationRunResultId)?.toString()?.trim())
        String resultUrl = TenantNotificationSupport.buildRunResultUrl(ec, params)
        String file1SystemLabel = TenantNotificationSupport.resolveFileSystemLabel(ec, params, "file1", null)
        String file2SystemLabel = TenantNotificationSupport.resolveFileSystemLabel(ec, params, "file2", null)
        Closure<String> displayCount = { Object value ->
            value == null ? "0" :
                    value instanceof Number ? ((Number) value).intValue().toString() :
                            (((value)?.toString()?.trim()) ?: "0")
        }
        // Task 7: mirror the real build#RunCompletedPayload template's failure rendering (see
        // ReconciliationNotificationServices.xml) so failure-path tests can assert on 'Status: FAILED'.
        List<String> warningList = (params.processingWarnings instanceof List ? (List) params.processingWarnings : [])
                .collect { ((it)?.toString()?.trim()) }.findAll { it }
        boolean runFailed = ((params.statusEnumId)?.toString()?.trim()) == AutomationExecutionSupport.STATUS_FAILED
        String headerPrefix = (runFailed || warningList) ? "Darpan run completed WITH ISSUES: " : "Darpan run completed: "
        List<String> lines = ["${headerPrefix}${runName}".toString()]
        if (runFailed) lines << "⚠ Status: FAILED — the ruleset did not fully evaluate; results may be incomplete.".toString()
        String terminationReasonValue = ((params.terminationReason)?.toString()?.trim())
        if (terminationReasonValue) lines << "⚠ ${terminationReasonValue}".toString()
        if (tenantLabel) lines << "Tenant: ${tenantLabel}".toString()
        if (resultId) lines << "Result ID: ${resultId}".toString()
        if (resultUrl) lines << "Run result: <${resultUrl}|Open run result>".toString()
        lines << "Differences: ${displayCount(params.differenceCount)}".toString()
        lines << "Only in ${file1SystemLabel ?: "File 1"}: ${displayCount(params.onlyInFile1Count)}".toString()
        lines << "Only in ${file2SystemLabel ?: "File 2"}: ${displayCount(params.onlyInFile2Count)}".toString()
        if (warningList) lines << "Warnings (${warningList.size()}): ${warningList.take(3).join('; ')}${warningList.size() > 3 ? ' …' : ''}".toString()
        return [payload: [text: lines.join("\n")]]
    }

    private static class FakeEc {
        FakeEntityFacade entity
        FakeServiceFacade service
        FakeTransactionFacade transaction
        FakeMessageFacade message
        Object resource
        Object user
    }

    private static class FakeMessageFacade {
        List<String> errors = []

        void addError(String error) { errors << error }

        boolean hasError() { return !errors.isEmpty() }

        String getErrorsString() { return errors.join("\n") }

        void clearErrors() { errors.clear() }
    }

    private static class FakeEntityFacade {
        Map<String, List<FakeValue>> rows = [:].withDefault { [] }
        Map<String, Integer> listCounts = [:].withDefault { 0 }
        int automationExecutionSeq = 1
        int runResultSeq = 1
        // Task 7: injection point for automationFailureNotifiesWithFailedStatus — lets a test simulate an
        // entity-row update failure AFTER a prior row (e.g. the run-result) already committed, so the
        // failure-path notify wiring (mintedRunResultId visible in the catch) can be exercised directly.
        Closure updateHook = null

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

        int listCount(String entityName) {
            return listCounts[entityName] ?: 0
        }
    }

    private static class FakeFind {
        FakeEntityFacade entity
        String entityName
        Map<String, Object> conditions = [:]
        Integer maxRows

        FakeFind condition(String fieldName, Object value) {
            conditions[fieldName] = value
            return this
        }

        FakeFind limit(Integer maxRows) {
            this.maxRows = maxRows
            return this
        }

        FakeFind disableAuthz() { return this }

        FakeFind useCache(boolean ignored) { return this }

        FakeValue one() {
            return list().find()
        }

        List<FakeValue> list() {
            entity.listCounts[entityName] = (entity.listCounts[entityName] ?: 0) + 1
            List<FakeValue> matchedRows = entity.rows[entityName].findAll { value ->
                conditions.every { fieldName, expected ->
                    // A null condition value means IS NULL (mirrors Moqui EntityFind semantics) — a
                    // missing/absent key on the row map already reads as null via Groovy Map.get(), so
                    // the same equality check below is IS-NULL-compatible with no special-casing.
                    value[fieldName] == expected
                }
            }
            return maxRows != null ? matchedRows.take(maxRows) : matchedRows
        }

        // Atomic claim-then-deliver support (Task 6 fix round 1): bulk-update every row matching the
        // accumulated conditions and report how many rows were touched, mirroring Moqui's
        // EntityFind.updateAll(Map) contract (long row count). Reuses list() so the null-means-IS-NULL
        // condition semantics stay identical between reads and this conditional write.
        long updateAll(Map<String, Object> fieldsToSet) {
            List<FakeValue> matchedRows = list()
            matchedRows.each { FakeValue row ->
                fieldsToSet.each { fieldName, value -> row.set(fieldName, value) }
                row.updated = true
            }
            return matchedRows.size() as long
        }
    }

    private static class FakeValue extends LinkedHashMap<String, Object> {
        String entityName
        FakeEntityFacade entity
        boolean created
        boolean updated
        boolean deleted

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
            entity?.updateHook?.call(this)
            updated = true
            return this
        }

        // Task: final-review fix, finding 1 — TenantNotificationSupport.purgeRunSubscriptions calls
        // .delete() on the EntityValue rows it already loaded; mirror real Moqui EntityValue.delete()
        // semantics (self-removes from the backing store) so the fan-out tests can assert cleanup.
        FakeValue delete() {
            entity?.rows?.get(entityName)?.remove(this)
            deleted = true
            return this
        }
    }

    private static class FakeServiceFacade {
        Closure responder = { FakeServiceCall ignored -> [:] }
        List<FakeServiceCall> calls = []
        FakeEc ec

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
            if (serviceName == "reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload") {
                return buildNotificationPayload(service.ec, params)
            }
            return (service.responder.call(this) ?: [:]) as Map<String, Object>
        }
    }

    private static class FakeTransactionFacade {
        Object runUseOrBegin(Integer timeout, String message, Closure work) {
            return work.call()
        }
    }
}
