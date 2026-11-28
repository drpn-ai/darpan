package darpan.reconciliation.automation

import darpan.audit.AuditAutomationSupport
import darpan.audit.AuditReportAssembler
import darpan.email.EmailCopyReader
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunObservability
import darpan.ops.ErrorChatSupport
import darpan.ops.RunErrorKinds
import darpan.reconciliation.notification.TenantNotificationSupport

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomationSources
import static darpan.reconciliation.automation.AutomationWindows.resolveZoneId
import static darpan.reconciliation.automation.AutomationWindows.toTimestamp

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). How a run's errors and quiet days are told (DAR-BE-015/362/378/390):
 * a run error's card goes to Darpan's own error space only; notify-me subscribers get the neutral copy line (DARPAN_ERROR_NOTICE),
 * never the error; a client chat room is never posted a run error. A quiet day tells the run's own destinations one plain line.
 * Never throws; a failed notify still drops the run's subscriptions.
 */
class AutomationRunNotifier {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AutomationRunNotifier)

    /** DAR-BE-015: the client's neutral line when a run errors (data/OpsNoticeSeedData.xml, editable in production). */
    static final String ERROR_NOTICE_TEMPLATE = "DARPAN_ERROR_NOTICE"

    static final String ERROR_NOTICE_NOT_RUN = "client.notrun"

    /** DAR-BE-362: the neutral line for a failed interactive saved run (not a morning run, so not client.notrun). */
    static final String ERROR_NOTICE_RUN_FAILED = "client.runfailed"

    /** DAR-BE-015 test seam: replaces ErrorChatSupport.postErrorCard, called with (ec, event). Null in production. */
    static Closure errorCardPoster = null

    /** DAR-BE-015 test seam: the EmailCopyReader for the client's neutral line, called with (ec). Null in production. */
    static Closure errorNoticeCopy = null

    /**
     * DAR-BE-015. The one path for a run error (FAILED, DEAD_LETTER, ONE_SIDE_EMPTY, KEPT_NOTHING; see RunErrorKinds): the card
     * goes to Darpan's own error space, one per audit check this automation runs or one for a non-audit automation. A client
     * destination never gets the error. Notify-me subscribers get only the neutral line (copy block DARPAN_ERROR_NOTICE /
     * client.notrun) on any run: they asked to be told. DAR-BE-378 (Aditi 2026-11-26): no client chat room is ever posted a
     * run error, not even the neutral line; the error space is where it goes.
     * Called for the terminal outcome only, never for an attempt that will be retried. Never throws: the run's own outcome
     * must reach the caller unchanged, and its subscriptions never outlive it.
     *
     * @param execution the execution row as it closed (statusEnumId, noDataKind, counts, retryCount, triggerType, dates)
     */
    static void notifyRunError(def ec, def automation, Map execution, String runResultId, String reason) {
        deliverRunError(ec, runResultId, ERROR_NOTICE_NOT_RUN) { ->
            String kind = RunErrorKinds.of(execution)
            if (kind == null) return null
            String trigger = AutomationExecutionSupport.triggerOf(execution)
            List<Map> subjects = null
            try {
                subjects = AuditAutomationSupport.errorSubjects(ec, automation)
            } catch (Throwable lookupError) {
                // Unknown whether this is an audit's automation: Darpan still gets a card and subscribers the line; the automation's space is not guessed at.
                logger.warn("Run error for automation {}: its audit checks could not be read ({})",
                        readField(automation, "automationId"), lookupError.getClass().name)
            }
            Map<String, Object> event = runErrorEvent(ec, automation, execution, kind, reason, runResultId, trigger)
            return [tenantId: normalize(readField(automation, "companyUserGroupId")), name: event.automationName,
                    cards   : (subjects ?: [[:]]).collect { Map subject -> event + subject }]
        }
    }

    /**
     * DAR-BE-362. The error path of a failed interactive saved run (run#SavedRunDiff, not an automation): Darpan's error space
     * gets one card (kind FAILED, trigger manual, the saved run's name, deduped per day on "savedRun:<savedRunId>" so it never
     * shares a slot with an automation of the same id); notify-me subscribers get only the neutral line (DARPAN_ERROR_NOTICE /
     * client.runfailed), never the error. The run result is claimed and its subscriptions purged as notifyRunLine does, so it
     * is told once. Never throws: the run's own outcome must reach the caller unchanged.
     *
     * @param run reconciliationRunResultId, companyUserGroupId, savedRunId, runName (blank falls back to savedRunId), resultDataManagerPath
     */
    static void notifySavedRunError(def ec, Map run, String reason) {
        deliverSavedRunError(ec, run, RunErrorKinds.FAILED, reason)
    }

    /**
     * DAR-OPS-029 S06 for S18 (OC19). A manual saved run that compared nothing closes as a scheduled one does (executeAutomation's
     * closeAsNoData), after its run result is NO_DATA. A did-not-run finding (AutomationNoDataJudge.closeFinding: ONE_SIDE_EMPTY or
     * KEPT_NOTHING) takes notifySavedRunError's route with the finding's kind and reason: one card to Darpan's error space, the
     * neutral client.runfailed line to notify-me subscribers. No finding is a quiet day: the client.nothing line ("Order sync · Apr 30:
     * nothing to compare (Shopify 0, HotWax 0)", the window's days in windowTimeZone, else the tenant's zone) to notify-me
     * subscribers only, never a chat room. Either way the run result is claimed, so it is told once. Never throws.
     *
     * @param run reconciliationRunResultId, companyUserGroupId, savedRunId, runName (blank falls back to savedRunId), resultDataManagerPath
     */
    static void notifySavedRunNoData(def ec, Map run, Map<String, String> finding, def file1Source, def file2Source,
                                     Map file1Result, Map file2Result, boolean evaluateMode,
                                     Timestamp windowStart, Timestamp windowEnd, String windowTimeZone) {
        if (finding != null) {
            deliverSavedRunError(ec, run, normalize(finding.kind) ?: RunErrorKinds.FAILED, finding.reason)
            return
        }
        String runResultId = normalize(run?.reconciliationRunResultId)
        try {
            String tenantId = normalize(run?.companyUserGroupId)
            ZoneId zone = normalize(windowTimeZone) ? resolveZoneId(windowTimeZone) :
                    (TenantNotificationSupport.resolveTenantZone(ec, tenantId) ?: ZoneId.of("UTC"))
            String line = quietDayLine(ec, normalize(run?.runName) ?: normalize(run?.savedRunId), windowStart, windowEnd, zone,
                    file1Source, file2Source, file1Result, file2Result, evaluateMode)
            TenantNotificationSupport.notifyRunLine(ec, tenantId, null, runResultId, line)
        } catch (Throwable notifyError) {
            logger.warn("Manual quiet day notification failed (best-effort): {}", notifyError.getClass().name)
            purgeAfterFailedNotify(ec, runResultId)
        }
    }

    /** DAR-BE-362: a saved run's error card (kind FAILED, or a no-data finding's kind) and its subscribers' neutral line. */
    private static void deliverSavedRunError(def ec, Map run, String kind, String reason) {
        String runResultId = normalize(run?.reconciliationRunResultId)
        deliverRunError(ec, runResultId, ERROR_NOTICE_RUN_FAILED) { ->
            String tenantId = normalize(run.companyUserGroupId)
            String savedRunId = normalize(run.savedRunId)
            String name = normalize(run.runName) ?: savedRunId
            Map<String, Object> event = [
                    tenantId      : tenantId,
                    tenantName    : tenantName(ec, tenantId),
                    subjectKey    : savedRunId ? "savedRun:${savedRunId}".toString() : runResultId,
                    automationName: name,
                    kind          : kind,
                    errorFirstLine: reason,
                    runUrl        : runErrorUrl(ec, [savedRunId: savedRunId, automationName: name, companyUserGroupId: tenantId],
                            [resultDataManagerPath: run.resultDataManagerPath], runResultId),
                    scheduled     : false,
                    trigger       : AutomationExecutionSupport.TRIGGER_MANUAL,
                    completedDate : ec.user.nowTimestamp,
                    // the card's day is the tenant's day (an interactive run has no automation zone)
                    zoneId        : TenantNotificationSupport.resolveTenantZone(ec, tenantId)?.id,
            ] as Map<String, Object>
            return [tenantId: tenantId, name: name, cards: [event]]
        }
    }

    /**
     * DAR-BE-362. A saved run's terminal notify (run#SavedRunDiff): a run whose rules did not fully evaluate (rulesFailed, or
     * the run result says AUT_STAT_FAILED) goes the error path, notifySavedRunError with ruleFailureReason as the card's
     * reason, so its subscribers never get the rule error or warnings; any other run gets the usual completion message.
     * A failed run whose cancel was requested meanwhile (the last cancel checkpoint is before the rule stage) ends CANCELLED:
     * like a cancelled automation (the guard in executeAutomation's rule-failure branch, cancelledExecutionResult) it posts no
     * card and tells no one, and its subscriptions go.
     */
    static void notifySavedRunCompleted(def ec, Map<String, Object> runResult, boolean rulesFailed) {
        if (rulesFailed || RunObservability.STATUS_FAILED == normalize(runResult?.statusEnumId)) {
            String runResultId = normalize(runResult?.reconciliationRunResultId)
            if (RunObservability.isCancelRequested(ec, runResultId)) {
                TenantNotificationSupport.purgeSubscriptionsForUnnotifiedRun(ec, runResultId)
                return
            }
            notifySavedRunError(ec, runResult, ruleFailureReason(runResult) ?: "rule execution failed")
        } else {
            TenantNotificationSupport.notifyRunCompleted(ec, runResult)
        }
    }

    /**
     * DAR-BE-015/362. The one tail of every run error. build returns what to send (null: nothing, the run had no error):
     * tenantId, name (the subscribers' line's ${name}) and cards (each posted to Darpan's error space; a failed post is logged
     * and never stops the rest). Then the neutral line (copy block blockId of DARPAN_ERROR_NOTICE) goes through notifyRunLine
     * to notify-me subscribers only, never a chat room (DAR-BE-378), which claims the run result and purges its subscriptions. Never throws; when anything fails the run's subscriptions still go, so none outlives the run.
     */
    private static void deliverRunError(def ec, String runResultId, String blockId, Closure<Map> build) {
        try {
            Map plan = build.call()
            if (plan == null) return
            Closure poster = errorCardPoster ?: { def postEc, Map postEvent -> ErrorChatSupport.postErrorCard(postEc, postEvent) }
            ((plan.cards ?: []) as List<Map>).each { Map card ->
                try {
                    poster.call(ec, card)
                } catch (Throwable postError) {
                    logger.warn("Run error card for {} failed: {}", card.automationId ?: card.subjectKey, postError.getClass().name)
                }
            }
            String line = noticeCopy(ec).text(blockId, [name: plan.name])
            TenantNotificationSupport.notifyRunLine(ec, plan.tenantId as String, null, runResultId, line)
        } catch (Throwable notifyError) {
            logger.warn("Run error notification failed (best-effort): {}", notifyError.getClass().name)
            purgeAfterFailedNotify(ec, runResultId)
        }
    }

    /** The neutral-line copy (DARPAN_ERROR_NOTICE), or the test seam's. */
    private static EmailCopyReader noticeCopy(def ec) {
        return errorNoticeCopy != null ? (EmailCopyReader) errorNoticeCopy.call(ec) : new EmailCopyReader(ec, ERROR_NOTICE_TEMPLATE)
    }

    /** A notify that failed still drops the run's subscriptions, so none outlives the run; a failed purge is logged, not thrown. */
    private static void purgeAfterFailedNotify(def ec, String runResultId) {
        try {
            TenantNotificationSupport.purgeSubscriptionsForUnnotifiedRun(ec, runResultId)
        } catch (Throwable purgeError) {
            logger.warn("Could not purge the subscriptions of run {} after a failed notify: {}", runResultId, purgeError.getClass().name)
        }
    }

    /** DAR-BE-390/393. The quiet-day line: the run's name, the window's days in zone, and what each side kept. */
    private static String quietDayLine(def ec, String name, Timestamp windowStart, Timestamp windowEnd, ZoneId zone,
                                       def file1Source, def file2Source, Map file1Result, Map file2Result, boolean evaluateMode) {
        List<String> sides = [sideName(ec, file1Source)] + (evaluateMode ? [] : [sideName(ec, file2Source)])
        return noticeCopy(ec).text("client.nothing", [name: name, day: windowDayWords(windowStart, windowEnd, zone),
                counts: AutomationNoDataJudge.quietDayCounts(sides, [file1Result, file2Result].take(sides.size()))])
    }

    /**
     * DAR-BE-390. A quiet day tells the destinations a finished run tells (the automation's chat space and notify-me
     * subscribers), once (notifyRunLine claims the run result and purges its subscriptions): one plain line, copy block
     * DARPAN_ERROR_NOTICE / client.nothing, "Daily Return Reconciliation · Nov 26: nothing to compare (HotWax 0, Shopify 0)",
     * the day in the automation's window zone. No card, no finding. Never throws; on any failure the subscriptions still go.
     */
    static void notifyQuietDay(def ec, def automation, def file1Source, def file2Source, Map window, String runResultId, boolean evaluateMode,
                               Map file1Result = null, Map file2Result = null) {
        try {
            String name = normalize(readField(automation, "automationName")) ?: normalize(readField(automation, "automationId"))
            String line = quietDayLine(ec, name, toTimestamp(window?.childWindowStartDate), toTimestamp(window?.childWindowEndDate),
                    resolveZoneId(readField(automation, "windowTimeZone")), file1Source, file2Source, file1Result, file2Result, evaluateMode)
            TenantNotificationSupport.notifyRunLine(ec, normalize(readField(automation, "companyUserGroupId")),
                    normalize(readField(automation, "chatSpaceId")), runResultId, line)
        } catch (Throwable notifyError) {
            logger.warn("Quiet day notification failed (best-effort): {}", notifyError.getClass().name)
            purgeAfterFailedNotify(ec, runResultId)
        }
    }

    /** A side's system as a client reads it ("HotWax", "Shopify"): the system source's name, else its root system key. */
    private static String sideName(def ec, def source) {
        String systemEnumId = normalize(readField(source, "systemEnumId"))
        return TenantNotificationSupport.resolveSystemSourceName(ec, systemEnumId) ?:
                AuditReportAssembler.systemKey(AuditAutomationSupport.rootSystem(systemEnumId)) ?: "Side"
    }

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

    /** DAR-BE-390. A window's days in zone, its end exclusive: "Nov 26", "Nov 20–26", "Oct 30–Nov 2"; "" without a start. */
    static String windowDayWords(Timestamp start, Timestamp end, ZoneId zone) {
        if (start == null) return ""
        LocalDate first = start.toInstant().atZone(zone).toLocalDate()
        LocalDate last = end != null && end.after(start) ? end.toInstant().minusNanos(1).atZone(zone).toLocalDate() : first
        if (!last.isAfter(first)) return MONTH_DAY.format(first)
        String to = last.month == first.month ? String.valueOf(last.dayOfMonth) : MONTH_DAY.format(last)
        return "${MONTH_DAY.format(first)}–${to}".toString()
    }

    /** DAR-BE-015: what a rule failure says for Darpan's card: the first rule error, else the first warning; null when neither. */
    static String ruleFailureReason(Map result) {
        String ruleError = ((result?.ruleErrors ?: []) as List).collect { normalize(it) }.find { it }
        return ruleError ?: ((result?.processingWarnings ?: []) as List).collect { normalize(it) }.find { it }
    }

    /** The card's facts (ErrorChatSupport.postErrorCard reads these); an audit check's names are laid over it per check. */
    static Map<String, Object> runErrorEvent(def ec, def automation, Map execution, String kind, String reason,
            String runResultId, String trigger) {
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        String automationName = normalize(readField(automation, "automationName")) ?: normalize(readField(automation, "automationId"))
        int tries = (normalizeInt(execution.retryCount) ?: 0) + 1
        Map<String, Map<String, Object>> counts = runErrorCounts(ec, automation, execution)
        return [
                tenantId      : tenantId,
                tenantName    : tenantName(ec, tenantId),
                automationId  : normalize(readField(automation, "automationId")),
                automationName: automationName,
                kind          : kind,
                attempts      : tries > 1 ? tries : null,
                errorFirstLine: reason ?: normalize(execution.errorMessage),
                readCounts    : counts.read,
                keptCounts    : counts.kept,
                runUrl        : runErrorUrl(ec, automation, execution, runResultId),
                scheduled     : trigger == AutomationExecutionSupport.TRIGGER_SCHEDULED,
                trigger       : trigger,
                scheduledDate : toTimestamp(execution.scheduledDate),
                completedDate : toTimestamp(execution.completedDate),
                zoneId        : normalize(readField(automation, "windowTimeZone")),
                executionId   : normalize(execution.automationExecutionId),
        ] as Map<String, Object>
    }

    /**
     * Each side's records read (the recent read) and kept, labelled by its system; two sides on one system are told apart by
     * number. A side that recorded only what it extracted (a run that failed before Keep) shows that as its read. Null when no
     * side recorded a count.
     */
    protected static Map<String, Map<String, Object>> runErrorCounts(def ec, def automation, Map execution) {
        Map<String, Object> sources = [:]
        try { sources = loadAutomationSources(ec, normalize(readField(automation, "automationId"))) ?: [:] } catch (Throwable ignored) { }
        List<String> labels = [AutomationExecutionSupport.FILE_SIDE_1, AutomationExecutionSupport.FILE_SIDE_2].collect { String side ->
            String system = AuditAutomationSupport.rootSystem(normalize(readField(sources[side], "systemEnumId")))
            system ? AuditReportAssembler.systemKey(system) : (side == AutomationExecutionSupport.FILE_SIDE_1 ? "Side 1" : "Side 2")
        }
        if (labels[0] == labels[1]) labels = [labels[0] + " 1", labels[1] + " 2"]
        Map<String, Object> read = [:]
        Map<String, Object> kept = [:]
        ["file1", "file2"].eachWithIndex { String prefix, int i ->
            Integer readCount = normalizeInt(execution[prefix + "ReadCount"])
            Integer recordCount = normalizeInt(execution[prefix + "RecordCount"])
            if (readCount != null) {
                read[labels[i]] = readCount
                if (recordCount != null) kept[labels[i]] = recordCount
            } else if (recordCount != null) {
                read[labels[i]] = recordCount
            }
        }
        return [read: read ?: null, kept: kept ?: null]
    }

    /** The run's page (its result, or the stages of a run that failed), else the result file's page when no run is known. */
    protected static String runErrorUrl(def ec, def automation, Map execution, String runResultId) {
        try {
            return TenantNotificationSupport.buildRunResultUrl(ec, [savedRunId: normalize(readField(automation, "savedRunId")),
                    reconciliationRunResultId: runResultId, resultDataManagerPath: execution?.resultDataManagerPath,
                    runName: readField(automation, "automationName"), companyUserGroupId: readField(automation, "companyUserGroupId")])
        } catch (Throwable ignored) {
            return null
        }
    }

    private static String tenantName(def ec, String tenantId) {
        if (!tenantId) return null
        try {
            def group = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup",
                    "DAR-BE-015 run error card: the tenant's display name for Darpan's own error space").condition("userGroupId", tenantId)
                    .useCache(true).one()
            return normalize(readField(group, "description")) ?: tenantId
        } catch (Throwable ignored) {
            return tenantId
        }
    }

    /** DAR-OPS-026: the notify seams back to production. Test-only. */
    static void resetForTests() {
        errorCardPoster = null
        errorNoticeCopy = null
    }
}
