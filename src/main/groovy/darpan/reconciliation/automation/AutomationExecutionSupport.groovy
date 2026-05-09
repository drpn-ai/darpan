package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.moqui.impl.service.ScheduledJobRunner

import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.currentUserId
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomation
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomationSources
import static darpan.reconciliation.automation.AutomationRuntimeSupport.normalizeDataManagerPath
import static darpan.reconciliation.automation.AutomationRuntimeSupport.nowTimestamp
import static darpan.reconciliation.automation.AutomationRuntimeSupport.requireNormalized
import static darpan.reconciliation.automation.AutomationRuntimeSupport.runInTransaction
import static darpan.reconciliation.automation.AutomationRuntimeSupport.safeMetadataJson
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorDetail
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage
import static darpan.reconciliation.automation.AutomationRuntimeSupport.truncate
import static darpan.reconciliation.automation.AutomationRuntimeSupport.updateAutomationExecution

class AutomationExecutionSupport {
    static final String AUTOMATION_INPUT_API_RANGE = "AUT_IN_API_RANGE"
    static final String AUTOMATION_INPUT_SFTP_FILES = "AUT_IN_SFTP_FILES"
    static final String AUTOMATION_SOURCE_API = "AUT_SRC_API"
    static final String OMS_SYSTEM_ENUM_ID = "OMS"
    static final String SHOPIFY_SYSTEM_ENUM_ID = "SHOPIFY"
    static final String HOTWAX_OMS_ORDERS_EXTRACT_SERVICE = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders"
    static final String SHOPIFY_ORDERS_EXTRACT_SERVICE = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"
    static final String HOTWAX_OMS_WINDOW_START_PARAMETER = "windowStart"
    static final String HOTWAX_OMS_WINDOW_END_PARAMETER = "windowEnd"
    static final String SHOPIFY_WINDOW_START_PARAMETER = "windowStart"
    static final String SHOPIFY_WINDOW_END_PARAMETER = "windowEnd"

    static final String STATUS_PENDING = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING = "AUT_STAT_RUNNING"
    static final String STATUS_SUCCEEDED = "AUT_STAT_SUCCESS"
    static final String STATUS_FAILED = "AUT_STAT_FAILED"
    static final String STATUS_NO_DATA = "AUT_STAT_NO_DATA"
    static final String STATUS_SKIPPED_DUPLICATE = "AUT_STAT_SKIP_DUP"

    static final String WINDOW_PREVIOUS_DAY = "AUT_WIN_PREV_DAY"
    static final String WINDOW_PREVIOUS_WEEK = "AUT_WIN_PREV_WEEK"
    static final String WINDOW_PREVIOUS_MONTH = "AUT_WIN_PREV_MONTH"
    static final String WINDOW_LAST_DAYS = "AUT_WIN_LAST_DAYS"
    static final String WINDOW_LAST_WEEKS = "AUT_WIN_LAST_WEEKS"
    static final String WINDOW_LAST_MONTHS = "AUT_WIN_LAST_MONTHS"
    static final String WINDOW_CUSTOM = "AUT_WIN_CUSTOM"

    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"

    private static final Set<String> REUSABLE_EXECUTION_STATUSES = [STATUS_PENDING] as Set

    private static final Closure DEFAULT_SOURCE_EXTRACTOR = { def ec, def automation, def source, Map<String, Object> window,
            Map<String, Object> params ->
        return callConfiguredSourceExtractor(ec, automation, source, window, params)
    }
    private static final Closure DEFAULT_RECONCILE_RUNNER = { def ec, def automation, def file1Source, def file2Source,
            Map<String, Object> file1Result, Map<String, Object> file2Result, Map<String, Object> window,
            Map<String, Object> params ->
        return callRuleSetCompareScope(ec, automation, file1Source, file2Source, file1Result, file2Result, window, params)
    }

    private static Closure sourceExtractor = DEFAULT_SOURCE_EXTRACTOR
    private static Closure reconcileRunner = DEFAULT_RECONCILE_RUNNER

    static void setSourceExtractor(Closure extractor) {
        sourceExtractor = extractor ?: DEFAULT_SOURCE_EXTRACTOR
    }

    static void setReconcileRunner(Closure runner) {
        reconcileRunner = runner ?: DEFAULT_RECONCILE_RUNNER
    }

    static void resetExecutionHooks() {
        sourceExtractor = DEFAULT_SOURCE_EXTRACTOR
        reconcileRunner = DEFAULT_RECONCILE_RUNNER
    }

    static Map<String, Object> executeAutomation(def ec, Map params) {
        Map<String, Object> input = params ?: [:]
        String automationId = requireNormalized(input.automationId, "automationId is required")
        def automation = loadAutomation(ec, automationId)
        String inputModeEnumId = normalize(readField(automation, "inputModeEnumId"))

        if (inputModeEnumId == AUTOMATION_INPUT_SFTP_FILES) {
            return SftpAutomationSupport.runSftpFileAutomation(ec, normalizeSftpExecutionParams(input))
        }
        if (inputModeEnumId != AUTOMATION_INPUT_API_RANGE) {
            throw new IllegalArgumentException("Automation ${automationId} must use ${AUTOMATION_INPUT_API_RANGE} or ${AUTOMATION_INPUT_SFTP_FILES}")
        }

        Timestamp scheduledFireTime = resolveScheduledFireTime(ec, input)
        List<Map<String, Object>> windows = resolveWindows(automation, input + [scheduledFireTime: scheduledFireTime])
        Map<String, Object> sourcesBySide = loadAutomationSources(ec, automationId)
        def file1Source = requireApiSource(automation, sourcesBySide[FILE_SIDE_1], FILE_SIDE_1)
        def file2Source = requireApiSource(automation, sourcesBySide[FILE_SIDE_2], FILE_SIDE_2)
        Map<String, Object> executionParams = new LinkedHashMap<String, Object>(input)
        Map<String, Object> sourceExtractorConfigDefaults = resolveSourceExtractorConfigDefaults(ec, automation, [file1Source, file2Source])
        if (sourceExtractorConfigDefaults) executionParams.sourceExtractorConfigDefaults = sourceExtractorConfigDefaults

        List<Map<String, Object>> executionResults = []
        windows.eachWithIndex { Map<String, Object> window, int index ->
            Map<String, Object> executionState = findOrCreateExecution(ec, automation, scheduledFireTime, window, index + 1)
            def execution = executionState.execution
            String automationExecutionId = normalize(readField(execution, "automationExecutionId"))

            if (executionState.duplicate == true) {
                executionResults << [
                        automationExecutionId: automationExecutionId,
                        statusEnumId         : STATUS_SKIPPED_DUPLICATE,
                        childWindowStartDate : window.childWindowStartDate,
                        childWindowEndDate   : window.childWindowEndDate,
                ]
                return
            }

            try {
                Timestamp startedTimestamp = nowTimestamp(ec)
                updateAutomationExecution(ec, execution, [
                        statusEnumId   : STATUS_RUNNING,
                        startedDate    : startedTimestamp,
                        lastUpdatedDate: startedTimestamp,
                ])

                Map<String, Object> file1Result = normalizeSourceResult(sourceExtractor.call(ec, automation, file1Source, window, executionParams), file1Source)
                Map<String, Object> file2Result = normalizeSourceResult(sourceExtractor.call(ec, automation, file2Source, window, executionParams), file2Source)

                if (!hasData(file1Result) || !hasData(file2Result)) {
                    Timestamp completedTimestamp = nowTimestamp(ec)
                    Map<String, Object> noDataFields = executionUpdateFields(file1Result, file2Result, [:]) + [
                            statusEnumId    : STATUS_NO_DATA,
                            completedDate   : completedTimestamp,
                            safeMetadataJson: safeMetadataJson([
                                    mode              : "API_DATE_RANGE",
                                    dataAvailable     : false,
                                    file1DataAvailable: hasData(file1Result),
                                    file2DataAvailable: hasData(file2Result),
                                    childWindowStart  : window.childWindowStartDate,
                                    childWindowEnd    : window.childWindowEndDate,
                            ]),
                            lastUpdatedDate : completedTimestamp,
                    ]
                    updateAutomationExecution(ec, execution, noDataFields)
                    executionResults << noDataFields + [
                            automationExecutionId: automationExecutionId,
                            childWindowStartDate : window.childWindowStartDate,
                            childWindowEndDate   : window.childWindowEndDate,
                    ]
                    return
                }

                Map<String, Object> reconcileResult = normalizeReconcileResult(
                        reconcileRunner.call(ec, automation, file1Source, file2Source, file1Result, file2Result, window, executionParams)
                )
                ensureAutomationResultArtifact(ec, automation, file1Source, file2Source, reconcileResult, window, executionParams)
                String resultDataManagerPath = normalizeDataManagerPath(ec,
                        reconcileResult.resultDataManagerPath ?: reconcileResult.diffLocation ?: reconcileResult.diffFileName)
                String reconciliationRunResultId = persistAutomationRunResult(ec, automation, file1Result, file2Result,
                        reconcileResult, resultDataManagerPath)

                Timestamp completedTimestamp = nowTimestamp(ec)
                Map<String, Object> successFields = executionUpdateFields(file1Result, file2Result, reconcileResult) + [
                        statusEnumId              : STATUS_SUCCEEDED,
                        completedDate             : completedTimestamp,
                        resultFileName            : normalize(reconcileResult.resultFileName) ?: fileNameFromPath(reconcileResult.diffFileName) ?: fileNameFromPath(resultDataManagerPath),
                        resultDataManagerPath     : resultDataManagerPath,
                        reconciliationRunResultId : reconciliationRunResultId,
                        safeMetadataJson          : safeMetadataJson([
                                mode                : "API_DATE_RANGE",
                                dataAvailable       : true,
                                childWindowStart    : window.childWindowStartDate,
                                childWindowEnd      : window.childWindowEndDate,
                                sourceExtractorMode : "configured",
                                reconciliationRunner: "RuleSetCompareScope",
                                validationErrors    : reconcileResult.validationErrors ?: [],
                                processingWarnings  : reconcileResult.processingWarnings ?: [],
                        ]),
                        lastUpdatedDate           : completedTimestamp,
                ]
                updateAutomationExecution(ec, execution, successFields)
                TenantNotificationSupport.notifyRunCompleted(ec, [
                        reconciliationRunResultId: reconciliationRunResultId,
                        runName                  : normalize(readField(automation, "automationName")),
                        savedRunId               : normalize(readField(automation, "savedRunId")),
                        reconciliationRunId      : normalize(readField(automation, "reconciliationRunId")),
                        companyUserGroupId       : normalize(readField(automation, "companyUserGroupId")),
                        resultDataManagerPath    : resultDataManagerPath,
                        file1SystemEnumId        : normalize(readField(file1Source, "systemEnumId")),
                        file2SystemEnumId        : normalize(readField(file2Source, "systemEnumId")),
                        file1SystemLabel         : normalize(reconcileResult.file1Label),
                        file2SystemLabel         : normalize(reconcileResult.file2Label),
                        differenceCount          : successFields.differenceCount,
                        onlyInFile1Count         : successFields.onlyInFile1Count,
                        onlyInFile2Count         : successFields.onlyInFile2Count,
                ])
                executionResults << successFields + [
                        automationExecutionId: automationExecutionId,
                        childWindowStartDate : window.childWindowStartDate,
                        childWindowEndDate   : window.childWindowEndDate,
                ]
            } catch (Throwable t) {
                Timestamp completedTimestamp = nowTimestamp(ec)
                Map<String, Object> failureFields = [
                        statusEnumId    : STATUS_FAILED,
                        completedDate   : completedTimestamp,
                        errorMessage    : truncate(sanitizeErrorMessage(t), 3900),
                        errorDetail     : truncate(sanitizeErrorDetail(t), 12000),
                        safeMetadataJson: safeMetadataJson([
                                mode            : "API_DATE_RANGE",
                                childWindowStart: window.childWindowStartDate,
                                childWindowEnd  : window.childWindowEndDate,
                                errorMessage    : sanitizeErrorMessage(t),
                        ]),
                        lastUpdatedDate : completedTimestamp,
                ]
                updateAutomationExecution(ec, execution, failureFields)
                executionResults << failureFields + [
                        automationExecutionId: automationExecutionId,
                        childWindowStartDate : window.childWindowStartDate,
                        childWindowEndDate   : window.childWindowEndDate,
                    ]
            }
        }

        Map statusCounts = executionResults.countBy { it.statusEnumId }
        return [
                automationId          : automationId,
                scheduledFireTime     : scheduledFireTime,
                executedCount         : statusCounts[STATUS_SUCCEEDED] ?: 0,
                noDataCount           : statusCounts[STATUS_NO_DATA] ?: 0,
                failedCount           : statusCounts[STATUS_FAILED] ?: 0,
                skippedDuplicateCount : statusCounts[STATUS_SKIPPED_DUPLICATE] ?: 0,
                executionResults      : executionResults,
        ]
    }

    static Map<String, Object> scanDueAutomations(def ec, Map params) {
        Map<String, Object> input = params ?: [:]
        Timestamp now = toTimestamp(input.nowTimestamp) ?: nowTimestamp(ec)
        int limit = normalizeInt(input.limit) ?: 100

        List<Map<String, Object>> dueAutomationEntries = loadActiveAutomations(ec)
                .collect { automation ->
                    [
                            automation       : automation,
                            scheduledFireTime: resolveDueScheduledFireTime(automation, now),
                    ]
                }
                .findAll { entry -> entry.scheduledFireTime != null }
                .sort { left, right ->
                    ((Timestamp) left.scheduledFireTime) <=> ((Timestamp) right.scheduledFireTime)
                }
                .take(limit)

        List<Map<String, Object>> pendingExecutions = dueAutomationEntries.collect { Map<String, Object> dueEntry ->
            def automation = dueEntry.automation
            String automationId = normalize(readField(automation, "automationId"))
            Timestamp scheduledFireTime = toTimestamp(dueEntry.scheduledFireTime)
            Map<String, Object> executeParams = [
                    automationId      : automationId,
                    scheduledFireTime : scheduledFireTime,
                    nowTimestamp      : now,
            ]
            ["outputLocation", "sparkMaster", "sparkAppName"].each { key ->
                if (input[key] != null) executeParams[key] = input[key]
            }

            return [
                    automation       : automation,
                    automationId     : automationId,
                    scheduledFireTime: scheduledFireTime,
                    executeFuture    : callExecuteAutomationServiceFuture(ec, executeParams),
            ] as Map<String, Object>
        } as List<Map<String, Object>>

        List<Map<String, Object>> scanResults = []
        pendingExecutions.each { Map<String, Object> pendingExecution ->
            def automation = pendingExecution.automation
            String automationId = pendingExecution.automationId as String
            Timestamp scheduledFireTime = toTimestamp(pendingExecution.scheduledFireTime)
            Map<String, Object> executeResult = resolveExecuteAutomationFuture((Future<Map<String, Object>>) pendingExecution.executeFuture)
            Timestamp nextFireTime = resolveNextScheduledFireTime(automation, scheduledFireTime, now)
            updateAutomation(ec, automation, [
                    lastScheduledFireTime: scheduledFireTime,
                    nextScheduledFireTime: nextFireTime,
                    lastUpdatedDate      : now,
            ])
            scanResults << [
                    automationId             : automationId,
                    scheduledFireTime        : scheduledFireTime,
                    nextScheduledFireTime    : nextFireTime,
                    executeResult            : executeResult,
            ]
        }

        return [
                scanTimestamp: now,
                dueCount     : dueAutomationEntries.size(),
                scanResults  : scanResults,
        ]
    }

    static List<Map<String, Object>> resolveWindows(def automation, Map params = [:]) {
        Map<String, Object> input = params ?: [:]
        Timestamp scheduledFireTime = toTimestamp(input.scheduledFireTime ?: input.scheduledDate ?: input.nowTimestamp) ?:
                new Timestamp(System.currentTimeMillis())
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        ZonedDateTime scheduledLocal = scheduledFireTime.toInstant().atZone(zone)
        String windowType = normalize(readField(automation, "relativeWindowTypeEnumId")) ?: WINDOW_LAST_DAYS
        int windowCount = normalizeInt(readField(automation, "relativeWindowCount")) ?: 1

        ZonedDateTime windowStart
        ZonedDateTime windowEnd
        switch (windowType) {
            case WINDOW_PREVIOUS_DAY:
                LocalDate previousDay = scheduledLocal.toLocalDate().minusDays(1)
                windowStart = previousDay.atStartOfDay(zone)
                windowEnd = previousDay.plusDays(1).atStartOfDay(zone)
                break
            case WINDOW_PREVIOUS_WEEK:
                LocalDate currentWeekStart = scheduledLocal.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                LocalDate previousWeekStart = currentWeekStart.minusWeeks(1)
                windowStart = previousWeekStart.atStartOfDay(zone)
                windowEnd = currentWeekStart.atStartOfDay(zone)
                break
            case WINDOW_PREVIOUS_MONTH:
                YearMonth previousMonth = YearMonth.from(scheduledLocal.toLocalDate()).minusMonths(1)
                windowStart = previousMonth.atDay(1).atStartOfDay(zone)
                windowEnd = previousMonth.plusMonths(1).atDay(1).atStartOfDay(zone)
                break
            case WINDOW_LAST_WEEKS:
                LocalDate weekEnd = scheduledLocal.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                windowStart = weekEnd.minusWeeks(Math.max(windowCount, 1)).atStartOfDay(zone)
                windowEnd = weekEnd.atStartOfDay(zone)
                break
            case WINDOW_LAST_MONTHS:
                YearMonth monthEnd = YearMonth.from(scheduledLocal.toLocalDate())
                windowStart = monthEnd.minusMonths(Math.max(windowCount, 1)).atDay(1).atStartOfDay(zone)
                windowEnd = monthEnd.atDay(1).atStartOfDay(zone)
                break
            case WINDOW_CUSTOM:
                Object customStart = input.windowStartDate ?: input.childWindowStartDate ?: readField(automation, "customWindowStartDate")
                Object customEnd = input.windowEndDate ?: input.childWindowEndDate ?: readField(automation, "customWindowEndDate")
                windowStart = requireTimestamp(customStart, "windowStartDate is required for custom automation windows")
                        .toInstant().atZone(zone)
                windowEnd = requireTimestamp(customEnd, "windowEndDate is required for custom automation windows")
                        .toInstant().atZone(zone)
                break
            case WINDOW_LAST_DAYS:
            default:
                LocalDate dayEnd = scheduledLocal.toLocalDate()
                windowStart = dayEnd.minusDays(Math.max(windowCount, 1)).atStartOfDay(zone)
                windowEnd = dayEnd.atStartOfDay(zone)
                break
        }

        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("Automation window start must be before window end")
        }

        Timestamp parentStart = Timestamp.from(windowStart.toInstant())
        Timestamp parentEnd = Timestamp.from(windowEnd.toInstant())
        return splitOnCalendarMonthBoundaries(windowStart, windowEnd, zone).withIndex().collect { Map<String, ZonedDateTime> segment, int index ->
            [
                    sequenceNum         : index + 1,
                    windowStartDate     : parentStart,
                    windowEndDate       : parentEnd,
                    childWindowStartDate: Timestamp.from(segment.start.toInstant()),
                    childWindowEndDate  : Timestamp.from(segment.end.toInstant()),
            ]
        }
    }

    static Timestamp resolveNextScheduledFireTime(def automation, Timestamp scheduledFireTime, Timestamp now) {
        String scheduleExpr = normalize(readField(automation, "scheduleExpr"))
        if (!scheduleExpr) return null
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        Timestamp base = scheduledFireTime ?: now ?: new Timestamp(System.currentTimeMillis())

        try {
            ZonedDateTime cursor = base.toInstant().atZone(zone).plusNanos(1)
            ZonedDateTime nowLocal = (now ?: base).toInstant().atZone(zone)
            Optional<ZonedDateTime> next = ScheduledJobRunner.getExecutionTime(scheduleExpr).nextExecution(cursor)
            while (next.present && !next.get().isAfter(nowLocal)) {
                next = ScheduledJobRunner.getExecutionTime(scheduleExpr).nextExecution(next.get().plusNanos(1))
            }
            return next.present ? Timestamp.from(next.get().toInstant()) : null
        } catch (Throwable ignored) {
            Duration duration = parseScheduleDuration(scheduleExpr)
            if (duration == null || duration.isZero() || duration.isNegative()) return null
            ZonedDateTime next = base.toInstant().atZone(zone).plus(duration)
            ZonedDateTime nowLocal = (now ?: base).toInstant().atZone(zone)
            while (!next.isAfter(nowLocal)) next = next.plus(duration)
            return Timestamp.from(next.toInstant())
        }
    }

    static Timestamp resolveDueScheduledFireTime(def automation, Timestamp now) {
        Timestamp nowValue = now ?: new Timestamp(System.currentTimeMillis())
        Timestamp nextFire = toTimestamp(readField(automation, "nextScheduledFireTime"))
        if (nextFire != null) return nextFire.after(nowValue) ? null : nextFire

        String scheduleExpr = normalize(readField(automation, "scheduleExpr"))
        if (!scheduleExpr) return null

        Timestamp lastScheduledFireTime = toTimestamp(readField(automation, "lastScheduledFireTime"))
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        Timestamp cronFireTime = resolveLastCronFireTime(scheduleExpr, nowValue, zone)
        if (cronFireTime != null) {
            return lastScheduledFireTime == null || lastScheduledFireTime.before(cronFireTime) ? cronFireTime : null
        }

        Timestamp durationFireTime = resolveLastDurationFireTime(scheduleExpr, lastScheduledFireTime, nowValue, zone)
        if (durationFireTime != null) {
            return lastScheduledFireTime == null || lastScheduledFireTime.before(durationFireTime) ? durationFireTime : null
        }
        return null
    }

    protected static Map<String, Object> normalizeSftpExecutionParams(Map<String, Object> input) {
        Map<String, Object> sftpParams = [:]
        sftpParams.putAll(input)
        if (sftpParams.scheduledFireTime && !sftpParams.scheduledDate) sftpParams.scheduledDate = sftpParams.scheduledFireTime
        return sftpParams
    }

    protected static Map<String, Object> findOrCreateExecution(def ec, def automation, Timestamp scheduledFireTime,
            Map<String, Object> window, int sequenceNum) {
        String automationId = normalize(readField(automation, "automationId"))
        Timestamp childStart = toTimestamp(window.childWindowStartDate)
        Timestamp childEnd = toTimestamp(window.childWindowEndDate)

        def existing = ec?.entity?.find("darpan.reconciliation.ReconciliationAutomationExecution")
                ?.condition("automationId", automationId)
                ?.condition("scheduledDate", scheduledFireTime)
                ?.condition("childWindowStartDate", childStart)
                ?.condition("childWindowEndDate", childEnd)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
        if (existing) {
            String statusEnumId = normalize(readField(existing, "statusEnumId"))
            return [
                    execution: existing,
                    duplicate: !REUSABLE_EXECUTION_STATUSES.contains(statusEnumId),
                    reused   : REUSABLE_EXECUTION_STATUSES.contains(statusEnumId),
            ]
        }

        def created = runInTransaction(ec, "Error creating reconciliation automation execution", {
            Timestamp createdTimestamp = nowTimestamp(ec)
            def execution = ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationExecution")
            execution.set("automationId", automationId)
            execution.set("companyUserGroupId", normalize(readField(automation, "companyUserGroupId")))
            execution.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
            execution.set("statusEnumId", STATUS_PENDING)
            execution.set("scheduledDate", scheduledFireTime)
            execution.set("childWindowSequenceNum", sequenceNum)
            execution.set("windowStartDate", toTimestamp(window.windowStartDate))
            execution.set("windowEndDate", toTimestamp(window.windowEndDate))
            execution.set("childWindowStartDate", childStart)
            execution.set("childWindowEndDate", childEnd)
            execution.set("createdDate", createdTimestamp)
            execution.set("lastUpdatedDate", createdTimestamp)
            execution.setSequencedIdPrimary()
            execution.create()
            return execution
        })
        return [execution: created, duplicate: false, reused: false]
    }

    protected static List<Map<String, ZonedDateTime>> splitOnCalendarMonthBoundaries(ZonedDateTime start,
            ZonedDateTime end, ZoneId zone) {
        List<Map<String, ZonedDateTime>> segments = []
        ZonedDateTime segmentStart = start
        while (segmentStart.isBefore(end)) {
            ZonedDateTime nextMonthStart = YearMonth.from(segmentStart.toLocalDate()).plusMonths(1)
                    .atDay(1).atStartOfDay(zone)
            ZonedDateTime segmentEnd = nextMonthStart.isBefore(end) ? nextMonthStart : end
            segments << [start: segmentStart, end: segmentEnd]
            segmentStart = segmentEnd
        }
        return segments
    }

    protected static Timestamp resolveLastCronFireTime(String scheduleExpr, Timestamp now, ZoneId zone) {
        try {
            ZonedDateTime nowLocal = now.toInstant().atZone(zone)
            Optional<ZonedDateTime> last = ScheduledJobRunner.getExecutionTime(scheduleExpr).lastExecution(nowLocal)
            return last.present ? Timestamp.from(last.get().toInstant()) : null
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static Timestamp resolveLastDurationFireTime(String scheduleExpr, Timestamp lastScheduledFireTime,
            Timestamp now, ZoneId zone) {
        Duration duration = parseScheduleDuration(scheduleExpr)
        if (duration == null || duration.isZero() || duration.isNegative() || lastScheduledFireTime == null) return null

        ZonedDateTime nowLocal = now.toInstant().atZone(zone)
        ZonedDateTime candidate = lastScheduledFireTime.toInstant().atZone(zone).plus(duration)
        Timestamp latestDue = null
        while (!candidate.isAfter(nowLocal)) {
            latestDue = Timestamp.from(candidate.toInstant())
            candidate = candidate.plus(duration)
        }
        return latestDue
    }

    protected static Map<String, Object> callConfiguredSourceExtractor(def ec, def automation, def source,
            Map<String, Object> window, Map<String, Object> params) {
        Map<String, Object> metadata = resolveSourceExtractorMetadata(ec, source,
                params?.sourceExtractorConfigDefaults instanceof Map ? (Map<String, Object>) params.sourceExtractorConfigDefaults : [:])
        String serviceName = normalize(metadata.extractServiceName ?: metadata.serviceName)
        if (!serviceName) {
            String systemEnumId = normalize(readField(source, "systemEnumId")) ?: "unknown system"
            String fileSide = normalize(readField(source, "fileSide")) ?: "source"
            throw new IllegalStateException("API source extractor is not configured for ${systemEnumId} ${fileSide}. Add extractServiceName to source metadata after DAR-240/DAR-241 source contracts are available.")
        }

        Map<String, Object> serviceParams = [:]
        if (metadata.parameters instanceof Map) serviceParams.putAll((Map<String, Object>) metadata.parameters)
        serviceParams.automationId = normalize(readField(automation, "automationId"))
        serviceParams.companyUserGroupId = normalize(readField(automation, "companyUserGroupId"))
        serviceParams.fileSide = normalize(readField(source, "fileSide"))
        serviceParams.systemEnumId = normalize(readField(source, "systemEnumId"))
        serviceParams.sourceTypeEnumId = normalize(readField(source, "sourceTypeEnumId"))
        serviceParams.windowStartDate = window.childWindowStartDate
        serviceParams.windowEndDate = window.childWindowEndDate

        String dateFromParameterName = normalize(readField(source, "dateFromParameterName")) ?: defaultDateFromParameterName(serviceName)
        String dateToParameterName = normalize(readField(source, "dateToParameterName")) ?: defaultDateToParameterName(serviceName)
        serviceParams[dateFromParameterName] = window.childWindowStartDate
        serviceParams[dateToParameterName] = window.childWindowEndDate
        if (serviceName == SHOPIFY_ORDERS_EXTRACT_SERVICE) serviceParams.preserveWindowInstants = true

        def call = ec.service.sync().name(serviceName).parameters(serviceParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        Map<String, Object> result = (call.call() ?: [:]) as Map<String, Object>
        List<String> errors = (result.errors instanceof Collection ? (Collection) result.errors : [])
                .collect { Object error -> normalize(error) }
                .findAll { String error -> error } as List<String>
        if (errors) throw new IllegalStateException(errors.join("; "))
        return result
    }

    protected static Map<String, Object> resolveSourceExtractorConfigDefaults(def ec, def automation, Collection sources) {
        String companyUserGroupId = normalize(readField(automation, "companyUserGroupId"))
        if (!companyUserGroupId) return [:]

        boolean needsOmsRestConfig = false
        boolean needsShopifyAuthConfig = false
        (sources ?: []).each { source ->
            Map<String, Object> metadata = parseJsonMap(readField(source, "safeMetadataJson"))
            if (!normalize(metadata.extractServiceName ?: metadata.serviceName)) {
                Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
                String systemEnumId = normalize(readField(source, "systemEnumId"))
                if (systemEnumId == OMS_SYSTEM_ENUM_ID && !normalize(parameters.omsRestSourceConfigId)) needsOmsRestConfig = true
                if (systemEnumId == SHOPIFY_SYSTEM_ENUM_ID && !normalize(parameters.shopifyAuthConfigId)) needsShopifyAuthConfig = true
            }
        }

        Map<String, Object> defaults = [:]
        if (needsOmsRestConfig) defaults.omsRestSourceConfigId = findSingleActiveOmsRestSourceConfigId(ec, companyUserGroupId)
        if (needsShopifyAuthConfig) defaults.shopifyAuthConfigId = findSingleActiveShopifyAuthConfigId(ec, companyUserGroupId)
        return defaults.findAll { it.value != null } as Map<String, Object>
    }

    protected static Map<String, Object> resolveSourceExtractorMetadata(def ec, def source, Map<String, Object> configDefaults = [:]) {
        Map<String, Object> metadata = parseJsonMap(readField(source, "safeMetadataJson"))
        if (normalize(metadata.extractServiceName ?: metadata.serviceName)) return metadata

        String systemEnumId = normalize(readField(source, "systemEnumId"))
        String companyUserGroupId = normalize(readField(source, "companyUserGroupId"))
        Map<String, Object> parameters = metadata.parameters instanceof Map ?
                new LinkedHashMap<>((Map<String, Object>) metadata.parameters) : [:]

        if (systemEnumId == OMS_SYSTEM_ENUM_ID) {
            String configId = normalize(parameters.omsRestSourceConfigId) ?:
                    normalize(configDefaults?.omsRestSourceConfigId) ?:
                    findSingleActiveOmsRestSourceConfigId(ec, companyUserGroupId)
            if (configId) {
                parameters.omsRestSourceConfigId = configId
                metadata.parameters = parameters
                metadata.extractServiceName = HOTWAX_OMS_ORDERS_EXTRACT_SERVICE
            }
        }
        if (systemEnumId == SHOPIFY_SYSTEM_ENUM_ID) {
            String configId = normalize(parameters.shopifyAuthConfigId) ?:
                    normalize(configDefaults?.shopifyAuthConfigId) ?:
                    findSingleActiveShopifyAuthConfigId(ec, companyUserGroupId)
            if (configId) {
                parameters.shopifyAuthConfigId = configId
                metadata.parameters = parameters
                metadata.extractServiceName = SHOPIFY_ORDERS_EXTRACT_SERVICE
            }
        }
        return metadata
    }

    protected static String defaultDateFromParameterName(String serviceName) {
        switch (normalize(serviceName)) {
            case HOTWAX_OMS_ORDERS_EXTRACT_SERVICE:
                return HOTWAX_OMS_WINDOW_START_PARAMETER
            case SHOPIFY_ORDERS_EXTRACT_SERVICE:
                return SHOPIFY_WINDOW_START_PARAMETER
            default:
                return "fromDate"
        }
    }

    protected static String defaultDateToParameterName(String serviceName) {
        switch (normalize(serviceName)) {
            case HOTWAX_OMS_ORDERS_EXTRACT_SERVICE:
                return HOTWAX_OMS_WINDOW_END_PARAMETER
            case SHOPIFY_ORDERS_EXTRACT_SERVICE:
                return SHOPIFY_WINDOW_END_PARAMETER
            default:
                return "toDate"
        }
    }

    protected static String findSingleActiveOmsRestSourceConfigId(def ec, String companyUserGroupId) {
        if (!companyUserGroupId) return null
        try {
            List rows = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                    .condition("companyUserGroupId", companyUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .disableAuthz()
                    .useCache(false)
                    .limit(2)
                    .list() ?: []
            return rows.size() == 1 ? normalize(readField(rows.first(), "omsRestSourceConfigId")) : null
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static String findSingleActiveShopifyAuthConfigId(def ec, String companyUserGroupId) {
        if (!companyUserGroupId) return null
        try {
            List rows = ec.entity.find("darpan.shopify.ShopifyAuthConfig")
                    .condition("companyUserGroupId", companyUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .disableAuthz()
                    .useCache(false)
                    .limit(2)
                    .list() ?: []
            return rows.size() == 1 ? normalize(readField(rows.first(), "shopifyAuthConfigId")) : null
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static String sourceLabel(def ec, String systemEnumId, String fallback) {
        String enumId = normalize(systemEnumId)
        if (!enumId) return fallback
        try {
            def enumeration = ec?.entity?.find("moqui.basic.Enumeration")
                    ?.condition("enumId", enumId)
                    ?.disableAuthz()
                    ?.useCache(true)
                    ?.one()
            return normalize(readField(enumeration, "description")) ?: normalize(readField(enumeration, "enumCode")) ?: enumId
        } catch (Throwable ignored) {
            return enumId
        }
    }

    protected static Map<String, Object> callRuleSetCompareScope(def ec, def automation, def file1Source, def file2Source,
            Map<String, Object> file1Result, Map<String, Object> file2Result, Map<String, Object> window,
            Map<String, Object> params) {
        Map<String, Object> serviceParams = [
                ruleSetId           : normalize(readField(automation, "ruleSetId")) ?: normalize(readField(automation, "savedRunId")),
                compareScopeId      : normalize(readField(automation, "compareScopeId")),
                file1Location       : file1Result.fileLocation ?: file1Result.dataManagerPath,
                file2Location       : file2Result.fileLocation ?: file2Result.dataManagerPath,
                file1Name           : file1Result.fileName,
                file2Name           : file2Result.fileName,
                file1FileTypeEnumId : file1Result.fileTypeEnumId ?: readField(file1Source, "fileTypeEnumId"),
                file2FileTypeEnumId : file2Result.fileTypeEnumId ?: readField(file2Source, "fileTypeEnumId"),
                file1SchemaFileName : file1Result.schemaFileName ?: readField(file1Source, "schemaFileName"),
                file2SchemaFileName : file2Result.schemaFileName ?: readField(file2Source, "schemaFileName"),
                hasHeader           : params.containsKey("hasHeader") ? params.hasHeader : Boolean.TRUE,
                sparkMaster         : normalize(params.sparkMaster) ?: "local[*]",
                sparkAppName        : normalize(params.sparkAppName) ?: "AutomationRuleSetCompareScope",
        ].findAll { it.value != null } as Map<String, Object>

        def call = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters(serviceParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        return (call.call() ?: [:]) as Map<String, Object>
    }

    protected static Map<String, Object> callExecuteAutomationService(def ec, Map<String, Object> executeParams) {
        def call = ec.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.execute#Automation")
                .parameters(executeParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        return (call.call() ?: [:]) as Map<String, Object>
    }

    protected static Future<Map<String, Object>> callExecuteAutomationServiceFuture(def ec, Map<String, Object> executeParams) {
        if (!ec?.service?.metaClass?.respondsTo(ec.service, "async")) {
            return CompletableFuture.completedFuture(callExecuteAutomationService(ec, executeParams))
        }

        def call = ec.service.async()
                .name("reconciliation.ReconciliationAutomationServices.execute#Automation")
                .parameters(executeParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        return (Future<Map<String, Object>>) call.callFuture()
    }

    protected static Map<String, Object> resolveExecuteAutomationFuture(Future<Map<String, Object>> future) {
        return (future?.get() ?: [:]) as Map<String, Object>
    }

    protected static List loadActiveAutomations(def ec) {
        return ec?.entity?.find("darpan.reconciliation.ReconciliationAutomation")
                ?.condition("isActive", "Y")
                ?.disableAuthz()
                ?.useCache(false)
                ?.list() ?: []
    }

    protected static def requireApiSource(def automation, def source, String fileSide) {
        String automationId = normalize(readField(automation, "automationId"))
        if (!source) throw new IllegalArgumentException("Automation ${automationId} is missing ${fileSide} source")

        String automationTenant = normalize(readField(automation, "companyUserGroupId"))
        String sourceTenant = normalize(readField(source, "companyUserGroupId"))
        if (automationTenant && sourceTenant && automationTenant != sourceTenant) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source belongs to tenant ${sourceTenant}, not ${automationTenant}")
        }

        String sourceTypeEnumId = normalize(readField(source, "sourceTypeEnumId"))
        if (sourceTypeEnumId != AUTOMATION_SOURCE_API) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source must use ${AUTOMATION_SOURCE_API}")
        }
        return source
    }

    protected static Map<String, Object> normalizeSourceResult(Object rawResult, def source) {
        Map<String, Object> result = rawResult instanceof Map ? new LinkedHashMap<String, Object>((Map) rawResult) : [:]
        result.dataAvailable = result.containsKey("dataAvailable") ?
                toBoolean(result.dataAvailable) :
                Boolean.valueOf(result.fileLocation || result.dataManagerPath || result.location)
        result.fileLocation = normalize(result.fileLocation ?: result.dataManagerPath ?: result.location)
        result.dataManagerPath = normalize(result.dataManagerPath ?: result.fileLocation)
        result.fileName = normalize(result.fileName) ?: fileNameFromPath(result.fileLocation)
        result.fileTypeEnumId = normalize(result.fileTypeEnumId) ?: normalize(readField(source, "fileTypeEnumId"))
        result.schemaFileName = normalize(result.schemaFileName) ?: normalize(readField(source, "schemaFileName"))
        result.recordCount = normalizeInt(result.recordCount)
        return result
    }

    protected static Map<String, Object> normalizeReconcileResult(Object rawResult) {
        return rawResult instanceof Map ? new LinkedHashMap<String, Object>((Map) rawResult) : [:]
    }

    protected static Map<String, Object> ensureAutomationResultArtifact(def ec, def automation, def file1Source, def file2Source,
            Map<String, Object> reconcileResult, Map<String, Object> window, Map<String, Object> params) {
        if (!reconcileResult) return [:]
        if (normalize(reconcileResult.resultDataManagerPath ?: reconcileResult.diffLocation ?: reconcileResult.diffFileName)) {
            return reconcileResult
        }

        Dataset diffDf = reconcileResult.diffDf instanceof Dataset ? (Dataset) reconcileResult.diffDf : null
        if (diffDf == null) return reconcileResult

        String runToken = DataManagerSupport.safeToken(
                normalize(readField(automation, "savedRunId")) ?: normalize(readField(automation, "automationId")),
                "automation"
        )
        String runArtifactLocation = normalize(params?.outputLocation) ?:
                DataManagerSupport.resolveReconciliationRunLocation(ec, runToken, DataManagerSupport.formatRunTimestamp(ec))
        File runArtifactDir = DataManagerSupport.resolveDirectoryFile(ec, runArtifactLocation, true)
        if (runArtifactDir == null) {
            throw new IllegalStateException("Unable to resolve reconciliation automation result directory: ${runArtifactLocation}")
        }

        String file1Label = normalize(reconcileResult.file1Label) ?:
                sourceLabel(ec, normalize(readField(file1Source, "systemEnumId")), "File 1")
        String file2Label = normalize(reconcileResult.file2Label) ?:
                sourceLabel(ec, normalize(readField(file2Source, "systemEnumId")), "File 2")
        Map<String, Object> output = ReconciliationServices.writeDiffDatasetOutput(
                ec,
                diffDf,
                runArtifactLocation,
                DataManagerSupport.runArtifactFileName(runToken, "result", "result.json"),
                "${runToken}-diff.json",
                [
                        timestamp              : nowTimestamp(ec)?.toString(),
                        automationId           : normalize(readField(automation, "automationId")),
                        automationName         : normalize(readField(automation, "automationName")),
                        companyUserGroupId     : normalize(readField(automation, "companyUserGroupId")),
                        savedRunId             : normalize(readField(automation, "savedRunId")),
                        savedRunType           : normalize(readField(automation, "savedRunType")) ?: "ruleset",
                        ruleSetId              : normalize(reconcileResult.ruleSetId) ?: normalize(readField(automation, "ruleSetId")),
                        compareScopeId         : normalize(reconcileResult.compareScopeId) ?: normalize(readField(automation, "compareScopeId")),
                        compareScopeDescription: normalize(reconcileResult.compareScopeDescription),
                        file1Label             : file1Label,
                        file2Label             : file2Label,
                        file1Type              : normalize(reconcileResult.file1Type),
                        file2Type              : normalize(reconcileResult.file2Type),
                        reconciliation         : "RULESET",
                        objectType             : normalize(reconcileResult.objectType),
                        childWindowStart       : toTimestamp(window?.childWindowStartDate)?.toString(),
                        childWindowEnd         : toTimestamp(window?.childWindowEndDate)?.toString(),
                ].findAll { it.value != null } as Map<String, Object>,
                [
                        totalDifferences             : reconcileResult.differenceCount,
                        onlyInFile1Count             : reconcileResult.missingInFile2Count ?: reconcileResult.onlyInFile1Count,
                        onlyInFile2Count             : reconcileResult.missingInFile1Count ?: reconcileResult.onlyInFile2Count,
                        missingObjectDifferenceCount : reconcileResult.missingObjectDifferenceCount,
                        ruleDifferenceCount          : reconcileResult.ruleDifferenceCount,
                ].findAll { it.value != null } as Map<String, Object>,
                (List) (reconcileResult.validationErrors ?: []),
                (List) (reconcileResult.processingWarnings ?: [])
        )
        reconcileResult.diffLocation = output.diffLocation
        reconcileResult.diffFileName = output.diffFileName
        return reconcileResult
    }

    protected static boolean hasData(Map<String, Object> sourceResult) {
        return sourceResult.dataAvailable == true && normalize(sourceResult.fileLocation)
    }

    protected static Map<String, Object> executionUpdateFields(Map<String, Object> file1Result, Map<String, Object> file2Result,
            Map<String, Object> reconcileResult) {
        return [
                file1Name        : normalize(file1Result.fileName),
                file1DataManagerPath: normalize(file1Result.dataManagerPath),
                file2Name        : normalize(file2Result.fileName),
                file2DataManagerPath: normalize(file2Result.dataManagerPath),
                file1RecordCount : normalizeInt(file1Result.recordCount),
                file2RecordCount : normalizeInt(file2Result.recordCount),
                differenceCount  : normalizeInt(reconcileResult.differenceCount),
                onlyInFile1Count : normalizeInt(reconcileResult.onlyInFile1Count ?: reconcileResult.missingInFile2Count),
                onlyInFile2Count : normalizeInt(reconcileResult.onlyInFile2Count ?: reconcileResult.missingInFile1Count),
        ].findAll { it.value != null } as Map<String, Object>
    }

    protected static String persistAutomationRunResult(def ec, def automation, Map<String, Object> file1Result,
            Map<String, Object> file2Result, Map<String, Object> reconcileResult, String resultDataManagerPath) {
        if (!normalize(resultDataManagerPath)) return null

        return runInTransaction(ec, "Error saving reconciliation automation run result", {
            def runResultValue = ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
            runResultValue.set("savedRunId", normalize(readField(automation, "savedRunId")))
            runResultValue.set("savedRunType", normalize(readField(automation, "savedRunType")) ?: "ruleset")
            runResultValue.set("reconciliationRunId", normalize(readField(automation, "reconciliationRunId")))
            runResultValue.set("reconciliationMappingId", normalize(readField(automation, "reconciliationMappingId")))
            runResultValue.set("ruleSetId", normalize(readField(automation, "ruleSetId")))
            runResultValue.set("compareScopeId", normalize(readField(automation, "compareScopeId")))
            runResultValue.set("companyUserGroupId", normalize(readField(automation, "companyUserGroupId")))
            runResultValue.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
            runResultValue.set("file1Name", normalize(file1Result.fileName))
            runResultValue.set("file1DataManagerPath", normalize(file1Result.dataManagerPath))
            runResultValue.set("file2Name", normalize(file2Result.fileName))
            runResultValue.set("file2DataManagerPath", normalize(file2Result.dataManagerPath))
            runResultValue.set("resultDataManagerPath", normalize(resultDataManagerPath))
            runResultValue.set("reconciliationType", normalize(reconcileResult.reconciliationType ?: reconcileResult.objectType))
            runResultValue.set("differenceCount", normalizeInt(reconcileResult.differenceCount))
            runResultValue.set("onlyInFile1Count", normalizeInt(reconcileResult.onlyInFile1Count ?: reconcileResult.missingInFile2Count))
            runResultValue.set("onlyInFile2Count", normalizeInt(reconcileResult.onlyInFile2Count ?: reconcileResult.missingInFile1Count))
            runResultValue.set("createdDate", nowTimestamp(ec))
            runResultValue.setSequencedIdPrimary()
            runResultValue.create()
            return normalize(readField(runResultValue, "reconciliationRunResultId"))
        }) as String
    }

    protected static void updateAutomation(def ec, def automation, Map<String, Object> fields) {
        if (!automation) return
        runInTransaction(ec, "Error updating reconciliation automation", {
            fields.each { entry -> automation.set(entry.key as String, entry.value) }
            automation.update()
            return null
        })
    }

    protected static Timestamp resolveScheduledFireTime(def ec, Map<String, Object> input) {
        return toTimestamp(input.scheduledFireTime ?: input.scheduledDate ?: input.nowTimestamp) ?: nowTimestamp(ec)
    }

    protected static Timestamp requireTimestamp(Object rawValue, String message) {
        Timestamp timestamp = toTimestamp(rawValue)
        if (!timestamp) throw new IllegalArgumentException(message)
        return timestamp
    }

    protected static Timestamp toTimestamp(Object rawValue) {
        if (rawValue == null) return null
        if (rawValue instanceof Timestamp) return rawValue
        if (rawValue instanceof Date) return new Timestamp(rawValue.time)
        if (rawValue instanceof Instant) return Timestamp.from((Instant) rawValue)
        if (rawValue instanceof ZonedDateTime) return Timestamp.from(((ZonedDateTime) rawValue).toInstant())
        if (rawValue instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) rawValue)

        String normalized = normalize(rawValue)
        if (!normalized) return null
        try {
            return Timestamp.from(Instant.parse(normalized))
        } catch (Exception ignored) {
        }
        try {
            return Timestamp.valueOf(normalized)
        } catch (Exception ignored) {
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(normalized))
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("Invalid timestamp '${normalized}'")
    }

    protected static ZoneId resolveZoneId(Object rawZoneId) {
        String normalized = normalize(rawZoneId) ?: "UTC"
        try {
            return ZoneId.of(normalized)
        } catch (Exception ignored) {
            return ZoneId.of("UTC")
        }
    }

    protected static Duration parseScheduleDuration(String scheduleExpr) {
        try {
            return Duration.parse(scheduleExpr)
        } catch (Exception ignored) {
            return null
        }
    }

    protected static Map<String, Object> parseJsonMap(Object rawJson) {
        String text = normalize(rawJson)
        if (!text) return [:]
        try {
            Object parsed = new JsonSlurper().parseText(text)
            return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    protected static boolean toBoolean(Object rawValue) {
        if (rawValue == Boolean.TRUE) return true
        if (rawValue == Boolean.FALSE || rawValue == null) return false
        return ["Y", "true", "TRUE", "yes", "YES", "1"].contains(normalize(rawValue))
    }
}
