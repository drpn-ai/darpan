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
        ec.entity.add("darpan.reconciliation.TenantNotificationSetting", [
                companyUserGroupId   : "TENANT_A",
                createdByUserId      : "tester",
                googleChatWebhookUrl : webhookUrl,
                isActive             : "Y",
                createdDate          : NOW,
                lastUpdatedDate      : NOW,
        ])
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
        List<String> lines = ["Darpan run completed: ${runName}".toString()]
        if (tenantLabel) lines << "Tenant: ${tenantLabel}".toString()
        if (resultId) lines << "Result ID: ${resultId}".toString()
        if (resultUrl) lines << "Run result: <${resultUrl}|Open run result>".toString()
        lines << "Differences: ${displayCount(params.differenceCount)}".toString()
        lines << "Only in ${file1SystemLabel ?: "File 1"}: ${displayCount(params.onlyInFile1Count)}".toString()
        lines << "Only in ${file2SystemLabel ?: "File 2"}: ${displayCount(params.onlyInFile2Count)}".toString()
        return [payload: [text: lines.join("\n")]]
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
        Map<String, Integer> listCounts = [:].withDefault { 0 }
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
                    value[fieldName] == expected
                }
            }
            return maxRows != null ? matchedRows.take(maxRows) : matchedRows
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
