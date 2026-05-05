package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonOutput
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
                updateAutomationExecution(ec, execution, [
                        statusEnumId   : STATUS_RUNNING,
                        startedDate    : nowTimestamp(ec),
                        lastUpdatedDate: nowTimestamp(ec),
                ])

                Map<String, Object> file1Result = normalizeSourceResult(sourceExtractor.call(ec, automation, file1Source, window, input), file1Source)
                Map<String, Object> file2Result = normalizeSourceResult(sourceExtractor.call(ec, automation, file2Source, window, input), file2Source)

                if (!hasData(file1Result) || !hasData(file2Result)) {
                    Map<String, Object> noDataFields = executionUpdateFields(file1Result, file2Result, [:]) + [
                            statusEnumId    : STATUS_NO_DATA,
                            completedDate   : nowTimestamp(ec),
                            safeMetadataJson: safeMetadataJson([
                                    mode              : "API_DATE_RANGE",
                                    dataAvailable     : false,
                                    file1DataAvailable: hasData(file1Result),
                                    file2DataAvailable: hasData(file2Result),
                                    childWindowStart  : window.childWindowStartDate,
                                    childWindowEnd    : window.childWindowEndDate,
                            ]),
                            lastUpdatedDate : nowTimestamp(ec),
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
                        reconcileRunner.call(ec, automation, file1Source, file2Source, file1Result, file2Result, window, input)
                )
                ensureAutomationResultArtifact(ec, automation, file1Source, file2Source, reconcileResult, window, input)
                String resultDataManagerPath = normalizeDataManagerPath(ec,
                        reconcileResult.resultDataManagerPath ?: reconcileResult.diffLocation ?: reconcileResult.diffFileName)
                String reconciliationRunResultId = persistAutomationRunResult(ec, automation, file1Result, file2Result,
                        reconcileResult, resultDataManagerPath)

                Map<String, Object> successFields = executionUpdateFields(file1Result, file2Result, reconcileResult) + [
                        statusEnumId              : STATUS_SUCCEEDED,
                        completedDate             : nowTimestamp(ec),
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
                        lastUpdatedDate           : nowTimestamp(ec),
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
                Map<String, Object> failureFields = [
                        statusEnumId    : STATUS_FAILED,
                        completedDate   : nowTimestamp(ec),
                        errorMessage    : truncate(sanitizeErrorMessage(t), 3900),
                        errorDetail     : truncate(sanitizeErrorDetail(t), 12000),
                        safeMetadataJson: safeMetadataJson([
                                mode            : "API_DATE_RANGE",
                                childWindowStart: window.childWindowStartDate,
                                childWindowEnd  : window.childWindowEndDate,
                                errorMessage    : sanitizeErrorMessage(t),
                        ]),
                        lastUpdatedDate : nowTimestamp(ec),
                ]
                updateAutomationExecution(ec, execution, failureFields)
                executionResults << failureFields + [
                        automationExecutionId: automationExecutionId,
                        childWindowStartDate : window.childWindowStartDate,
                        childWindowEndDate   : window.childWindowEndDate,
                    ]
            }
        }

        return [
                automationId          : automationId,
                scheduledFireTime     : scheduledFireTime,
                executedCount         : executionResults.count { it.statusEnumId == STATUS_SUCCEEDED },
                noDataCount           : executionResults.count { it.statusEnumId == STATUS_NO_DATA },
                failedCount           : executionResults.count { it.statusEnumId == STATUS_FAILED },
                skippedDuplicateCount : executionResults.count { it.statusEnumId == STATUS_SKIPPED_DUPLICATE },
                executionResults      : executionResults,
        ]
    }

    static Map<String, Object> scanDueAutomations(def ec, Map params) {
        Map<String, Object> input = params ?: [:]
        Timestamp now = toTimestamp(input.nowTimestamp) ?: nowTimestamp(ec)
        int limit = toInteger(input.limit) ?: 100

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

        List<Map<String, Object>> scanResults = []
        dueAutomationEntries.each { Map<String, Object> dueEntry ->
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

            Map<String, Object> executeResult = callExecuteAutomationService(ec, executeParams)
            Timestamp nextFireTime = resolveNextScheduledFireTime(automation, scheduledFireTime, now)
            updateAutomation(ec, automation, [
                    lastScheduledFireTime: scheduledFireTime,
                    nextScheduledFireTime: nextFireTime,
                    lastUpdatedDate      : nowTimestamp(ec),
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
        int windowCount = toInteger(readField(automation, "relativeWindowCount")) ?: 1

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
            execution.set("createdDate", nowTimestamp(ec))
            execution.set("lastUpdatedDate", nowTimestamp(ec))
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
        Map<String, Object> metadata = resolveSourceExtractorMetadata(ec, source)
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

    protected static Map<String, Object> resolveSourceExtractorMetadata(def ec, def source) {
        Map<String, Object> metadata = parseJsonMap(readField(source, "safeMetadataJson"))
        if (normalize(metadata.extractServiceName ?: metadata.serviceName)) return metadata

        String systemEnumId = normalize(readField(source, "systemEnumId"))
        String companyUserGroupId = normalize(readField(source, "companyUserGroupId"))
        Map<String, Object> parameters = metadata.parameters instanceof Map ?
                new LinkedHashMap<>((Map<String, Object>) metadata.parameters) : [:]

        if (systemEnumId == OMS_SYSTEM_ENUM_ID) {
            String configId = normalize(parameters.omsRestSourceConfigId) ?: findSingleActiveOmsRestSourceConfigId(ec, companyUserGroupId)
            if (configId) {
                parameters.omsRestSourceConfigId = configId
                metadata.parameters = parameters
                metadata.extractServiceName = HOTWAX_OMS_ORDERS_EXTRACT_SERVICE
            }
        }
        if (systemEnumId == SHOPIFY_SYSTEM_ENUM_ID) {
            String configId = normalize(parameters.shopifyAuthConfigId) ?: findSingleActiveShopifyAuthConfigId(ec, companyUserGroupId)
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
                    ?.useCache(false)
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

    protected static List loadActiveAutomations(def ec) {
        return ec?.entity?.find("darpan.reconciliation.ReconciliationAutomation")
                ?.condition("isActive", "Y")
                ?.disableAuthz()
                ?.useCache(false)
                ?.list() ?: []
    }

    protected static def loadAutomation(def ec, String automationId) {
        def automation = ec?.entity?.find("darpan.reconciliation.ReconciliationAutomation")
                ?.condition("automationId", automationId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
        if (!automation) throw new IllegalArgumentException("Automation ${automationId} not found")
        return automation
    }

    protected static Map<String, Object> loadAutomationSources(def ec, String automationId) {
        List sources = ec?.entity?.find("darpan.reconciliation.ReconciliationAutomationSource")
                ?.condition("automationId", automationId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.list() ?: []
        return sources.collectEntries { source ->
            [(normalize(readField(source, "fileSide"))): source]
        } as Map<String, Object>
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
        result.recordCount = toInteger(result.recordCount)
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

    protected static String normalizeDataManagerPath(def ec, Object rawPath) {
        String normalized = normalize(rawPath)
        if (!normalized) return null

        String dataManagerLocation = DataManagerSupport.resolveDataManagerLocation(ec)
        if (normalized.startsWith(dataManagerLocation + "/")) {
            return DataManagerSupport.normalizeRelativePath(normalized.substring(dataManagerLocation.length() + 1))
        }

        String relativePath = DataManagerSupport.normalizeRelativePath(normalized)
        if (relativePath) return relativePath

        if (normalized.contains("://")) return null

        File root = DataManagerSupport.resolveDirectoryFile(ec, dataManagerLocation, false)
        if (root == null) return null

        File candidate = new File(normalized)
        try {
            def rootPath = root.canonicalFile.toPath()
            def candidatePath = candidate.canonicalFile.toPath()
            if (!candidatePath.startsWith(rootPath)) return null
            return rootPath.relativize(candidatePath).toString().replace(File.separator, "/")
        } catch (Exception ignored) {
            return null
        }
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
                file1RecordCount : toInteger(file1Result.recordCount),
                file2RecordCount : toInteger(file2Result.recordCount),
                differenceCount  : toInteger(reconcileResult.differenceCount),
                onlyInFile1Count : toInteger(reconcileResult.onlyInFile1Count ?: reconcileResult.missingInFile2Count),
                onlyInFile2Count : toInteger(reconcileResult.onlyInFile2Count ?: reconcileResult.missingInFile1Count),
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
            runResultValue.set("differenceCount", toInteger(reconcileResult.differenceCount))
            runResultValue.set("onlyInFile1Count", toInteger(reconcileResult.onlyInFile1Count ?: reconcileResult.missingInFile2Count))
            runResultValue.set("onlyInFile2Count", toInteger(reconcileResult.onlyInFile2Count ?: reconcileResult.missingInFile1Count))
            runResultValue.set("createdDate", nowTimestamp(ec))
            runResultValue.setSequencedIdPrimary()
            runResultValue.create()
            return normalize(readField(runResultValue, "reconciliationRunResultId"))
        }) as String
    }

    protected static void updateAutomationExecution(def ec, def execution, Map<String, Object> fields) {
        if (!execution) return
        runInTransaction(ec, "Error updating reconciliation automation execution", {
            fields.findAll { it.value != null }.each { entry ->
                execution.set(entry.key as String, entry.value)
            }
            execution.update()
            return null
        })
    }

    protected static void updateAutomation(def ec, def automation, Map<String, Object> fields) {
        if (!automation) return
        runInTransaction(ec, "Error updating reconciliation automation", {
            fields.each { entry -> automation.set(entry.key as String, entry.value) }
            automation.update()
            return null
        })
    }

    protected static Object runInTransaction(def ec, String message, Closure work) {
        if (ec?.transaction?.metaClass?.respondsTo(ec.transaction, "runUseOrBegin", Integer, String, Closure)) {
            return ec.transaction.runUseOrBegin(30, message, work)
        }
        return work.call()
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

    protected static String safeMetadataJson(Map<String, Object> metadata) {
        return truncate(JsonOutput.toJson(safeJsonValue(metadata)), 3900)
    }

    protected static Object safeJsonValue(Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) return value
        if (value instanceof Collection) return value.collect { safeJsonValue(it) }
        if (value instanceof Map) {
            return value.collectEntries { entry ->
                [(entry.key?.toString()): safeJsonValue(entry.value)]
            }
        }
        return value.toString()
    }

    protected static String currentUserId(def ec) {
        try {
            return TenantAccessSupport.currentUserId(ec)
        } catch (Exception ignored) {
            return normalize(ec?.user?.userId)
        }
    }

    protected static Timestamp nowTimestamp(def ec) {
        return ec?.user?.nowTimestamp ?: new Timestamp(System.currentTimeMillis())
    }

    protected static Integer toInteger(Object rawValue) {
        if (rawValue == null) return null
        if (rawValue instanceof Number) return rawValue.intValue()
        String normalized = normalize(rawValue)
        if (!normalized) return null
        return normalized.isInteger() ? normalized.toInteger() : null
    }

    protected static boolean toBoolean(Object rawValue) {
        if (rawValue == Boolean.TRUE) return true
        if (rawValue == Boolean.FALSE || rawValue == null) return false
        return ["Y", "true", "TRUE", "yes", "YES", "1"].contains(normalize(rawValue))
    }

    protected static String requireNormalized(Object value, String message) {
        String normalized = normalize(value)
        if (!normalized) throw new IllegalArgumentException(message)
        return normalized
    }

    protected static String fileNameFromPath(Object rawPath) {
        String normalized = normalize(rawPath)
        if (!normalized) return null
        return normalized.tokenize("/\\").last()
    }

    protected static String sanitizeErrorMessage(Throwable t) {
        return sanitizeText(t?.message ?: t?.class?.name ?: "Automation execution failed")
    }

    protected static String sanitizeErrorDetail(Throwable t) {
        if (t == null) return null

        StringBuilder detail = new StringBuilder()
        Throwable cursor = t
        int depth = 0
        while (cursor != null && depth < 8) {
            if (depth > 0) detail.append("\nCaused by: ")
            detail.append(cursor.class.name)
            if (cursor.message) detail.append(": ").append(cursor.message)
            cursor.stackTrace?.each { StackTraceElement element ->
                detail.append("\n    at ").append(element.toString())
            }
            cursor = cursor.cause
            depth++
        }
        return sanitizeText(detail.toString())
    }

    protected static String sanitizeText(String value) {
        return value?.replaceAll(/(?i)(password|privateKey|apiToken|token)\s*[:=]\s*[^,\s)]+/, "\$1=***")
    }

    protected static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value
        return value.substring(0, maxLength)
    }

    protected static String normalize(Object value) {
        return ((value)?.toString()?.trim())
    }

    protected static Object readField(def record, String fieldName) {
        if (record == null || !fieldName) return null
        if (record instanceof Map) return record[fieldName]
        if (record.metaClass.respondsTo(record, "get", String)) return record.get(fieldName)
        return record."${fieldName}"
    }
}
