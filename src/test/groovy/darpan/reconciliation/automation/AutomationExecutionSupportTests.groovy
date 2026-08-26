package darpan.reconciliation.automation

import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.notification.RunNotificationVoice
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.source.SourceFilterSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
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
    void stateWindowModeYieldsExactlyOneSegmentForTheScheduledDay() {
        def automation = [
                automationId            : "AUT_TO_DAILY",
                relativeWindowTypeEnumId: "AUT_WIN_STATE",
                windowTimeZone          : "UTC",
        ]
        Timestamp scheduled = timestamp("2026-08-05T06:00:00Z")

        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(
                automation, [scheduledFireTime: scheduled])

        assertEquals(1, windows.size())
        assertEquals(timestamp("2026-08-05T00:00:00Z"), windows[0].childWindowStartDate)
        assertEquals(timestamp("2026-08-06T00:00:00Z"), windows[0].childWindowEndDate)
        assertEquals(1, windows[0].sequenceNum)
    }

    @Test
    void stateWindowModeDoesNotSplitAcrossMonths() {
        def automation = [
                automationId            : "AUT_TO_DAILY",
                relativeWindowTypeEnumId: "AUT_WIN_STATE",
                relativeWindowCount     : 400,
                windowTimeZone          : "UTC",
        ]

        List<Map<String, Object>> windows = AutomationExecutionSupport.resolveWindows(
                automation, [scheduledFireTime: timestamp("2026-08-05T06:00:00Z")])

        // relativeWindowCount is meaningless in state mode and must not widen or split the window:
        // splitting a status-defined population by calendar month yields N identical diffs.
        assertEquals(1, windows.size())
    }

    @Test
    void stateWindowModeIsDetectable() {
        assertTrue(AutomationExecutionSupport.isStateWindowMode(
                [relativeWindowTypeEnumId: "AUT_WIN_STATE"]))
        assertFalse(AutomationExecutionSupport.isStateWindowMode(
                [relativeWindowTypeEnumId: "AUT_WIN_LAST_DAYS"]))
        assertFalse(AutomationExecutionSupport.isStateWindowMode([:]))
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

    /**
     * sm-darpan 2026-08-22: a KG Canada run compared 98 Dolce Vita orders against KG's Shopify
     * store. Both tenants' automations extract from the same shared HotWaxOmsRestSourceConfig, and
     * extractOmsOrders.groovy names its output folder
     * {@code resolveReconciliationRunLocation(ec, automationExecutionId ?: omsRestSourceConfigId, timestamp)}.
     * This sink never passed automationExecutionId, so every tenant sharing one OMS config fell back
     * to the SAME folder token and wrote the SAME file name (oms-orders-{from}-{thru}.json) for the
     * same window — one run's compare then read the other run's extract. The extract services have
     * always declared the parameter ("Automation execution id used to choose the default
     * data-manager output folder"); only the wire from here was missing.
     *
     * The id must come from the per-window execution row, not from the automation: executionParams
     * is built once OUTSIDE the window loop, while a split window mints one execution per child
     * window, and two child windows sharing a folder collide with each other.
     */
    @Test
    void extractorsReceiveTheExecutionIdSoOneTenantsExtractCannotLandOnAnothers() {
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
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
                        reconciliationType: "ORDER",
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 0,
                        onlyInFile1Count  : 0,
                        onlyInFile2Count  : 0,
                        validationErrors  : [],
                        processingWarnings: [],
                ]
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : "AUTO_API",
                scheduledFireTime: NOW,
        ])

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertNotNull(execution.automationExecutionId)

        List<FakeServiceCall> extracts = ec.service.calls.findAll {
            it.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE
        }
        assertEquals(2, extracts.size())
        extracts.each { FakeServiceCall extract ->
            assertEquals(execution.automationExecutionId, extract.params.automationExecutionId,
                    "${extract.params.fileSide} extract must be told which execution it belongs to, or its " +
                            "output folder falls back to the source-config id that every tenant shares")
        }
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

    // ==================================================================================================
    // Task 2b — the run-result row is minted when the execution goes RUNNING, not when it ends, so an
    // in-flight automation is something the UI can follow. Every test below reads the LIVE rows from
    // inside the first extract call, i.e. mid-run, because that is the only moment the property under
    // test is observable; asserting on the end state alone cannot tell the two designs apart.
    // ==================================================================================================

    @Test
    void activeExecutionCarriesARunResultIdWhileTheRunIsStillGoing() {
        // The exact pair the "Run now" poll waits for: an ACTIVE execution row that already names its
        // run-result row, and that row itself ACTIVE. Before Task 2b the id was written only in the same
        // update that set a terminal status, so this pair was unreachable and the redirect never fired.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        Map<String, Object> midRun = [:]
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                if (midRun.isEmpty()) midRun.putAll(snapshotLiveRun(ec))
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
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                ]
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(AutomationExecutionSupport.STATUS_RUNNING, midRun.executionStatus)
        assertNotNull(midRun.executionRunResultId, "an ACTIVE execution row must already name its run-result row")
        assertEquals(midRun.runResultId, midRun.executionRunResultId)
        assertEquals(AutomationExecutionSupport.STATUS_RUNNING, midRun.runResultStatus)
        assertEquals(NOW, midRun.runResultStartedDate)
        // The row is stamped with the automation's asserted tenant from the first write, so the
        // tenant-gated status read the UI does against it resolves for the operator who started the run.
        assertEquals("TENANT_A", midRun.runResultTenant)
    }

    @Test
    void aSuccessfulRunOwnsExactlyOneRunResultRowFromStartToFinish() {
        // Guards the regression early minting invites: mint at RUNNING, then create a SECOND row at
        // terminal. One row must carry both halves — the startedDate only the mint writes, and the
        // artifact only the terminal write produces — and the execution row must point at that row.
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
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                        onlyInFile1Count  : 1,
                        onlyInFile2Count  : 3,
                ]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(1, result.executedCount)
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size(), "one execution must own exactly one run-result row")
        FakeValue runResult = runResults[0]
        assertEquals(NOW, runResult.startedDate, "startedDate is written only by the mint at RUNNING")
        assertEquals("reconciliation-runs/AUTO_API/20260501/result.json", runResult.resultDataManagerPath,
                "the artifact is written only by the terminal update — so both halves landed on ONE row")
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, runResult.statusEnumId)
        assertNotNull(runResult.completedDate)
        assertEquals(4, runResult.differenceCount)
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(runResult.reconciliationRunResultId, execution.reconciliationRunResultId)
    }

    @Test
    void theRunResultIdSeenAtRunningIsTheSameIdSeenAtSucceeded() {
        // "An id is present at the end" was already true before Task 2b — a test asserting only that
        // passes against the broken code. What has to hold is that the id the UI captured mid-run is
        // still the run's id when it finishes: a terminal write that minted its own row would strand
        // the viewer on a run that stays RUNNING forever.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        Map<String, Object> midRun = [:]
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                if (midRun.isEmpty()) midRun.putAll(snapshotLiveRun(ec))
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
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                ]
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
        assertNotNull(midRun.executionRunResultId)
        assertEquals(midRun.executionRunResultId, execution.reconciliationRunResultId,
                "the id the UI polled at RUNNING must still be the run's id at SUCCEEDED")
        FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
                .find { it.reconciliationRunResultId == midRun.executionRunResultId }
        assertNotNull(runResult, "the id captured mid-run must still resolve to a row")
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, runResult.statusEnumId)
    }

    @Test
    void aFailedRunEndsItsMintedRowTerminalAndReusesIt() {
        // A permanent failure after the mint. The row the viewer is already watching must be the row
        // that goes FAILED — not a second row minted by the failure path, which would leave the watched
        // row RUNNING forever (and the stuck-run reaper alerting on it two hours later).
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }.put("chatSpaceId", "CS_OPS")
        Map<String, Object> midRun = [:]
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                if (midRun.isEmpty()) midRun.putAll(snapshotLiveRun(ec))
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                // "was not found" is a permanent-failure marker, so the execution goes terminal FAILED
                // instead of being requeued for retry — the path that notifies.
                throw new IllegalStateException("Compare scope SCOPE_ORDER was not found")
            }
            return [:]
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            assertEquals(1, result.failedCount)
            assertNotNull(midRun.executionRunResultId)
            List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
            assertEquals(1, runResults.size(), "the failure path must reuse the minted row, not add a second one")
            FakeValue runResult = runResults[0]
            assertEquals(midRun.executionRunResultId, runResult.reconciliationRunResultId)
            assertEquals(AutomationExecutionSupport.STATUS_FAILED, runResult.statusEnumId)
            assertNotNull(runResult.completedDate)
            assertTrue((runResult.errorMessage as String).contains("was not found"))
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_FAILED, execution.statusEnumId)
            assertEquals(runResult.reconciliationRunResultId, execution.reconciliationRunResultId)
            // Exactly one alert, claimed on that one row: two rows would mean two claimable anchors.
            assertEquals(1, deliveries.size())
            assertNotNull(runResult.notifiedDate)
            assertTrue((deliveries[0].payload.text as String).contains("Status: FAILED"))
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aRunThatProducesNoOutputFileStillLeavesNoRunningRowBehind() {
        // Compare returns counts but no artifact, so resultDataManagerPath is blank. That used to mean
        // "no run-result row at all"; now the row already exists, so a blank path must resolve to the
        // pre-minted id and still end that row terminal — a null return would strand it RUNNING.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }.put("chatSpaceId", "CS_OPS")
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
                // Counts only: requireReconcileOutput is satisfied by differenceCount, and with no
                // diffDf ensureAutomationResultArtifact writes nothing, so no path is ever resolved.
                return [reconciliationType: "ORDER", differenceCount: 4, onlyInFile1Count: 1, onlyInFile2Count: 3]
            }
            return [:]
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            assertEquals(1, result.executedCount)
            List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
            assertEquals(1, runResults.size())
            FakeValue runResult = runResults[0]
            assertNull(runResult.resultDataManagerPath, "there genuinely was no artifact to record")
            assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, runResult.statusEnumId,
                    "a fileless run must still end terminal — never left RUNNING for the reaper")
            assertNotNull(runResult.completedDate)
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
            assertEquals(runResult.reconciliationRunResultId, execution.reconciliationRunResultId)
            // Deliberate consequence, pinned so it stays deliberate: this run now has an anchor row, so
            // it notifies once. Before Task 2b it had none and was silent — the silence was an accident
            // of the missing row, not a decision that a completed run should go unreported.
            assertEquals(1, deliveries.size())
            assertNotNull(runResult.notifiedDate)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aNoDataRunPurgesTheNotifyMeSubscriptionItCanNowCollect() {
        // Review finding 1. Minting at RUNNING made automation runs subscribable for the first time
        // (subscribe#RunNotification only accepts PENDING/RUNNING), and NO_DATA is a terminal path that
        // never notifies — so nothing would ever clean up the subscription. An orphan can never fire
        // AND counts forever as chat-space usage, which permanently blocks deleting that space.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Mine",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/ME_SPACE/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                // The operator watching the live view clicks "Notify me" mid-run, which is only
                // reachable at all because the row exists and is RUNNING right now.
                subscribeMidRun(ec, "USER_A", "CS_ME")
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
            Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            assertEquals(1, result.noDataCount)
            FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
            assertEquals(AutomationExecutionSupport.STATUS_NO_DATA, runResult.statusEnumId)
            // NO_DATA stays silent — purging must not be achieved by sending a notification.
            assertTrue(deliveries.isEmpty())
            assertNull(runResult.notifiedDate)
            assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                    "a terminal run that never notifies must still purge its own subscriptions")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aRequeuedAttemptKeepsItsSubscriptionBecauseTheChainIsNotOver() {
        // Fix round 2. This started life asserting the opposite — that a transient close purges its
        // subscriptions — which silently broke the dead-letter alert: reprocessDueRetries sends the
        // give-up alert against THIS row's id, so deleting its subscriptions here drops every ad hoc
        // subscriber from it. A requeue is not a terminal outcome of the chain, so the subscription
        // must survive: either the re-drive carries it onto the next attempt's row, or the dead-letter
        // alert reaches the subscriber through this row. Both are asserted in the two tests below.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Mine",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/ME_SPACE/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                subscribeMidRun(ec, "USER_A", "CS_ME")
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            // Empty reconcile result, no message error and no permanent-failure marker: the transient
            // path, which requeues the execution to PENDING and deliberately stays quiet.
            return [:]
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId)
            assertTrue(deliveries.isEmpty(), "a requeued attempt must stay quiet")
            List<FakeValue> subscriptions = ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
            assertEquals(1, subscriptions.size(), "the chain has not ended, so the subscription must survive")
            assertEquals(execution.reconciliationRunResultId, subscriptions[0].reconciliationRunResultId,
                    "it must still sit on the row the dead-letter alert would notify against")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aDeadLetterAlertStillReachesSomeoneWhoSubscribedDuringTheFinalAttempt() {
        // Fix round 2, the regression the previous round introduced. When retries run out,
        // reprocessDueRetries sends the give-up alert against execution.reconciliationRunResultId —
        // which is the LAST attempt's row, the very row a transient close was purging. Giving up is
        // the outcome a subscriber most needs told about, and losing it is silent.
        // The automation has NO chatSpaceId of its own here, so the subscription is the ONLY way this
        // alert can be delivered at all: one delivery proves the subscriber path specifically.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        String meWebhookUrl = "https://chat.googleapis.com/v1/spaces/ME_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Mine",
                googleChatWebhookUrl: meWebhookUrl,
                isActive            : "Y",
        ])
        int attemptCount = 0
        ec.service.responder = { FakeServiceCall call ->
            // The scanner re-drives through this service in production; wiring it makes attempt 2 a
            // real run rather than a stub, so the row it mints is a real row.
            if (call.serviceName == "reconciliation.ReconciliationAutomationServices.execute#Automation") {
                return AutomationExecutionSupport.executeAutomation(ec, call.params)
            }
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                if (call.params.fileSide == "FILE_1") attemptCount++
                // Subscribed during the FINAL attempt only — the exact scenario that was being lost.
                if (attemptCount == 2) subscribeMidRun(ec, "USER_A", "CS_ME")
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            // Empty reconcile result every time: the transient path, so every attempt requeues.
            return [:]
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            // Attempt 1 — real run, transient failure, requeued.
            AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId)
            // Shorten the budget so the chain ends after one more attempt; the mechanic is identical
            // to the production default of 3.
            execution.put("maxRetryCount", 1)

            // Scanner pass 1: under the cap, so it claims and re-drives — attempt 2 runs for real and
            // mints its own row, and the operator clicks "Notify me" on it.
            AutomationExecutionSupport.reprocessDueRetries(ec, timestamp("2026-05-01T11:00:00Z"), 100, [:])
            // DAR-BE-002: the re-drive is submitted, not joined — wait for attempt 2 to actually finish
            // before asserting on what it left behind.
            ec.service.awaitAsyncCompletion()
            assertEquals(1, execution.retryCount)
            assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId)
            assertEquals(2, attemptCount, "the re-drive must be a real second attempt")
            List<FakeValue> subscriptions = ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
            assertEquals(1, subscriptions.size())
            assertEquals(execution.reconciliationRunResultId, subscriptions[0].reconciliationRunResultId)
            assertTrue(deliveries.isEmpty(), "nothing is terminal yet")

            // Scanner pass 2: retries exhausted → DEAD_LETTER, and the give-up alert goes out.
            AutomationExecutionSupport.reprocessDueRetries(ec, timestamp("2026-05-01T12:00:00Z"), 100, [:])
            ec.service.awaitAsyncCompletion()

            assertEquals(AutomationExecutionSupport.STATUS_DEAD_LETTER, execution.statusEnumId)
            assertEquals(1, deliveries.size(), "the subscriber must be told the automation gave up")
            assertEquals(meWebhookUrl, deliveries[0].webhookUrl)
            assertTrue((deliveries[0].payload.text as String).contains("Automation gave up after 1 retries"))
            assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                    "the chain is over, so the subscription must not outlive it")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aSubscriptionTakenOnAnEarlierAttemptIsNotifiedWhenTheRetryFinallySucceeds() {
        // Fix round 2, the other half of the lifecycle. "Notify me" has to mean "tell me how this run
        // ends", not "tell me how this one attempt ends" — but a subscription is anchored to ONE
        // run-result row and each attempt mints its own. The subscription must therefore ride forward
        // onto the new attempt's row, be delivered exactly once when that attempt ends, and be gone.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        String meWebhookUrl = "https://chat.googleapis.com/v1/spaces/ME_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Mine",
                googleChatWebhookUrl: meWebhookUrl,
                isActive            : "Y",
        ])
        boolean firstAttempt = true
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                // Subscribed during attempt 1 only — the attempt that never notifies.
                if (firstAttempt) subscribeMidRun(ec, "USER_A", "CS_ME")
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                if (firstAttempt) return [:]   // transient failure → requeued
                return [
                        reconciliationType: "ORDER",
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
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
            AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId)
            String abandonedRunResultId = execution.reconciliationRunResultId

            // The requeued row is PENDING, so the scanner's re-drive reuses it — attempt 2 succeeds.
            firstAttempt = false
            AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
            assertNotEquals(abandonedRunResultId, execution.reconciliationRunResultId, "attempt 2 mints its own row")
            assertEquals(2, ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"].size())
            // Exactly once, to the subscriber, for the attempt they never saw.
            assertEquals(1, deliveries.size())
            assertEquals(meWebhookUrl, deliveries[0].webhookUrl)
            assertTrue((deliveries[0].payload.text as String).startsWith("API Automation"))
            assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                    "the chain is over, so the subscription must not outlive it")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aReDrivenExecutionMintsAFreshRowSoItsCompletionStillNotifies() {
        // Review finding 2. reprocessAutomationExecution requeues a FAILED execution WITHOUT clearing
        // its reconciliationRunResultId, and that row's notifiedDate is already claimed. The mint must
        // stay unconditional: feeding the execution's existing id into beginAutomationRunResult as an
        // adopt-hint — which is exactly what RunObservability.beginRun does, and the obvious DRY move —
        // would resurrect the claimed row and the successful re-drive's alert would vanish as
        // ALREADY_NOTIFIED, with every other test still green.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }.put("chatSpaceId", "CS_OPS")
        boolean firstAttempt = true
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
                // Attempt 1 fails permanently (terminal FAILED, which notifies and burns the claim);
                // the operator re-drives it and attempt 2 succeeds.
                if (firstAttempt) throw new IllegalStateException("Compare scope SCOPE_ORDER was not found")
                return [
                        reconciliationType: "ORDER",
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
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
            Map firstResult = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])
            assertEquals(1, firstResult.failedCount)
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            String firstRunResultId = execution.reconciliationRunResultId
            assertNotNull(firstRunResultId)
            assertEquals(1, deliveries.size())
            FakeValue firstRunResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
            assertNotNull(firstRunResult.notifiedDate, "attempt 1's FAILED alert claims that row's notifiedDate")

            // Operator re-drive. Note what it does NOT do: clear reconciliationRunResultId.
            Map requeue = AutomationExecutionSupport.reprocessAutomationExecution(ec,
                    [automationExecutionId: execution.automationExecutionId])
            assertEquals(true, requeue.requeued)
            assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId)
            assertEquals(firstRunResultId, execution.reconciliationRunResultId,
                    "the stale, already-notified id is still on the execution row — this is the trap")

            firstAttempt = false
            Map secondResult = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            assertEquals(1, secondResult.executedCount, "the PENDING row must be reused and re-driven, not skipped as a duplicate")
            assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
            assertNotEquals(firstRunResultId, execution.reconciliationRunResultId,
                    "attempt 2 must mint its OWN row — adopting attempt 1's would inherit its spent notifiedDate")
            List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
            assertEquals(2, runResults.size(), "one row per attempt")
            FakeValue secondRunResult = runResults.find { it.reconciliationRunResultId == execution.reconciliationRunResultId }
            assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, secondRunResult.statusEnumId)
            assertNotNull(secondRunResult.notifiedDate)
            // The payload that would go missing under an adopt-the-old-row regression.
            assertEquals(2, deliveries.size(), "the re-drive's completion alert must not be swallowed as ALREADY_NOTIFIED")
            assertTrue((deliveries[1].payload.text as String).startsWith("API Automation"))
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    // ==================================================================================================
    // Task 6 Part A — the live progress view offers "Cancel run" for automation runs, and until now
    // nothing on the automation side read the flag that button sets: the run finished normally and
    // reported SUCCESS to an operator who believed they had stopped it.
    // ==================================================================================================

    @Test
    void aCancelRequestedMidRunStopsTheApiRunAndEndsBothRowsCancelled() {
        // The core one: the run must actually STOP (the second extract and the reconcile never happen)
        // and BOTH rows must end terminal CANCELLED. A test that only checked the end status would pass
        // against a run that ignored the cancel, ran to completion and was merely relabelled — so the
        // service-call trace is the load-bearing assertion here, not the statuses.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        List<String> extractedSides = []
        List<String> reconcileCalls = []
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                extractedSides << (call.params.fileSide as String)
                // The operator presses Cancel on the live view while the first extract is running.
                if (extractedSides.size() == 1) requestCancelMidRun(ec)
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                reconcileCalls << "reconcile"
                return [reconciliationType: "ORDER", differenceCount: 4, diffFileName: "result.json"]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(["FILE_1"], extractedSides,
                "the cancel checkpoint after extract 1 must stop the run — the second extract must never run")
        assertTrue(reconcileCalls.isEmpty(), "a cancelled run must not go on to reconcile")
        assertEquals(1, result.cancelledCount)
        assertEquals(0, result.failedCount, "a cancel is not a failure")
        assertEquals(0, result.executedCount)
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size(), "cancelling must reuse the minted row, not add a second one")
        FakeValue runResult = runResults[0]
        assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId,
                "the row the operator is watching must end CANCELLED — never left RUNNING for the reaper")
        assertNotNull(runResult.completedDate)
        assertEquals("Run cancelled by an operator.", runResult.errorMessage)
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, execution.statusEnumId,
                "the execution row must be CANCELLED too — not FAILED, and not requeued PENDING")
        assertNotNull(execution.completedDate)
        assertEquals(runResult.reconciliationRunResultId, execution.reconciliationRunResultId)
    }

    @Test
    void aCancelSurfacingAsAnExtractorFailureIsStillReportedCancelledNotFailed() {
        // Requirement 3, the precedence runSavedRunDiff.groovy:1017 encodes. An extractor can catch the
        // cancel thrown by its own progress checkpoint and re-raise its own error, so the runner sees an
        // ordinary failure with no RunCancelledException in hand. Reporting THAT as FAILED tells the
        // operator their own cancellation was a run failure — and here it also ALERTS them about it.
        // The chosen message is a permanent-failure marker, so without the outranking check this is a
        // terminal FAILED that notifies; with it, nothing is sent at all.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }.put("chatSpaceId", "CS_OPS")
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                requestCancelMidRun(ec)
                throw new IllegalStateException("Compare scope SCOPE_ORDER was not found")
            }
            return [:]
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            assertEquals(1, result.cancelledCount)
            assertEquals(0, result.failedCount)
            FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId)
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, execution.statusEnumId)
            assertTrue(deliveries.isEmpty(),
                    "a cancelled run must not send the operator a failure alert about their own cancellation")
            assertNull(runResult.notifiedDate, "and must not spend the notify claim on one either")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aCancelledApiRunPurgesTheNotifyMeSubscriptionItLeavesBehind() {
        // Cancellation is a terminal outcome that deliberately notifies nobody, so nothing else will ever
        // clean up a subscription taken while the run was RUNNING — purgeRunSubscriptions only fires off a
        // won notification claim. And unlike a transient failure there is no successor attempt to carry it
        // to: a cancel never requeues. An orphan here can never fire AND pins its chat space against
        // deletion forever. The automation has no chatSpaceId of its own, so the subscription is the only
        // route any alert could take — an empty delivery list means the silence is real.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Mine",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/ME_SPACE/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                // Notify me, then Cancel run — both only reachable because the row is live and RUNNING.
                subscribeMidRun(ec, "USER_A", "CS_ME")
                requestCancelMidRun(ec)
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
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
            AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId)
            assertTrue(deliveries.isEmpty(), "a cancelled run stays silent — the operator already knows")
            assertNull(runResult.notifiedDate)
            assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                    "the subscription must not outlive the chain — a cancel never requeues, so this is the end of it")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aCancelLandingWhileTheArtifactIsWrittenBeatsARuleExecutionFailure() {
        // Fix round 1. The API path reaches a FAILED terminal without ever throwing: a rule build/eval
        // failure is only a flag on the reconcile result. That decision sits AFTER the last cancel
        // checkpoint, on the far side of the artifact write and the run-result persist — seconds of work
        // on a large diff — so a cancel landing there is seen by no checkpoint and no catch. Unguarded,
        // the operator is written FAILED and ALERTED that their own cancellation was a run failure.
        //
        // The sequence assertion is what keeps this honest: it pins the click to AFTER the post-reconcile
        // heartbeat (and so after the checkpoint that immediately follows it). A cancel stamped one step
        // earlier would be caught by that checkpoint, and this test would be proving the throw path.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_API" }.put("chatSpaceId", "CS_OPS")
        List<String> sequence = []
        ec.entity.updateHook = { FakeValue value ->
            if (value.entityName == "darpan.reconciliation.ReconciliationRunResult" &&
                    value.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING) {
                sequence << "heartbeat"
            }
        }
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
                sequence << "reconcile"
                return [
                        reconciliationType : "ORDER",
                        diffLocation       : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName       : "result.json",
                        differenceCount    : 4,
                        // The rule set did not fully evaluate: this is the non-throwing FAILED terminal.
                        ruleExecutionFailed: true,
                ]
            }
            return [:]
        }
        ec.message.onHasError = {
            // The first message read after the reconcile is requireReconcileOutput's, one line past the
            // checkpoint and still ahead of the persist — the operator's click lands there.
            if (sequence.contains("reconcile") && !sequence.contains("cancel")) {
                sequence << "cancel"
                requestCancelMidRun(ec)
            }
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

            int cancelIndex = sequence.indexOf("cancel")
            assertEquals("heartbeat", sequence[cancelIndex - 1],
                    "the cancel must land after the post-reconcile heartbeat — i.e. past the checkpoint that follows it")
            assertEquals("reconcile", sequence[cancelIndex - 2],
                    "...and after the reconcile, or this proves a checkpoint rather than the outrank guard")
            assertEquals(1, result.cancelledCount)
            assertEquals(0, result.failedCount)
            List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
            assertEquals(1, runResults.size())
            FakeValue runResult = runResults[0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId,
                    "a rule-execution failure under a pending cancel is a cancellation, not a failure")
            FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, execution.statusEnumId)
            assertTrue(deliveries.isEmpty(),
                    "the operator must not be alerted that their own cancellation was a run failure")
            assertNull(runResult.notifiedDate)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
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
            // Routing/plumbing assertions only. Copy lives in RunNotificationVoiceTests, so this
            // suite pins tokens a copy tweak cannot move.
            assertTrue(text.startsWith("API Automation"), text)
            assertTrue(text.contains("Tenant: Tenant A"))
            assertTrue(text.contains("Result ID: RUN_RESULT_1"))
            // URL from main: the deep link now names the tenant so the app can switch into it.
            assertTrue(text.contains("Run result: <https://hotwax-darpan-dev.web.app/reconciliation/run-result/RS_ORDER/reconciliation-runs%2FAUTO_API%2F20260501%2Fresult.json?runName=API+Automation&file1SystemLabel=SHOPIFY&file2SystemLabel=OMS&tenantId=TENANT_A|Open run result>"))
            assertTrue(text.contains("Missing from "), text)
            // Original intent preserved: the system labels must RESOLVE, never fall back.
            assertFalse(text.contains("Missing from File 1"), text)
            assertFalse(text.contains("Missing from File 2"), text)
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
        // Task 7 / Task 2b: NO_DATA must stay silent even when the automation has a configured
        // chatSpaceId. Since 2b the run-result row is minted at RUNNING, so "no row" is no longer the
        // thing that keeps it quiet — the assertions below check what actually matters now: the one
        // minted row ends terminal NO_DATA, is never claimed for notification, and nothing is delivered.
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
            List<FakeValue> runResults = ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult")
            assertEquals(1, runResults.size())
            assertEquals(AutomationExecutionSupport.STATUS_NO_DATA, runResults[0].statusEnumId)
            assertNull(runResults[0].notifiedDate)
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
    void scanSubmitsAndAdvancesTheScheduleWithoutWaitingForTheExecution() {
        // DAR-BE-002: the every-5-minute ServiceJob used to dispatch async futures and then JOIN them
        // (future.get(1800s)), so a ~301s reconciliation held the whole sweep open and ticks overlapped.
        // The scan must submit, advance the schedule, and return while the execution is still running.
        FakeEc ec = fakeEc()
        addDueAutomation(ec, "AUTO_SLOW", timestamp("2026-05-01T09:00:00Z"))

        CountDownLatch executionStarted = new CountDownLatch(1)
        CountDownLatch releaseExecution = new CountDownLatch(1)
        AtomicBoolean executionFinished = new AtomicBoolean(false)
        ec.service.responder = { FakeServiceCall call ->
            executionStarted.countDown()
            releaseExecution.await(30, TimeUnit.SECONDS)
            executionFinished.set(true)
            return [executedCount: 1]
        }
        // Watchdog so a regression to joining FAILS on the assertion below instead of hanging the suite.
        startLatchWatchdog(releaseExecution, 3000L)

        Map result = AutomationExecutionSupport.scanDueAutomations(ec, [nowTimestamp: NOW, limit: 100])

        assertFalse(executionFinished.get(),
                "scan#DueAutomations must return without waiting for the execution it submitted")
        FakeValue dueAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_SLOW"
        }
        assertEquals(timestamp("2026-05-01T09:00:00Z"), dueAutomation.lastScheduledFireTime,
                "the schedule must advance at submission, not after the execution finishes")
        assertEquals(timestamp("2026-05-01T11:00:00Z"), dueAutomation.nextScheduledFireTime,
                "a still-running execution must not leave its automation due for the next tick")
        assertEquals(1, result.dueCount)
        assertTrue(executionStarted.await(10, TimeUnit.SECONDS),
                "the execution must actually be submitted, not dropped")
        releaseExecution.countDown()
    }

    @Test
    void scanDefersSubmissionWhenTheInFlightExecutionCapIsAlreadyFull() {
        // DAR-BE-002 keeps the 2026-06-11 #8 flood protection: fire-and-forget moves scheduling off the
        // scan, so the bound has to move onto in-flight state. Moqui's shared worker pool is bounded at
        // 32+ threads — far above the 4 concurrent Spark reconciliations this cap exists to allow — so
        // the scan counts RUNNING executions and defers rather than stampeding the driver.
        FakeEc ec = fakeEc()
        addDueAutomation(ec, "AUTO_DEFERRED", timestamp("2026-05-01T09:00:00Z"))
        (1..AutomationExecutionSupport.MAX_CONCURRENT_EXECUTIONS).each { int index ->
            ec.entity.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                    automationExecutionId: "EXEC_INFLIGHT_${index}".toString(),
                    automationId         : "AUTO_OTHER_${index}".toString(),
                    companyUserGroupId   : "TENANT_A",
                    statusEnumId         : AutomationExecutionSupport.STATUS_RUNNING,
                    scheduledDate        : NOW,
            ])
        }
        ec.service.responder = { FakeServiceCall call -> [executedCount: 1] }

        Map result = AutomationExecutionSupport.scanDueAutomations(ec, [nowTimestamp: NOW, limit: 100])

        assertTrue(ec.service.calls.isEmpty(),
                "no execution may be submitted while the in-flight cap is full. Submitted: ${ec.service.calls*.serviceName}")
        assertEquals(1, result.deferredCount)
        FakeValue deferred = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_DEFERRED"
        }
        assertNull(deferred.lastScheduledFireTime,
                "a deferred automation must stay due — advancing its schedule would silently drop the window")
        assertEquals(timestamp("2026-05-01T09:00:00Z"), deferred.nextScheduledFireTime)
    }

    @Test
    void aDeferredAutomationIsSubmittedOnTheNextTickOnceCapacityFrees() {
        // The deferral above must be a delay, not a loss: once the in-flight executions clear, the very
        // next 5-minute tick picks the same window up.
        FakeEc ec = fakeEc()
        addDueAutomation(ec, "AUTO_DEFERRED", timestamp("2026-05-01T09:00:00Z"))
        List<FakeValue> inFlight = (1..AutomationExecutionSupport.MAX_CONCURRENT_EXECUTIONS).collect { int index ->
            ec.entity.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                    automationExecutionId: "EXEC_INFLIGHT_${index}".toString(),
                    automationId         : "AUTO_OTHER_${index}".toString(),
                    companyUserGroupId   : "TENANT_A",
                    statusEnumId         : AutomationExecutionSupport.STATUS_RUNNING,
                    scheduledDate        : NOW,
            ])
            return ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"].last()
        }
        ec.service.responder = { FakeServiceCall call -> [executedCount: 1] }

        AutomationExecutionSupport.scanDueAutomations(ec, [nowTimestamp: NOW, limit: 100])
        inFlight.each { FakeValue row -> row.statusEnumId = AutomationExecutionSupport.STATUS_SUCCEEDED }
        Map secondTick = AutomationExecutionSupport.scanDueAutomations(ec, [
                nowTimestamp: timestamp("2026-05-01T10:05:00Z"),
                limit       : 100,
        ])

        assertEquals(0, secondTick.deferredCount)
        assertEquals(1, ec.service.calls.size())
        assertEquals("AUTO_DEFERRED", ec.service.calls[0].params.automationId)
        assertEquals(timestamp("2026-05-01T09:00:00Z"), ec.service.calls[0].params.scheduledFireTime,
                "the deferred window itself must run, not a fresher one that skips it")
    }

    @Test
    void retryRedriveIsSubmittedWithoutWaitingForTheExecution() {
        // DAR-BE-002: reprocessDueRetries ran on the same 5-minute scan and blocked on a SYNC
        // execute#Automation for every due retry, so leaving it synchronous would keep the sweep long
        // even after the scheduled path stopped joining.
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: "EXEC_RETRY_SLOW",
                automationId         : "AUTO_RETRY",
                companyUserGroupId   : "TENANT_A",
                statusEnumId         : AutomationExecutionSupport.STATUS_PENDING,
                retryCount           : 0,
                maxRetryCount        : 3,
                scheduledDate        : NOW,
                nextRetryDate        : timestamp("2026-04-30T00:00:00Z"),
        ])

        CountDownLatch executionStarted = new CountDownLatch(1)
        CountDownLatch releaseExecution = new CountDownLatch(1)
        AtomicBoolean executionFinished = new AtomicBoolean(false)
        ec.service.responder = { FakeServiceCall call ->
            executionStarted.countDown()
            releaseExecution.await(30, TimeUnit.SECONDS)
            executionFinished.set(true)
            return [executedCount: 1]
        }
        startLatchWatchdog(releaseExecution, 3000L)

        List results = AutomationExecutionSupport.reprocessDueRetries(ec, NOW, 100, [:])

        assertFalse(executionFinished.get(),
                "the retry sweep must submit the re-drive and return, not join it")
        assertEquals(1, results.size())
        FakeValue row = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"].find {
            it.automationExecutionId == "EXEC_RETRY_SLOW"
        }
        assertEquals(1, row.retryCount, "the row must still be claimed before the submission")
        assertTrue(executionStarted.await(10, TimeUnit.SECONDS),
                "the re-drive must actually be submitted, not dropped")
        releaseExecution.countDown()
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
        // Task 2b: the attempt minted a run-result row at RUNNING, so a requeue can no longer be proven
        // by "no row exists". What must hold is that the attempt's row is CLOSED (never left ACTIVE for
        // the stuck-run reaper to alert on) and was never stamped SUCCESS or notified.
        List<FakeValue> runResults = ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult")
        assertEquals(1, runResults.size())
        assertEquals(AutomationExecutionSupport.STATUS_FAILED, runResults[0].statusEnumId)
        assertNull(runResults[0].notifiedDate)
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

    @Test
    void automationExtractorReceivesTheSourcesConfiguredExclusionFilters() {
        Map<String, Object> serviceParams = AutomationExecutionSupport.applyExcludeFilterParameter(
                [:],
                [filterParameterName: "sourceFilters"],
                [[sequenceNum: 1, fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN",
                  filterValues: "POS_SALES_CHANNEL"]])

        assertEquals(1, (serviceParams.sourceFilters as List).size())
        assertEquals("salesChannelEnumId", (serviceParams.sourceFilters as List)[0].fieldExpression)
    }

    @Test
    void connectorWithoutAFilterParameterNeverReceivesFilters() {
        Map<String, Object> serviceParams = AutomationExecutionSupport.applyExcludeFilterParameter(
                [:], [filterParameterName: null],
                [[sequenceNum: 1, fieldExpression: "salesChannelEnumId", filterValues: "POS_SALES_CHANNEL"]])

        assertFalse(serviceParams.containsKey("sourceFilters"))
    }

    @Test
    void emptyFilterListLeavesTheParameterUnset() {
        Map<String, Object> serviceParams = AutomationExecutionSupport.applyExcludeFilterParameter(
                [:], [filterParameterName: "sourceFilters"], [])

        assertFalse(serviceParams.containsKey("sourceFilters"))
    }

    @Test
    void statusParameterIsPassedWhenBothConnectorAndSourceDeclareIt() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyStatusParameter(serviceParams,
                [statusParameterName: "orderStatusIds"],
                [extractStatusIds: "ORDER_CREATED, ORDER_APPROVED"])

        assertEquals(["ORDER_CREATED", "ORDER_APPROVED"], serviceParams.orderStatusIds)
    }

    @Test
    void statusParameterIsAbsentWhenTheConnectorDoesNotDeclareOne() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyStatusParameter(serviceParams, [:],
                [extractStatusIds: "ORDER_CREATED"])

        assertEquals([:], serviceParams)
    }

    @Test
    void statusParameterIsAbsentWhenTheSourceConfiguresNoStatuses() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyStatusParameter(serviceParams,
                [statusParameterName: "orderStatusIds"], [extractStatusIds: "  ,  "])

        assertEquals([:], serviceParams)
    }

    @Test
    void connectorWindowFieldNameReachesTheExtractService() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyWindowFieldParameter(
                serviceParams, [windowFieldName: "lastUpdatedTxStamp"])

        assertEquals("lastUpdatedTxStamp", serviceParams.windowFieldName)
    }

    @Test
    void blankConnectorWindowFieldNameLeavesTheParameterUnset() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyWindowFieldParameter(serviceParams, [windowFieldName: "  "])
        AutomationExecutionSupport.applyWindowFieldParameter(serviceParams, [:])

        // Unset, not empty: the extractor's own default must win, and an empty string would override it.
        assertFalse(serviceParams.containsKey("windowFieldName"))
    }

    @Test
    void windowModeDispatchStillSendsDateParameters() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyWindowParameters(serviceParams,
                [relativeWindowTypeEnumId: "AUT_WIN_LAST_DAYS"],
                [:],
                [dateFromParameterName: "windowStart", dateToParameterName: "windowEnd",
                 supportsStateExtract: false],
                [childWindowStartDate: Timestamp.valueOf("2026-08-05 00:00:00"),
                 childWindowEndDate  : Timestamp.valueOf("2026-08-06 00:00:00")])

        assertEquals(Timestamp.valueOf("2026-08-05 00:00:00"), serviceParams.windowStart)
        assertEquals(Timestamp.valueOf("2026-08-06 00:00:00"), serviceParams.windowEnd)
    }

    @Test
    void stateModeDispatchOmitsDateParametersEntirely() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyWindowParameters(serviceParams,
                [relativeWindowTypeEnumId: "AUT_WIN_STATE"],
                [:],
                [dateFromParameterName: "windowStart", dateToParameterName: "windowEnd",
                 supportsStateExtract: true],
                [childWindowStartDate: Timestamp.valueOf("2026-08-05 00:00:00"),
                 childWindowEndDate  : Timestamp.valueOf("2026-08-06 00:00:00")])

        assertFalse(serviceParams.containsKey("windowStart"))
        assertFalse(serviceParams.containsKey("windowEnd"))
    }

    @Test
    void stateModeStillSendsDatesWhenTheConnectorDoesNotSupportStateExtraction() {
        Map<String, Object> serviceParams = [:]
        AutomationExecutionSupport.applyWindowParameters(serviceParams,
                [relativeWindowTypeEnumId: "AUT_WIN_STATE"],
                [:],
                [dateFromParameterName: "windowStart", dateToParameterName: "windowEnd",
                 supportsStateExtract: false],
                [childWindowStartDate: Timestamp.valueOf("2026-08-05 00:00:00"),
                 childWindowEndDate  : Timestamp.valueOf("2026-08-06 00:00:00")])

        // Fail safe: a connector that cannot do state extraction gets the window it expects rather
        // than a silently unbounded request. Task 11 rejects this combination at save time.
        assertEquals(Timestamp.valueOf("2026-08-05 00:00:00"), serviceParams.windowStart)
    }

    @Test
    void automationSourceFiltersAreReturnedInSequenceOrder() {
        // Fix round 1: SourceFilterSupport.firstMatchingRule returns the FIRST matching rule in list
        // order, and that rule owns the excluded count (ReconciliationEntities.xml:381-382 on
        // ReconciliationAutomationSourceFilter.sequenceNum). Seed the rows through the fake OUT of
        // sequence order (sequenceNum 2 before 1) — a loader that lost its .orderBy("sequenceNum") call
        // would return them in this insertion order, [2, 1], and this test would fail.
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationSourceFilter", [
                automationId   : "AUTO_API",
                fileSide       : "FILE_1",
                sequenceNum    : 2,
                fieldExpression: "returnStatus",
                operator       : "EXCLUDE_IN",
                filterValues   : "RETURNED",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationSourceFilter", [
                automationId   : "AUTO_API",
                fileSide       : "FILE_1",
                sequenceNum    : 1,
                fieldExpression: "salesChannelEnumId",
                operator       : "EXCLUDE_IN",
                filterValues   : "POS_SALES_CHANNEL",
        ])

        List<Map<String, Object>> filters = AutomationRuntimeSupport.loadAutomationSourceFilters(ec, "AUTO_API", "FILE_1")

        assertEquals([1, 2], filters*.sequenceNum)
        assertEquals("salesChannelEnumId", filters[0].fieldExpression)
        assertEquals("returnStatus", filters[1].fieldExpression)
    }

    @Test
    void aBoardConfiguredJsonPathSnapshotActuallyExcludesARawRecordOnTheScheduledPath() {
        // FINAL-REVIEW CRITICAL 1a, scheduled half. An automation's snapshot rows are copied VERBATIM
        // from the rule set, so they carry the board's JSONPath — but the getter scans top-level record
        // keys. Without the reduction in loadAutomationSourceFilters, a scheduled run silently excludes
        // nothing while its interactive twin excludes correctly: exactly the divergence the mirrored
        // snapshot exists to prevent.
        FakeEc ec = fakeEc()
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationSourceFilter", [
                automationId   : "AUTO_API",
                fileSide       : "FILE_1",
                sequenceNum    : 1,
                fieldExpression: '$.records[*].salesChannelEnumId',
                operator       : "EXCLUDE_IN",
                filterValues   : "POS_SALES_CHANNEL",
        ])

        List<Map<String, Object>> filters = AutomationRuntimeSupport.loadAutomationSourceFilters(ec, "AUTO_API", "FILE_1")

        assertEquals("salesChannelEnumId", filters[0].fieldExpression)
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules(filters)
        assertNotNull(SourceFilterSupport.firstMatchingRule(
                [orderId: "O-1", salesChannelEnumId: "POS_SALES_CHANNEL"], parsed))
        assertNull(SourceFilterSupport.firstMatchingRule(
                [orderId: "O-2", salesChannelEnumId: "WEB_SALES_CHANNEL"], parsed))
    }

    // ==================================================================================================
    // Task 2c — Task 2b's early mint exposes an automation's run-result row to StuckRunReaper (it sweeps
    // any PENDING/RUNNING row whose lastUpdatedStamp goes stale). These heartbeat the row at automation
    // phase boundaries so a normal-length run is not falsely reaped. Boundary heartbeats give exactly the
    // same guarantee the interactive path's beginStep/endStep heartbeats give — no stronger: a run whose
    // SINGLE phase (one very large extract, or one very large reconcile) exceeds the reaper threshold on
    // its own is still reaped. That is not fixed here; it is out of scope (see the task report).
    // ==================================================================================================

    @Test
    void heartbeatDuringARunRefreshesTheRunResultRowWithoutChangingStatusOrNotifiedDate() {
        // Required test 1. A heartbeat must move lastHeartbeatDate/lastUpdatedDate forward — the write
        // that gives StuckRunReaper's lastUpdatedStamp something real to bump — while leaving statusEnumId
        // at RUNNING and notifiedDate untouched. A heartbeat is not a status change and must never look
        // like one to the notifiedDate claim-then-deliver CAS.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        Timestamp heartbeatAt = timestamp("2026-05-01T10:05:00Z")
        Map<String, Object> midRun = [:]
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                if (call.params.fileSide == "FILE_1") {
                    // Move the clock forward before the heartbeat that fires right after this call
                    // returns, so its write is observably distinct from the mint's startedDate — both
                    // otherwise read the same fixed NOW, and the assertions below could not tell
                    // "never heartbeated" apart from "heartbeated but the clock didn't move".
                    ec.user.nowTimestamp = heartbeatAt
                }
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                // Both extraction heartbeats have already fired by now; the reconcile-call heartbeat and
                // every terminal write are still ahead, so this is the live in-flight state a poll would
                // see mid-run.
                if (midRun.isEmpty()) {
                    FakeValue liveRunResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
                    midRun.statusEnumId = liveRunResult.statusEnumId
                    midRun.lastHeartbeatDate = liveRunResult.lastHeartbeatDate
                    midRun.lastUpdatedDate = liveRunResult.lastUpdatedDate
                    midRun.notifiedDate = liveRunResult.notifiedDate
                    midRun.updated = liveRunResult.@updated
                }
                return [
                        reconciliationType: "ORDER",
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                        onlyInFile1Count  : 1,
                        onlyInFile2Count  : 3,
                ]
            }
            return [:]
        }

        AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(AutomationExecutionSupport.STATUS_RUNNING, midRun.statusEnumId,
                "a heartbeat must never change statusEnumId")
        assertEquals(heartbeatAt, midRun.lastHeartbeatDate, "the heartbeat must move lastHeartbeatDate forward")
        assertEquals(heartbeatAt, midRun.lastUpdatedDate, "the heartbeat must move lastUpdatedDate forward")
        assertNull(midRun.notifiedDate, "a heartbeat must never touch notification state")
        assertTrue(midRun.updated as boolean,
                "the row must already have received a real .update() from the heartbeat, not just the mint's .create()")
    }

    @Test
    void aHeartbeatFailureDoesNotFailTheRun() {
        // Required test 2. Best-effort means best-effort: inject a throwing write at exactly the
        // heartbeat point (never the mint, which .create()s, and never the terminal close, which flips
        // statusEnumId to a terminal value in the SAME .update() call this hook is keyed on) and prove the
        // run still completes SUCCEEDED with its run-result row correctly closed.
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
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                        onlyInFile1Count  : 1,
                        onlyInFile2Count  : 3,
                ]
            }
            return [:]
        }
        int heartbeatWriteAttempts = 0
        ec.entity.updateHook = { FakeValue value ->
            if (value.entityName == "darpan.reconciliation.ReconciliationRunResult" &&
                    value.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING) {
                heartbeatWriteAttempts++
                throw new IllegalStateException("heartbeat write failed")
            }
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertTrue(heartbeatWriteAttempts > 0, "the injected failure must actually have been exercised")
        assertEquals(1, result.executedCount, "a best-effort heartbeat failure must never fail the run")
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
        FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, runResult.statusEnumId,
                "the terminal close must still land despite every heartbeat write throwing")
        assertNotNull(runResult.notifiedDate, "the completion notify must still fire despite the heartbeat failures")
    }

    @Test
    void heartbeatsFireAtEachPhaseBoundaryForATwoSourceRun() {
        // Required test 3. Guards the specific promise: one heartbeat after source-1 extraction, one
        // after source-2 extraction, one after the reconcile call — three boundaries for a two-source run,
        // in that order. A future refactor that drops one of the three heartbeatAutomationRun(...) call
        // sites must fail this test even though the run itself would still complete SUCCEEDED.
        FakeEc ec = fakeEc()
        seedApiAutomation(ec)
        List<String> events = []
        String lastStage = null
        ec.entity.updateHook = { FakeValue value ->
            // Two different writers now touch a RUNNING run-result row: heartbeatAutomationRun (clock
            // only) and RunObservability.beginStep (which also stamps currentStage as it opens a stage).
            // Discriminate on currentStage changing — a heartbeat inherits whatever stage is already on
            // the row, so only a genuine stage transition reports a new value. Every terminal close
            // flips statusEnumId in the SAME .update(), so neither branch can double-count a terminal.
            if (value.entityName == "darpan.reconciliation.ReconciliationRunResult" &&
                    value.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING) {
                String stage = value.currentStage
                if (stage != null && stage != lastStage) {
                    lastStage = stage
                    events << "stage:${stage}".toString()
                } else {
                    events << "heartbeat"
                }
            }
        }
        ec.service.responder = { FakeServiceCall call ->
            if (call.serviceName == AutomationExecutionSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE) {
                events << "extract:${call.params.fileSide}".toString()
                return [
                        dataAvailable: true,
                        fileLocation : "runtime://tmp/${call.params.fileSide}.json".toString(),
                        fileName     : "${call.params.fileSide}.json".toString(),
                        recordCount  : 5,
                ]
            }
            if (call.serviceName == "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope") {
                events << "reconcile"
                return [
                        reconciliationType: "ORDER",
                        diffLocation      : "reconciliation-runs/AUTO_API/20260501/result.json",
                        diffFileName      : "result.json",
                        differenceCount   : 4,
                        onlyInFile1Count  : 1,
                        onlyInFile2Count  : 3,
                ]
            }
            return [:]
        }

        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_API", scheduledFireTime: NOW])

        assertEquals(1, result.executedCount)
        // The three heartbeats are unchanged and still land after each extract and after the reconcile.
        // Interleaved with them are the stage transitions an operator watches on the live progress view:
        // automations previously wrote no ReconciliationRunStep rows at all, so that view rendered every
        // canonical stage as a Pending row that could never advance for the whole run.
        assertEquals(
                ["stage:RESOLVE",
                 "stage:EXTRACT_FILE1", "extract:FILE_1", "heartbeat",
                 "stage:EXTRACT_FILE2", "extract:FILE_2", "heartbeat",
                 "stage:COMPARE", "reconcile", "heartbeat",
                 "stage:WRITE_OUTPUT"],
                events,
                "expected each phase boundary to open its stage and still fire its heartbeat")

        // The stage rows themselves must exist and be closed — the live view reads these, not the
        // currentStage stamp, so a run that only moved currentStage would still show nothing.
        List steps = ec.entity.createdValues("darpan.reconciliation.ReconciliationRunStep")
        assertEquals(
                ["RESOLVE", "EXTRACT_FILE1", "EXTRACT_FILE2", "COMPARE", "WRITE_OUTPUT"],
                steps.collect { it.stageCode },
                "expected one persisted run-step row per completed phase")
        assertTrue(
                steps.every { it.statusEnumId == RunObservability.STATUS_SUCCESS },
                "expected every step of a successful run to be closed SUCCESS, not left RUNNING")
        assertEquals(5, steps.find { it.stageCode == "EXTRACT_FILE1" }.recordCount,
                "expected the extract step to carry its record count")
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

    /** An active automation on an hourly schedule whose next fire time has already passed. */
    private static void addDueAutomation(FakeEc ec, String automationId, Timestamp nextScheduledFireTime) {
        ec.entity.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId         : automationId,
                automationName       : automationId,
                companyUserGroupId   : "TENANT_A",
                inputModeEnumId      : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                scheduleExpr         : "0 0 * * * ?",
                windowTimeZone       : "UTC",
                isActive             : "Y",
                nextScheduledFireTime: nextScheduledFireTime,
        ])
    }

    /**
     * Releases a latch after a delay so a test that pins a submitted execution can never hang the suite:
     * production code that (re)gains a blocking join fails the assertion instead of stalling forever.
     */
    private static void startLatchWatchdog(CountDownLatch latch, long delayMillis) {
        Thread watchdog = new Thread({ ->
            try {
                Thread.sleep(delayMillis)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            }
            latch.countDown()
        }, "latch-watchdog")
        watchdog.daemon = true
        watchdog.start()
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

    /**
     * Review finding 1: what "Notify me" does while the live view is open — a subscription row against
     * the run-result row that is RUNNING right now. Called from an extract responder so the row it
     * points at is the real minted one, not a guessed id.
     */
    private static void subscribeMidRun(FakeEc ec, String userId, String chatSpaceId) {
        // The RUNNING row, not rows[0]: a retried automation has one row per attempt, and the live view
        // the operator is looking at is always the current attempt's.
        FakeValue liveRunResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
                .find { it.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING }
        if (liveRunResult == null) return
        String runResultId = liveRunResult.reconciliationRunResultId
        boolean already = ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
                .any { it.reconciliationRunResultId == runResultId && it.userId == userId }
        if (already) return
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: runResultId,
                userId                   : userId,
                chatSpaceId              : chatSpaceId,
        ])
    }

    /**
     * Task 6: what pressing "Cancel run" on the live progress view does — cancel#ReconciliationRun calls
     * RunObservability.requestCancel, which stamps cancelRequestedDate on the row and returns true while
     * the run is still active. Driven through the real production entry point rather than by setting the
     * field directly, so the fixture cannot drift from what the button actually writes. Called from inside
     * an extract responder so the click lands mid-run.
     */
    private static String requestCancelMidRun(FakeEc ec) {
        FakeValue liveRunResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
                .find { it.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING }
        if (liveRunResult == null) return null
        String runResultId = liveRunResult.reconciliationRunResultId
        assertTrue(RunObservability.requestCancel(ec, runResultId, "USER_A"),
                "the fixture must actually record a cancel request against the live run")
        return runResultId
    }

    /**
     * Task 2b: what a poller would see MID-RUN. Called from inside an extract responder, i.e. after the
     * execution went RUNNING and long before any terminal write, so the assertions can be about the
     * in-flight state rather than the end state (which both the old and new designs get right).
     */
    private static Map<String, Object> snapshotLiveRun(FakeEc ec) {
        FakeValue execution = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"][0]
        FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
        return [
                executionStatus     : execution?.statusEnumId,
                executionRunResultId: execution?.reconciliationRunResultId,
                runResultId         : runResult?.reconciliationRunResultId,
                runResultStatus     : runResult?.statusEnumId,
                runResultStartedDate: runResult?.startedDate,
                runResultTenant     : runResult?.companyUserGroupId,
        ] as Map<String, Object>
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
        // Delegates to the real renderer instead of reimplementing it. This fake previously built its
        // own line stack, so these suites stayed green against their own copy no matter what
        // build#RunCompletedPayload actually produced — hollow green that hid a whole redesign.
        List<String> warningList = (params.processingWarnings instanceof List ? (List) params.processingWarnings : [])
                .collect { ((it)?.toString()?.trim()) }.findAll { it }
        boolean runFailed = ((params.statusEnumId)?.toString()?.trim()) == AutomationExecutionSupport.STATUS_FAILED
        Map<String, Object> voiceModel = RunNotificationVoice.classify([
                onlyInFile1Count   : params.onlyInFile1Count,
                onlyInFile2Count   : params.onlyInFile2Count,
                ruleDifferenceCount: params.ruleDifferenceCount,
                runFailed          : runFailed,
                hasWarnings        : (warningList ? true : false),
        ]) + [runName         : runName, file1SystemLabel: file1SystemLabel,
              file2SystemLabel: file2SystemLabel, priorCleanRuns: 0, completedMoment: null]
        List<String> lines = RunNotificationVoice.renderLines(voiceModel)
        if (runFailed) lines << "⚠ Status: FAILED — the ruleset did not fully evaluate; results may be incomplete.".toString()
        String terminationReasonValue = ((params.terminationReason)?.toString()?.trim())
        if (terminationReasonValue) lines << "⚠ ${terminationReasonValue}".toString()
        if (tenantLabel) lines << "Tenant: ${tenantLabel}".toString()
        if (resultId) lines << "Result ID: ${resultId}".toString()
        if (resultUrl) lines << "Run result: <${resultUrl}|Open run result>".toString()
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
        /**
         * Task 6 fix round 1: fired on every hasError() read. requireReconcileOutput consumes message
         * errors immediately AFTER the post-reconcile cancel checkpoint and while the run-result row is
         * still RUNNING, so it is the one point a test can reach inside the window the non-throwing
         * ruleExecutionFailed outrank guard exists for.
         */
        Closure onHasError = null

        void addError(String error) { errors << error }

        boolean hasError() {
            onHasError?.call()
            return !errors.isEmpty()
        }

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
        // Task 6 exclusion filters, fix round 1: orderBy is honoured, not a no-op — see list() below.
        // Deliberately null until orderBy() is called: a test proving ordering matters must fail when the
        // production .orderBy("sequenceNum") call is removed, which only happens if this fake sorts ONLY
        // when told to, not unconditionally.
        String orderByField

        FakeFind condition(String fieldName, Object value) {
            conditions[fieldName] = value
            return this
        }

        FakeFind limit(Integer maxRows) {
            this.maxRows = maxRows
            return this
        }

        // Task 6 (automation exclusion filters), fix round 1: loadAutomationSourceFilters chains
        // .orderBy("sequenceNum") on every extractor dispatch — the ordering is load-bearing (the first
        // matching rule in SourceFilterSupport.firstMatchingRule owns the excluded count). list() below
        // sorts by this field ONLY when orderBy() was actually called, so a dropped/typo'd .orderBy(...)
        // call in production code changes the returned order and fails automationSourceFiltersAreReturnedInSequenceOrder.
        FakeFind orderBy(String fieldName) {
            this.orderByField = fieldName
            return this
        }

        FakeFind disableAuthz() { return this }

        FakeFind useCache(boolean ignored) { return this }

        FakeValue one() {
            return list().find()
        }

        // Mirrors Moqui EntityFind.count() — DAR-BE-002's in-flight governor counts RUNNING executions
        // rather than loading them, since the count is all it needs.
        long count() {
            return list().size() as long
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
            if (orderByField) {
                matchedRows = matchedRows.sort(false) { FakeValue row -> row[orderByField] as Comparable }
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
        /**
         * Mirrors ServiceFacadeImpl.isServiceDefined, which callConfiguredSourceExtractor now consults
         * so a connector row naming a service from an uninstalled component (database-darpan is absent
         * from every production image) fails with a legible message instead of "Unknown service".
         * Defaults to true: these fixtures exercise dispatch, not deployment topology. Set false in a
         * test that wants the not-installed path.
         */
        Closure serviceDefinedCheck = { String ignored -> true }
        boolean isServiceDefined(String serviceName) { return serviceDefinedCheck(serviceName) }
        List<FakeServiceCall> calls = Collections.synchronizedList([] as List<FakeServiceCall>)
        FakeEc ec
        /**
         * DAR-BE-002: a REAL background pool, not an inline stub. "scan#DueAutomations does not wait for
         * the execution it submitted" is only provable if the submitted body can still be running when
         * the scan returns — an inline fake would make every arrangement of the production code pass.
         * Daemon threads so a fixture that leaves work parked on a latch cannot hold the Gradle test JVM.
         */
        final ExecutorService asyncPool = Executors.newCachedThreadPool({ Runnable runnable ->
            Thread thread = new Thread(runnable, "fake-async-service")
            thread.daemon = true
            return thread
        } as ThreadFactory)
        private final List<Future<?>> asyncSubmissions = Collections.synchronizedList([] as List<Future<?>>)

        FakeServiceCall sync() {
            return new FakeServiceCall(service: this)
        }

        FakeAsyncServiceCall async() {
            return new FakeAsyncServiceCall(service: this)
        }

        Future<Map<String, Object>> dispatchAsync(FakeServiceCall submitted) {
            Future<Map<String, Object>> future = (Future<Map<String, Object>>) asyncPool.submit(
                    { -> (responder.call(submitted) ?: [:]) as Map<String, Object> } as Callable)
            asyncSubmissions << future
            return future
        }

        /**
         * Joins every submitted async body. A test asserting on the EFFECTS of a fire-and-forget
         * submission must say so explicitly — racing the worker would make it flaky, and asserting
         * without joining would quietly stop testing the re-drive at all.
         */
        void awaitAsyncCompletion(long timeoutSeconds = 10L) {
            List<Future<?>> pending
            synchronized (asyncSubmissions) { pending = new ArrayList<Future<?>>(asyncSubmissions) }
            pending.each { Future<?> future -> future.get(timeoutSeconds, TimeUnit.SECONDS) }
        }
    }

    /**
     * Mirrors Moqui's ServiceCallAsync: {@code call()} is fire-and-forget (void), {@code callFuture()}
     * hands back a Future. Both record the submission on the CALLING thread and run only the service
     * body off-thread, so a test can assert what was submitted without racing the worker.
     */
    private static class FakeAsyncServiceCall {
        FakeServiceFacade service
        String serviceName
        Map<String, Object> params = [:]

        FakeAsyncServiceCall name(String serviceName) {
            this.serviceName = serviceName
            return this
        }

        FakeAsyncServiceCall parameters(Map<String, Object> params) {
            this.params = params
            return this
        }

        FakeAsyncServiceCall disableAuthz() { return this }

        private FakeServiceCall recordSubmission() {
            FakeServiceCall submitted = new FakeServiceCall(service: service, serviceName: serviceName, params: params)
            service.calls << submitted
            return submitted
        }

        void call() {
            service.dispatchAsync(recordSubmission())
        }

        Future<Map<String, Object>> callFuture() {
            return service.dispatchAsync(recordSubmission())
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
        // No ambient transaction here (mirrors scan#DueAutomations, not run#AutomationNow): without
        // this, isTransactionInPlace() is undefined on this fake, so TransactionDetachSupport's call
        // throws MissingMethodException, which it catches and downgrades to a "could not suspend"
        // warning on every one of this file's ~19 executeAutomation calls — the detach ends up inert
        // by accident (an uncaught exception, not a deliberate no-op) instead of proving anything
        // about detached behavior, and floods the log with the same line an operator would grep for
        // on a real suspend failure.
        boolean isTransactionInPlace() { return false }

        Object runUseOrBegin(Integer timeout, String message, Closure work) {
            return work.call()
        }
    }

    // --- missing-diff verification on the scheduled path ---------------------------------------
    // The scheduled path has never verified its differences: STAGE_VERIFY appears 0 times in this
    // package, while runSavedRunDiff.groovy runs three verification passes. On gorjana automation
    // 100616 that gap meant a scheduled run reporting ~532 differences where the verified
    // interactive rerun reported 2. Rollout is flag-gated and OFF by default because enabling it
    // adds a ~46s stage to a path with a documented 60s transaction ceiling (design
    // 2026-08-26-reconciliation-pipeline-unification, steps 3-4).

    @Test
    void missingDiffVerificationIsOffByDefaultOnTheScheduledPath() {
        System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
        assertFalse(AutomationExecutionSupport.isMissingDiffVerificationEnabled(),
                "enabling verification changes reported difference counts by orders of magnitude; " +
                "it must never switch on merely by deploying")
    }

    @Test
    void missingDiffVerificationTurnsOnOnlyForAnExplicitTrue() {
        try {
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "true")
            assertTrue(AutomationExecutionSupport.isMissingDiffVerificationEnabled())
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "yes")
            assertFalse(AutomationExecutionSupport.isMissingDiffVerificationEnabled(),
                    "only an explicit 'true' may arm this; a typo must fail closed")
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
        }
    }

    @Test
    void aDisabledVerificationTouchesNothingAtAll() {
        System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
        Map<String, Object> reconcileResult = [differenceCount: 532L, missingInFile1Count: 500L,
                                               missingInFile2Count: 32L]

        // Every collaborator is null on purpose: with the flag off this must return before it can
        // touch the execution context, so a null ec proves no work was attempted.
        boolean ran = AutomationExecutionSupport.verifyMissingDiffsIfEnabled(
                null, null, null, null, reconcileResult, null, null)

        assertFalse(ran)
        assertEquals(532L, reconcileResult.differenceCount, "a disabled pass must not move any count")
        assertEquals(500L, reconcileResult.missingInFile1Count)
        assertNull(reconcileResult.processingWarnings, "a disabled pass must not annotate the result")
    }

    @Test
    void anEnabledVerificationStillSkipsARunWithNothingMissing() {
        try {
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "true")
            Map<String, Object> reconcileResult = [differenceCount: 4L, missingInFile1Count: 0L,
                                                   missingInFile2Count: 0L, ruleDifferenceCount: 4L]

            boolean ran = AutomationExecutionSupport.verifyMissingDiffsIfEnabled(
                    null, null, null, null, reconcileResult, null, null)

            assertFalse(ran, "value mismatches are not missing-object diffs; there is nothing to recheck")
            assertEquals(4L, reconcileResult.differenceCount)
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
        }
    }

    @Test
    void anEnabledVerificationSkipsARunWhoseRulesFailed() {
        try {
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "true")
            Map<String, Object> reconcileResult = [ruleExecutionFailed: true, differenceCount: 9L,
                                                   missingInFile1Count: 9L, missingInFile2Count: 0L]

            boolean ran = AutomationExecutionSupport.verifyMissingDiffsIfEnabled(
                    null, null, null, null, reconcileResult, null, null)

            assertFalse(ran, "partial diffs from a rule failure are kept for investigation, never rewritten")
            assertEquals(9L, reconcileResult.differenceCount)
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
        }
    }

    // --- canarying verification to specific automations ----------------------------------------
    // The arming property is JVM-wide, so on its own it switches verification on for EVERY
    // automation on an instance at once — no way to try one first. The allow-list narrows it
    // without an entity migration, a contract regeneration or a UI change, and deletes cleanly
    // when the default eventually flips (design step 4).

    @Test
    void anEmptyAllowListMeansEveryAutomationOnceArmed() {
        try {
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "true")
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_AUTOMATIONS_PROPERTY)
            assertTrue(AutomationExecutionSupport.isMissingDiffVerificationEnabled("100616"))
            assertTrue(AutomationExecutionSupport.isMissingDiffVerificationEnabled("ANY_OTHER"))
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
        }
    }

    @Test
    void anAllowListRestrictsVerificationToTheNamedAutomations() {
        try {
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "true")
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_AUTOMATIONS_PROPERTY, "100616, 100053")
            assertTrue(AutomationExecutionSupport.isMissingDiffVerificationEnabled("100616"),
                    "whitespace around a listed id must not exclude it")
            assertTrue(AutomationExecutionSupport.isMissingDiffVerificationEnabled("100053"))
            assertFalse(AutomationExecutionSupport.isMissingDiffVerificationEnabled("100999"),
                    "an automation outside the canary must stay on the old behaviour")
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_AUTOMATIONS_PROPERTY)
        }
    }

    @Test
    void anAllowListWithAnUnknownAutomationIdFailsClosed() {
        try {
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY, "true")
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_AUTOMATIONS_PROPERTY, "100616")
            assertFalse(AutomationExecutionSupport.isMissingDiffVerificationEnabled(null),
                    "a canary that cannot identify the automation must not verify it")
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_AUTOMATIONS_PROPERTY)
        }
    }

    @Test
    void theAllowListCannotArmVerificationOnItsOwn() {
        try {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY)
            System.setProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_AUTOMATIONS_PROPERTY, "100616")
            assertFalse(AutomationExecutionSupport.isMissingDiffVerificationEnabled("100616"),
                    "naming an automation must never be the thing that switches verification on")
        } finally {
            System.clearProperty(AutomationExecutionSupport.VERIFY_MISSING_DIFFS_AUTOMATIONS_PROPERTY)
        }
    }
}
