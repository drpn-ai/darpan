package darpan.audit

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.DiffDocumentStreamSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.conclusion.ConclusionTreeSupport
import darpan.reconciliation.conclusion.RunConclusionSupport
import groovy.json.JsonSlurper
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorDetail
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeText
import static darpan.reconciliation.automation.AutomationRuntimeSupport.truncate

/**
 * DAR-BE-092. The scheduled side of an audit run: an automation that runs an audit's checks (AuditCheck.automationId)
 * reads the audit's windows instead of its own, and after each window writes the ledger: health (record#CheckRun),
 * then findings (record#CheckFindings), then closings (close#ResolvedFindings), in that order.
 *
 * Windows: the run the audit owes that morning (audit.AuditReportServices.get#AuditWindow): the current period to
 * date. "On the 14th it reconciles 1-14."
 *
 * Findings: each row of the run's diff document is matched to the statements the check tests, through the
 * statement-to-check link's diffTypes and ruleIds, less the rows whose cause is expected (configuration, not code). Examined records are the keys
 * both sides held (file1/file2KeysOutLocation), so a record found on either side may close its finding.
 *
 * Tenant: the scheduler runs as _NA_ with no active tenant, so every read here carries an explicit
 * companyUserGroupId and every ledger call goes through AutomationExecutionSupport.dispatchInternalService, the one
 * audited authz-relaxed seam on the automation path. The ledger services themselves re-check that each check
 * belongs to the tenant.
 *
 * Best effort: a ledger write that fails is logged and never changes how the run itself ended.
 */
class AuditAutomationSupport {

    private static final Logger logger = LoggerFactory.getLogger(AuditAutomationSupport.class)
    private static final String LEDGER = "audit.AuditLedgerServices"
    /** The ledger's reason columns are text-intermediate (1023). */
    private static final int REASON_MAX = 1000
    private static final String WHY = "audit run hook on a scheduled thread: explicit companyUserGroupId condition applied"
    /** DAR-OPS-029 S11: why a check whose compare left no readable result closes nothing. */
    static final String NO_RESULT_REASON = "The compare finished but its result could not be read, so nothing was judged."
    /** D11: at most this many outside-rules rows are handed to the ledger; the count is the true total. */
    static final int OUTSIDE_RULES_ROWS = 200
    /** DAR-OPS-029 S11: why a check whose findings the ledger refused reads as not run, never as ran with none. */
    static final String FINDINGS_NOT_RECORDED_REASON = "The check ran but its findings could not be recorded."

    /** OC47: a compare's result document that cannot be read whole (DiffDocumentStreamSupport.DiffRowScan.problem()). */
    static class IncompleteResultException extends IllegalStateException {
        IncompleteResultException(String problem) { super(problem ?: "The result file could not be read whole.") }
    }

    /** Every ledger call goes through here, so a test can refuse one. */
    private static final Closure<Map> DEFAULT_LEDGER = { def ec, String serviceName, Map params ->
        AutomationExecutionSupport.dispatchInternalService(ec, serviceName, params)
    }
    static Closure<Map> ledger = DEFAULT_LEDGER

    /** Test-only: restores the ledger seam. Idempotent; never called from src/main. */
    static void resetForTests() {
        ledger = DEFAULT_LEDGER
    }

    /**
     * The audit checks this automation runs; empty when it feeds no audit. A check on no statement is retired (an import or
     * restore takes a check off its statements and leaves its automation, DAR-BE-332): it reports nowhere, so it is not run.
     */
    static List<Map> checksFor(def ec, def automation) {
        String automationId = normalize(readField(automation, "automationId"))
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        if (!automationId || !tenantId) return []
        List<Map> checks = tenantRows(ec, "darpan.audit.AuditCheck", tenantId).condition("automationId", automationId).list()*.getMap()
        if (!checks) return []
        Set<String> tested = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .condition("checkId", EntityCondition.IN, checks*.checkId).list()*.checkId.collect { it as String } as Set<String>
        return checks.findAll { (it.checkId as String) in tested }
    }

    /**
     * DAR-BE-015. What a Darpan error card names for each check this automation runs, in checkCode order: [auditId, auditName,
     * statementId (the codes of the statements it tests, e.g. "R1, R2"), checkId (its checkCode)]. Empty when it feeds no audit.
     */
    static List<Map> errorSubjects(def ec, def automation) {
        List<Map> checks = checksFor(ec, automation)
        if (!checks) return []
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        List<Map> links = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .condition("checkId", EntityCondition.IN, checks*.checkId).list()*.getMap()
        List statementIds = links*.statementId.unique()
        Map<String, Map> statements = !statementIds ? [:] : tenantRows(ec, "darpan.audit.AuditStatement", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds).list()*.getMap()
                .collectEntries { Map s -> [(s.statementId as String): s] }
        List auditIds = statements.values()*.auditId.unique()
        Map<String, Map> audits = !auditIds ? [:] : tenantRows(ec, "darpan.audit.Audit", tenantId)
                .condition("auditId", EntityCondition.IN, auditIds).list()*.getMap()
                .collectEntries { Map a -> [(a.auditId as String): a] }
        return checks.sort(false) { it.checkCode as String }.collect { Map check ->
            // DAR-BE-398: get, not [x as String]: Groovy 5 reads a subscript on a null String as a property named null and throws.
            List<Map> tested = links.findAll { it.checkId == check.checkId }.collect { statements.get(it.statementId as String) }.findAll()
            Map audit = tested ? audits.get(tested[0].auditId as String) : null
            [auditId    : audit?.auditId, auditName: audit?.auditName,
             statementId: (tested*.statementCode as List<String>).sort().join(", "), checkId: check.checkCode] as Map
        }
    }

    /** The one audit these checks report to. An automation runs one audit's checks; anything else is a config error. */
    static Map auditFor(def ec, String tenantId, List<Map> checks) {
        List<String> statementIds = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .condition("checkId", EntityCondition.IN, checks*.checkId).list()*.statementId.unique() as List<String>
        List<String> auditIds = statementIds ? tenantRows(ec, "darpan.audit.AuditStatement", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds).list()*.auditId.unique() as List<String> : []
        if (auditIds.size() != 1) {
            throw new IllegalStateException("Audit checks ${checks*.checkCode} report to ${auditIds.size()} audits; " +
                    "an automation runs the checks of exactly one audit.")
        }
        return tenantRows(ec, "darpan.audit.Audit", tenantId).condition("auditId", auditIds[0]).list().find()?.getMap()
    }

    /**
     * The windows of one morning, in the automation's zone, each end exclusive: [period start, the day after the
     * run's last day). Each window carries its audit period and report day for the ledger. DAR-BE-145: a window starts
     * at the tenant's go-live at the earliest, and one that ends by then is not read at all.
     */
    static List<Map<String, Object>> resolveWindows(def ec, def automation, Map audit, Timestamp scheduledFireTime,
                                                   Map explicit = null) {
        ZoneId zone = zoneOf(automation)
        LocalDate reportDay = scheduledFireTime.toInstant().atZone(zone).toLocalDate()
        // DAR-BE-122: reads build on the checks' last complete read; a full read only when asked (auditFullRead).
        List<String> checkIds = explicit?.auditFullRead == true || explicit?.auditVerifyKeys ? null :
                (checksFor(ec, automation)*.checkId.collect { it as String })
        // DAR-BE-123: a verify reads its findings' records by key in each window (see AutomationExecutionSupport).
        Map verify = explicit?.auditVerifyKeys ? [auditVerifyKeys: explicit.auditVerifyKeys] : [:]
        if (explicit?.auditWindowFromDate && explicit?.auditWindowThruDate) {
            return pickedWindows(ec, audit, zone, reportDay, toLocalDate(explicit.auditWindowFromDate), toLocalDate(explicit.auditWindowThruDate),
                    checkIds, verify)
        }
        Map out = AutomationExecutionSupport.dispatchInternalService(ec, "audit.AuditReportServices.get#AuditWindow",
                [companyUserGroupId: audit.companyUserGroupId, auditId: audit.auditId, reportDate: Date.valueOf(reportDay)])
        if (ec.message.hasError()) {
            String errors = ec.message.errorsString
            ec.message.clearErrors()
            throw new IllegalStateException("Audit ${audit.auditCode}: ${sanitizeText(errors)}")
        }
        Timestamp goLive = goLive(ec, audit.companyUserGroupId as String)
        List<Map<String, Object>> windows = []
        ((out.runs ?: []) as List<Map>).each { Map run ->
            Timestamp end = Timestamp.from((run.windowThruDate as Date).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant())
            Timestamp start = fromGoLive(Timestamp.from((run.windowFromDate as Date).toLocalDate().atStartOfDay(zone).toInstant()), end, goLive)
            if (start == null) return
            windows << window(windows.size() + 1, start, end, audit, run.period as String, reportDay,
                    readStart(ec, audit, checkIds, run.period as String, start, end, zone), verify)
        }
        return windows
    }

    /** One window of a morning or a play: its read [start, end), and what the ledger records it under. */
    private static Map<String, Object> window(int sequenceNum, Timestamp start, Timestamp end, Map audit, String period,
                                              LocalDate reportDay, Timestamp recentReadStart, Map extra) {
        return ([sequenceNum             : sequenceNum,
                 windowStartDate         : start,
                 windowEndDate           : end,
                 childWindowStartDate    : start,
                 childWindowEndDate      : end,
                 auditId                 : audit.auditId,
                 auditPeriod             : period,
                 auditReportDate         : Date.valueOf(reportDay),
                 auditRecentReadStartDate: recentReadStart] + extra) as Map<String, Object>
    }

    /**
     * DAR-UI-063. The days a person picked to play, first to last inclusive, in the automation's zone: one window per audit
     * period they cross, each dated as picked and recorded under its own period, reported on the run's own morning. Each
     * period must still be recordable that morning (DAR-BE-178: the morning's own, or the one just ended); otherwise the
     * run is refused before anything is read, because the ledger would refuse each check's write and the run would record
     * nothing. A window that ends by the tenant's go-live is left out; a window it cuts starts at go-live (DAR-BE-145).
     * verify: a verify's keys (DAR-BE-123), carried on every window so a verify over picked days writes no check run either.
     */
    static List<Map<String, Object>> pickedWindows(def ec, Map audit, ZoneId zone, LocalDate reportDay, LocalDate from,
                                                   LocalDate thru, List<String> checkIds = null, Map verify = [:]) {
        verify = verify ?: [:]   // AuditSettingsSupport validates a play's days with the six-argument form
        if (from > thru) throw new IllegalArgumentException("The days run backwards: ${from} to ${thru}.")
        if (thru > reportDay) throw new IllegalArgumentException("${thru} has not ended yet; pick days up to ${reportDay}.")
        String periodCode = AuditReportAssembler.enumCodes(ec).get(audit.periodEnumId as String)
        List<Map> periods = []
        for (LocalDate day = from; day <= thru; ) {
            String period = AuditReportAssembler.periodOf(day, periodCode)
            Map w = AuditReportAssembler.periodWindow(ec, audit, period, reportDay)
            if (w == null || !w.recordable) {
                throw new IllegalStateException("${period} has ended; pick days from ${AuditReportAssembler.recordableFrom(reportDay, periodCode)}.")
            }
            LocalDate last = [thru, w.end as LocalDate].min()
            periods << [period: w.period, from: day, thru: last]
            day = last.plusDays(1)
        }
        Timestamp goLive = goLive(ec, audit.companyUserGroupId as String)
        List<Map<String, Object>> windows = []
        periods.each { Map p ->
            Timestamp end = Timestamp.from((p.thru as LocalDate).plusDays(1).atStartOfDay(zone).toInstant())
            Timestamp start = fromGoLive(Timestamp.from((p.from as LocalDate).atStartOfDay(zone).toInstant()), end, goLive)
            if (start == null) return
            windows << window(windows.size() + 1, start, end, audit, p.period as String, reportDay,
                    readStart(ec, audit, checkIds, p.period as String, start, end, zone),
                    [auditWindowFromDate: Date.valueOf(p.from as LocalDate), auditWindowThruDate: Date.valueOf(p.thru as LocalDate)] + verify)
        }
        if (!windows) {
            throw new IllegalStateException("Nothing before go-live (${goLiveText(goLive, zone)}) is audited; pick days from ${goLiveDay(goLive, zone)}.")
        }
        return windows
    }

    /** DAR-BE-145. When the tenant went live (TenantSetting.goLiveDate); null when it has no go-live. */
    static Timestamp goLive(def ec, String tenantId) {
        return tenantRows(ec, "darpan.auth.TenantSetting", tenantId).list().find()?.goLiveDate as Timestamp
    }

    /** DAR-BE-145. A window's start moved up to go-live; null when the window ends by go-live, so nothing in it is read. */
    static Timestamp fromGoLive(Timestamp start, Timestamp end, Timestamp goLive) {
        if (goLive == null || !goLive.after(start)) return start
        return goLive.before(end) ? goLive : null
    }

    /** DAR-BE-145. The calendar day of go-live in {@code zone}: the first day an audit can report on. */
    static LocalDate goLiveDay(Timestamp goLive, ZoneId zone) {
        return goLive.toInstant().atZone(zone).toLocalDate()
    }

    private static String goLiveText(Timestamp goLive, ZoneId zone) {
        return goLive.toInstant().atZone(zone).toLocalDateTime().toString().replace('T', ' ')
    }

    /** DAR-BE-122. A window's read start from its checks' last complete runs in the period (null: read the whole window). */
    private static Timestamp readStart(def ec, Map audit, List<String> checkIds, String period, Timestamp start, Timestamp end, ZoneId zone) {
        if (!checkIds) return null
        Map<String, java.util.Date> lastCovered = [:]
        tenantRows(ec, "darpan.audit.AuditCheckRun", audit.companyUserGroupId as String)
                .condition("checkId", EntityCondition.IN, checkIds).condition("period", period).condition("statusEnumId", "AUDCS_RAN")
                .list().each { def r ->
                    java.util.Date thru = r.windowThruDate as java.util.Date
                    java.util.Date seen = lastCovered.get(r.checkId as String)
                    if (seen == null || thru.after(seen)) lastCovered.put(r.checkId as String, thru)
                }
        return AuditRecentReadSupport.incrementalStart(lastCovered, checkIds, start, end, zone)
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof Date) return ((Date) value).toLocalDate()
        if (value instanceof java.util.Date) return new Date(((java.util.Date) value).time).toLocalDate()
        return LocalDate.parse(value.toString().trim().substring(0, 10))
    }

    /** Two key files for one window's compare; the caller deletes them with {@link #discardKeyFiles}. */
    static void attachKeyFiles(Map<String, Object> window) {
        File dir = Files.createTempDirectory("audit-keys-").toFile()
        window.auditKeysDir = dir.absolutePath
        window.auditFile1KeysOut = new File(dir, "file1-keys.txt").absolutePath
        window.auditFile2KeysOut = new File(dir, "file2-keys.txt").absolutePath
    }

    static void discardKeyFiles(Map<String, Object> window) {
        String dir = window?.auditKeysDir as String
        if (dir) new File(dir).deleteDir()
    }

    /**
     * DAR-BE-393. Empties the keys the compare listed as examined (auditFile1KeysOut, auditFile2KeysOut), so a window that
     * closes as a quiet day records its check as ran but closes no finding: nothing was compared (a side kept nothing).
     */
    static void forgetExaminedKeys(Map<String, Object> window) {
        [window?.auditFile1KeysOut, window?.auditFile2KeysOut].each { Object location ->
            if (location) new File(location as String).delete()
        }
    }

    /**
     * After one window: health, findings, closings for every check the automation runs. A side with no result was
     * not read: the side that threw failed, a side never reached failed for that reason. Reads that completed but
     * whose compare failed make the check notRun, so nothing closes. noDataReason (DAR-BE-015) does the same for a
     * NO_DATA window where one side read nothing or Keep kept nothing.
     */
    static void recordWindow(def ec, def automation, List<Map> checks, Map<String, Object> window,
                             def file1Source, def file2Source, Map file1Result, Map file2Result,
                             Map reconcileResult, Throwable failure, String automationExecutionId = null,
                             Map<String, String> sideConfigIds = null, String noDataReason = null) {
        if (!checks || !window?.auditPeriod) return
        List<String> priorErrors = ec.message.hasError() ? new ArrayList<String>(ec.message.errors) : []
        ec.message.clearErrors()
        try {
            String tenantId = normalize(readField(automation, "companyUserGroupId"))
            Timestamp now = new Timestamp(System.currentTimeMillis())
            // F-E5 (Aditi 2026-11-28): a replay's reads are recorded at the time they were read, not now.
            String replayedFrom = normalize(file1Result?.replayedFromExecutionId) ?: normalize(file2Result?.replayedFromExecutionId)
            Timestamp replayReadAt = replayedFrom ? replayReadStamp(ec, tenantId, replayedFrom) : null
            List<Map> reads = sourceReads(file1Source, file2Source, file1Result, file2Result, failure, now,
                    [sideConfigIds: sideConfigIds, reconcileResult: reconcileResult,
                     readStamps: [FILE_1: file1Result?.replayedFromExecutionId ? replayReadAt : null,
                                  FILE_2: file2Result?.replayedFromExecutionId ? replayReadAt : null]])
            String notRunReason = failure != null && file1Result != null && file2Result != null ?
                    truncate("The compare failed: ${sanitizeErrorMessage(failure)}".toString(), REASON_MAX) : null
            // DAR-BE-015: a window that ended NO_DATA with a side empty or kept nothing did not run; it closes nothing.
            if (notRunReason == null && noDataReason) notRunReason = truncate(noDataReason, REASON_MAX)
            File diff = reconcileResult && failure == null ? AutomationExecutionSupport.resolveDiffFile(ec, reconcileResult) : null
            // A compare that ran and left no readable result judged nothing: with no rows, every examined key would read
            // as fixed, so the check did not run and closes nothing.
            if (notRunReason == null && reconcileResult && failure == null && diff == null) notRunReason = NO_RESULT_REASON
            // DAR-BE-142: a Set, deduped once. List.unique() compares every key with every key kept so far: gorjana's
            // Shopify vs HotWax window (189k keys, two checks) held a worker thread for minutes after the run succeeded.
            Set<String> examined = new LinkedHashSet<String>(readKeys(window.auditFile1KeysOut as String))
            examined.addAll(readKeys(window.auditFile2KeysOut as String))
            checks.each { Map check ->
                // F-E5 (Aditi 2026-11-28): a replay never overwrites what a later run of the check already recorded.
                if (replayedFrom && laterRunRecorded(ec, tenantId, [check.checkId as String], replayReadAt, replayedFrom)) {
                    logger.warn("Audit check ${check.checkCode}: replay of execution ${replayedFrom} (read ${replayReadAt}) refused; " +
                            "a later run of the check has already recorded")
                    return
                }
                // One check's failure never skips the window's other checks.
                try {
                    Set<String> unjudged = unjudgedKeys(ec, automation, check, window, diff, file1Source, file2Source)
                    List<String> judged = unjudged ? examined.findAll { !unjudged.contains(it) } as List<String> : new ArrayList<String>(examined)
                    recordCheck(ec, tenantId, check, window, reads, notRunReason, diff, file1Source, file2Source,
                            judged, now, unjudged, automationExecutionId, sideConfigIds)
                } catch (Exception e) {
                    logger.error("Audit ledger write failed for check ${check.checkId} window ${window.auditPeriod}: ${sanitizeErrorDetail(e)}")
                    ec.message.clearErrors()
                }
            }
        } catch (Exception e) {
            logger.error("Audit ledger write failed for automation ${readField(automation, 'automationId')} window ${window.auditPeriod}: ${sanitizeErrorDetail(e)}")
        } finally {
            ec.message.clearErrors()
            priorErrors.each { ec.message.addError(it) }
        }
    }

    /**
     * DAR-BE-116 phase 1. The keys of rows only the NON-OMS side holds whose own date is before the judged days (the recent
     * OMS read plus AuditRecentReadSupport.EDGE_SLACK_DAYS): their OMS twin may simply be older than the read, so they raise
     * no finding and, being left out of the examined keys, close none. Empty when OMS was read for the whole window, or
     * both or neither side is OMS.
     */
    static Set<String> unjudgedKeys(def ec, def automation, Map check, Map window, File diff, def file1Source, def file2Source) {
        Timestamp recent = window?.auditRecentReadStartDate as Timestamp
        if (recent == null || diff == null) return [] as Set
        boolean oms1 = AuditRecentReadSupport.sourceReadsRecentOnly(ec, file1Source)
        boolean oms2 = AuditRecentReadSupport.sourceReadsRecentOnly(ec, file2Source)
        if (oms1 == oms2) return [] as Set
        // Only the other side holds it: OMS is FILE_1, so the row is missing in FILE_1, and the reverse.
        String otherSideOnly = oms1 ? "MISSING_IN_FILE_1" : "MISSING_IN_FILE_2"
        ZoneId zone = zoneOf(automation)
        Timestamp judgedFrom = AuditRecentReadSupport.judgedFrom(recent)
        String dateField = normalize(check.otherSideDateField)
        Set<String> keys = new LinkedHashSet<>()
        DiffDocumentStreamSupport.forEachRow(diff) { Map raw ->
            if (((raw.diffType ?: raw.type) as String) != otherSideOnly) return
            String id = (raw.primaryId ?: raw.id) as String
            if (id && !AuditRecentReadSupport.judged(AuditAutomationSupport.rowData(raw.data), dateField, judgedFrom, zone)) keys.add(id)
        }
        if (keys) logger.info("Audit check ${check.checkCode}: ${keys.size()} record(s) only the other system holds are dated before ${judgedFrom} (recent OMS read from ${recent}); not judged this run")
        return keys
    }

    private static void recordCheck(def ec, String tenantId, Map check, Map window, List<Map> reads, String notRunReason,
                                    File diff, def file1Source, def file2Source, List<String> examined, Timestamp now,
                                    Set<String> unjudged, String automationExecutionId, Map<String, String> sideConfigIds = null) {
        Map base = [companyUserGroupId: tenantId, checkId: check.checkId, reportDate: window.auditReportDate,
                    period: window.auditPeriod]
        // DAR-UI-063. Played days keep their own dates; the morning run's are the period to date (the ledger's default).
        Map dates = window.auditWindowFromDate ? [windowFromDate: window.auditWindowFromDate, windowThruDate: window.auditWindowThruDate] : [:]
        // DAR-BE-123: a verify writes no check run: it moves neither the check's health nor its incremental read. As
        // record#CheckRun would, a read not complete makes it partial, which closes nothing (DAR-BE-124).
        Map runParams = base + dates + [runStamp: now, sourceReads: reads, automationExecutionId: automationExecutionId]
        Map run = !window.auditVerifyKeys ? call(ec, "${LEDGER}.record#CheckRun", runParams + [notRunReason: notRunReason]) :
                verifyStatus(ec, reads, notRunReason)
        if (run == null || run.checkStatusEnumId == "AUDCS_NOT_RUN") return
        Map<String, Map> explained = [:]
        Set<String> unjudgedStatementIds = new LinkedHashSet<String>()
        Map outsideRules = [count: 0, rows: []]
        List<Map> findings = null
        Map recorded = null
        String notRecordedReason = FINDINGS_NOT_RECORDED_REASON
        // From here the check's health reads ran (or partial): any failure to judge or record its findings restates it.
        try {
            findings = diff != null ? findingsFrom(ec, tenantId, check, diff, file1Source, file2Source, explained, sideConfigIds,
                    unjudgedStatementIds, outsideRules, unjudged).findAll { !unjudged.contains(it.recordKey as String) } : []
            // D11: the outside-rules group rides with the findings; the ledger stores it on the morning's run (S13).
            recorded = call(ec, "${LEDGER}.record#CheckFindings", base + [checkStamp: now, findings: findings, outsideRules: outsideRules])
        } catch (IncompleteResultException e) {
            logger.error("Audit check ${check.checkId} (${window.auditPeriod}): ${e.message} Nothing judged.")
            notRecordedReason = truncate(e.message, REASON_MAX)
            recorded = null
        } catch (Exception e) {
            logger.error("Audit findings for check ${check.checkId} (${window.auditPeriod}) not recorded: ${sanitizeErrorDetail(e)}")
            ec.message.clearErrors()
            recorded = null
        }
        if (recorded == null) {
            // The check's health is already written as ran (or partial): restate it as not run, so a morning whose findings
            // were lost never reads as a clean one. A verify wrote no health, so there is nothing to restate.
            if (!window.auditVerifyKeys) call(ec, "${LEDGER}.record#CheckRun", runParams + [notRunReason: notRecordedReason])
            return
        }
        // DAR-BE-118: closes what THIS run examined and did not report, so a later run the same morning closes too. OC33 (S13):
        // by finding, not by record (a record still breaking one statement never keeps another's fixed finding open), and
        // never a statement this run did not judge.
        call(ec, "${LEDGER}.close#ResolvedFindings", base + [checkStamp: now, checkStatusEnumId: run.checkStatusEnumId,
                                                            examinedRecordKeys: examined,
                                                            reportedFindingIds: (recorded.findingIds ?: []) as List,
                                                            unjudgedStatementIds: unjudgedStatementIds as List,
                                                            explanations: explained])
    }

    /**
     * DAR-BE-123. A verify's status as record#CheckRun would derive it, without writing a run: not run when it has a reason
     * or no read, or when a read names a system that is not a DarpanSystemSource (record#CheckRun refuses those); partial
     * when any read is not complete; else ran.
     */
    static Map verifyStatus(def ec, List<Map> reads, String notRunReason) {
        if (notRunReason || !reads) return null
        boolean known = reads.every { Map r ->
            ec.entity.find("moqui.basic.Enumeration").condition("enumId", r.systemEnumId).useCache(true).one()?.enumTypeId == "DarpanSystemSource"
        }
        if (!known || !reads.every { it.statusEnumId in ["AUDSR_COMPLETE", "AUDSR_PARTIAL", "AUDSR_FAILED"] }) return null
        return [checkStatusEnumId: reads.every { it.statusEnumId == "AUDSR_COMPLETE" } ? "AUDCS_RAN" : "AUDCS_PARTIAL"]
    }

    /**
     * F-E5 (Aditi 2026-11-28). When a replayed extract was read: the start of the execution that read it (its reads came
     * after). Null when the execution or its start cannot be found, which refuses the replay.
     */
    static Timestamp replayReadStamp(def ec, String tenantId, String executionId) {
        def execution = tenantRows(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION, tenantId)
                .condition("automationExecutionId", executionId).list().find()
        Object at = execution?.startedDate ?: execution?.createdDate
        return at instanceof java.util.Date ? new Timestamp(((java.util.Date) at).time) : null
    }

    /**
     * F-E5 (Aditi 2026-11-28). Whether a run of any of these checks recorded after readAt, other than the run the replayed
     * execution itself recorded: a replay of an older read must not overwrite it. An unknown readAt counts as recorded.
     */
    static boolean laterRunRecorded(def ec, String tenantId, List<String> checkIds, Timestamp readAt, String replayedFromExecutionId) {
        if (readAt == null) return true
        if (!checkIds) return false
        return tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId).condition("checkId", EntityCondition.IN, checkIds)
                .condition("runStamp", EntityCondition.GREATER_THAN, readAt).list()
                .any { normalize(it.automationExecutionId) != replayedFromExecutionId }
    }

    /** One ledger call; on an error, log it and answer null so the next step for this check is skipped. */
    private static Map call(def ec, String serviceName, Map params) {
        Map out = ledger.call(ec, serviceName, params)
        if (ec.message.hasError()) {
            logger.error("Audit ledger ${serviceName} for check ${params.checkId} (${params.period}) refused: ${sanitizeText(ec.message.errorsString)}")
            ec.message.clearErrors()
            return null
        }
        return out ?: [:]
    }

    /** One read per side, as record#CheckRun takes them; context.readStamps (side -> when it was read) for a replay, else now. */
    static List<Map> sourceReads(def file1Source, def file2Source, Map file1Result, Map file2Result, Throwable failure,
                                 Timestamp now, Map context = null) {
        String why = failure ? truncate(sanitizeErrorMessage(failure), REASON_MAX) : null
        String system1 = normalize(readField(file1Source, "systemEnumId"))
        String system2 = normalize(readField(file2Source, "systemEnumId"))
        List<Map> reads = []
        Map stamps = (Map) (context?.readStamps ?: [:])
        reads << readOf(system1, file1Result, (Timestamp) stamps.get("FILE_1") ?: now, why) + readStats("FILE_1", file1Result, context)
        reads << readOf(system2, file2Result, (Timestamp) stamps.get("FILE_2") ?: now,
                file1Result == null && failure ? "Not read: the ${system1} read failed first.".toString() : why) +
                readStats("FILE_2", file2Result, context)
        return reads.findAll { it.systemEnumId }
    }

    /**
     * DAR-BE-320 phase 1b. What one side's read cost and covered, for the audit's read report: the connection it read,
     * the window, the extract's own call tally (requestMetadata.shopifyCalls, or a system's requestMetadata.calls) and
     * the verify pass's lookup outcome for this side. Every key is left out when unknown.
     */
    static Map readStats(String fileSide, Map result, Map context) {
        Map out = [:]
        String configId = normalize(((Map) context?.sideConfigIds)?.get(fileSide))
        if (configId) out.sourceConfigId = configId
        if (result?.readWindowFrom instanceof java.util.Date) out.windowFromDate = new Timestamp(((java.util.Date) result.readWindowFrom).time)
        if (result?.readWindowThru instanceof java.util.Date) out.windowThruDate = new Timestamp(((java.util.Date) result.readWindowThru).time)
        Map metadata = result?.requestMetadata instanceof Map ? (Map) result.requestMetadata : null
        Object calls = metadata?.shopifyCalls ?: metadata?.calls
        if (calls instanceof Map && calls) out.callsJson = truncate(groovy.json.JsonOutput.toJson(calls), REASON_MAX)
        Map reconcile = (Map) context?.reconcileResult
        String label = normalize(reconcile?.get(fileSide == "FILE_1" ? "file1Label" : "file2Label"))
        // R1 (S21a): the verify pass keys each side's lookup outcome by the compare's side (two sides of one system share a
        // label, so one outcome would overwrite the other); a result from before keyed them by label.
        Map sides = reconcile?.verificationSides instanceof Map ? (Map) reconcile.verificationSides : null
        Object lookup = sides ? (sides.get(fileSide) ?: (label ? sides.get(label) : null)) : null
        if (lookup instanceof Map && lookup) out.lookupJson = truncate(groovy.json.JsonOutput.toJson(lookup), REASON_MAX)
        return out
    }

    private static Map readOf(String systemEnumId, Map result, Timestamp readAt, String failureReason) {
        if (result == null) {
            return [systemEnumId: systemEnumId, statusEnumId: "AUDSR_FAILED", recordCount: null, readStamp: null,
                    reason: failureReason ?: "The read did not finish."]
        }
        // DAR-BE-156: a read whose older findings could not be read again by key is partial: the check closes nothing
        // and the board says why, where a log line used to be all there was.
        String recheckFailure = normalize(result.recheckFailure)
        return [systemEnumId: systemEnumId, statusEnumId: recheckFailure ? "AUDSR_PARTIAL" : "AUDSR_COMPLETE",
                recordCount: result.recordCount != null ? (result.recordCount as Long) : null, readStamp: readAt,
                reason: recheckFailure ? truncate(recheckFailure, REASON_MAX) : null]
    }

    /**
     * A result row as the audit reads it: the document on disk names a row diffType / primaryId / message, the compare's
     * in-memory rows type / id / note; either is read. DAR-BE-372: `lookedUp` (what the row's conclusion lookups found)
     * is carried, so recordsOf keeps that record on the finding.
     */
    static Map rowOf(Map raw) {
        return [type: (raw.diffType ?: raw.type) as String, id: (raw.primaryId ?: raw.id) as String,
                note: normalize(raw.message ?: raw.note), data: rowData(raw.data),
                ids: rowData(raw.ids), sideRecords: rowData(raw.sideRecords),
                firstKeyRecords: rowData(raw.firstKeyRecords),
                file1Value: normalize(raw.file1Value), file2Value: normalize(raw.file2Value),
                lookedUp: raw.lookedUp instanceof List ? raw.lookedUp : null,
                systems: raw.conclusion instanceof Map ? ((Map) raw.conclusion).systems : null,
                checks: raw.conclusion instanceof Map ? ((Map) raw.conclusion).checks : null,
                conclusionLabel: raw.conclusion instanceof Map ? normalize(((Map) raw.conclusion).label) : null]
    }

    /**
     * The check's findings in one diff document: a row breaks a statement when its type is in the link's diffTypes
     * (blank: any), the rule that raised it is in the link's ruleIds (blank: any; DAR-BE-095) and its cause is not
     * expected (DAR-BE-196: the run marks conclusion.expected; Unexplained counts unless its run marked it, DAR-BE-198). One finding per statement and
     * record; the row's record lives on the side(s) its type names. `explained` gets recordKey -> {conclusionEnumId,
     * diagnosisText, headline} for each row of a linked diff type whose cause is expected (DAR-BE-118), kept on the closed
     * finding. Whether a link counts a row is countsRow (D11). unjudgedStatementIds gets the statements this run cannot judge
     * (OC33); outsideRules gets {count, rows ≤ OUTSIDE_RULES_ROWS} of the Unexplained rows no link owns (D11), leaving out
     * unjudgedKeys. A result file that cannot be read whole throws IncompleteResultException (OC47).
     */
    static List<Map> findingsFrom(def ec, String tenantId, Map check, File diff, def file1Source, def file2Source,
                                  Map<String, Map> explained, Map<String, String> sideConfigIds = null,
                                  Set<String> unjudgedStatementIds = null, Map outsideRules = null,
                                  Set<String> unjudgedKeys = null) {
        List<Map> links = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId).condition("checkId", check.checkId)
                .orderBy("sequenceNum").list()*.getMap()
        // DAR-UI-063. A statement turned off records no findings, so this run does not judge it (OC33: it closes nothing).
        List statementRows = tenantRows(ec, "darpan.audit.AuditStatement", tenantId)
                .condition("statementId", EntityCondition.IN, links*.statementId).list()
                .findAll { (it.isActive ?: "Y") != "N" }
        unjudgedStatementIds?.addAll(links.findAll { link -> !statementRows.any { it.statementId == link.statementId } }
                .collect { it.statementId as String })
        links = links.findAll { link -> statementRows.any { it.statementId == link.statementId } }
        // DAR-BE-208: a link names its rules by entity id; a row names the rule by its DRL name.
        Map<String, Set<String>> ruleNames = links.any { normalize(it.ruleIds) } ? ruleNamesById(ec, tenantId, check.savedRunId) : [:]
        // OC33: a link whose rules are none of the run's (deleted, or the run is not the tenant's) can never report a row,
        // so its statement is not judged: its open findings must not close. One link per statement and check (the PK).
        List<Map> dangling = normalize(check.savedRunId) ?
                links.findAll { Map link -> AuditAutomationSupport.namesNoRuleOfTheRun(link.ruleIds as String, ruleNames) } : []
        unjudgedStatementIds?.addAll(dangling.collect { it.statementId as String })
        links = (links - dangling).collect { Map link -> link + [ruleIds: AuditAutomationSupport.ruleIdsWithNames(link.ruleIds as String, ruleNames)] }
        Map<String, String> codeById = statementRows.collectEntries { [(it.statementId): it.statementCode] }
        Map<String, Map> byKey = new LinkedHashMap<>()
        // DAR-OPS-029 S11 (H5): two rows for one statement and record (rule rows of one key under a link that takes any rule,
        // duplicates) keep the row that ranks first by its content, not whichever the compare happened to write first.
        Map<String, String> keptRank = [:]
        // D11 (Aditi 2026-11-28, "U1, but show the rest"): the buckets the run's conclusion tree targets, for the Unexplained rows
        // of links that take any type; the Unexplained rows no link owns are the check's outside-rules group.
        Set<String> targeted = normalize(check.savedRunId) ?
                AuditSettingsSupport.targetedBuckets(ec, tenantId, check.savedRunId as String) : ([] as Set<String>)
        Map<String, Map> outside = new LinkedHashMap<>()
        DiffDocumentStreamSupport.DiffRowScan scan = DiffDocumentStreamSupport.forEachRow(diff) { Map raw ->
            // The document on disk names a row diffType / primaryId / message; the compare's in-memory rows say
            // type / id / note. Read either.
            Map row = AuditAutomationSupport.rowOf(raw)
            String rank = AuditAutomationSupport.rowRank(raw)
            String type = row.type as String
            String code = (raw.conclusion instanceof Map ? ((Map) raw.conclusion).code : null) as String
            // DAR-BE-196: Unexplained counts. DAR-BE-198 (b): unless its run marked it expected (unexplainedExpected).
            boolean expected = raw.conclusion instanceof Map && ((Map) raw.conclusion).expected == true &&
                    (code != ConclusionTreeSupport.UNEXPLAINED || ((Map) raw.conclusion).unexplainedExpected == true)
            String ruleId = normalize(raw.ruleId)
            boolean owned = false, unowned = false
            links.each { Map link ->
                // Qualified: from a closure nested in a closure, Groovy resolves a bare static call through the outer
                // closure's metaclass and fails with "argument type mismatch".
                if (expected && row.id && AuditAutomationSupport.matchesDiffType(link.diffTypes as String, type) &&
                        AuditAutomationSupport.keeps(keptRank, "explained|${row.id}".toString(), rank)) {
                    explained.put(row.id as String, [conclusionEnumId: AuditAutomationSupport.conclusionEnumId(ec, code),
                            diagnosisText: row.conclusionLabel,
                            headline: AuditAutomationSupport.headline(check, row, AuditAutomationSupport.recordsOf(check, row, file1Source, file2Source))])
                }
                if (!AuditAutomationSupport.countsRow(link, type, ruleId, expected, code, targeted)) {
                    // Taken by type and rule but not owned: an Unexplained row in a bucket the tree never targets (D11).
                    if (!expected && AuditAutomationSupport.matchesDiffType(link.diffTypes as String, type) &&
                            AuditAutomationSupport.matches(link.ruleIds as String, ruleId)) unowned = true
                    return
                }
                owned = true
                String statementCode = codeById.get(link.statementId as String)
                String recordKey = row.id as String
                if (!statementCode || !recordKey) return
                String findingKey = "${statementCode}|${recordKey}".toString()
                if (!AuditAutomationSupport.keeps(keptRank, findingKey, rank)) return
                List<Map> records = AuditAutomationSupport.recordsOf(check, row, file1Source, file2Source, sideConfigIds)
                byKey.put(findingKey, [
                        statementCode   : statementCode,
                        recordKey       : recordKey,
                        headline        : AuditAutomationSupport.headline(check, row, records),
                        ruleText        : row.note,
                        conclusionEnumId: AuditAutomationSupport.conclusionEnumId(ec, code),
                        records         : records,
                        checks          : AuditAutomationSupport.checksOf(row.checks)] + AuditAutomationSupport.amountsOf(check, row))
            }
            if (unowned && !owned && row.id && !unjudgedKeys?.contains(row.id as String) && AuditAutomationSupport.keeps(keptRank, "outside|${row.id}".toString(), rank)) {
                outside.put(row.id as String, [recordKey: row.id, diffType: type, bucket: RunConclusionSupport.bucketOf([diffType: type], null, null),
                        note: row.note, records: AuditAutomationSupport.recordsOf(check, row, file1Source, file2Source, sideConfigIds)])
            }
        }
        // OC47 (S21a): a file cut off mid-rows, without its differences section, or with a row that does not parse reads as
        // fewer rows; judged as it reads, the missing rows' findings would close. Nothing is judged from it.
        if (!scan.complete) throw new IncompleteResultException(scan.problem())
        if (outsideRules != null) {
            List<String> outsideKeys = outside.keySet().sort()
            outsideRules.put("count", outsideKeys.size())
            outsideRules.put("rows", outsideKeys.take(OUTSIDE_RULES_ROWS).collect { outside.get(it) })
        }
        // In key order, so the ledger meets them (and numbers new findings) the same way whatever order the compare wrote.
        return byKey.keySet().sort().collect { byKey.get(it) } as List<Map>
    }

    /** H5. A diff row's content as one comparable text: type, rule, note, the two values, cause, then its record. */
    static String rowRank(Map raw) {
        Map conclusion = raw.conclusion instanceof Map ? (Map) raw.conclusion : [:]
        return [raw.diffType ?: raw.type, raw.ruleId, raw.message ?: raw.note, raw.file1Value, raw.file2Value,
                conclusion.code, conclusion.label, raw.data].collect { it == null ? "" : it.toString() }.join("\u001F")
    }

    /** H5. True, and remembered, when rank comes before the rank kept for key (or none is kept yet). */
    static boolean keeps(Map<String, String> keptRank, String key, String rank) {
        String kept = keptRank.get(key)
        if (kept != null && kept <= rank) return false
        keptRank.put(key, rank)
        return true
    }

    /**
     * The conclusion's checks as the finding's evidence (AuditFindingCondition): each label, whether it held, and the value
     * it read, so R4 shows the memo's unapplied amount. Bounded: the values are another system's data.
     */
    static List<Map> checksOf(Object checks) {
        return ((checks instanceof List ? checks : []) as List).findAll { it instanceof Map && normalize(((Map) it).label) }
                .take(20).collect { Map c ->
                    [label: truncate(normalize(c.label), 255), passed: c.passed == true, value: truncate(normalize(c.value), 255)]
                }
    }

    /**
     * DAR-BE-095. A money check's mismatch as the finding's evidence: file 1's value expected, file 2's actual, in the
     * check's currency. Nothing for a check that declares no currency, another row type, or a value that is not a number.
     */
    static Map amountsOf(Map check, Map row) {
        String currency = normalize(check.amountCurrencyUomId)
        if (!currency || row.type != "FIELD_MISMATCH") return [:]
        BigDecimal expected = decimalOf(row.file1Value)
        BigDecimal actual = decimalOf(row.file2Value)
        if (expected == null || actual == null) return [:]
        return [expectedAmount: expected, actualAmount: actual, currencyUomId: currency,
                expectedSource: normalize(check.expectedSource), actualSource: normalize(check.actualSource)]
    }

    /** A mismatch value for a headline: money to the cent on a money check ("64.80", not "64.8"), as it came otherwise. */
    static String valueText(Map check, Object value) {
        BigDecimal amount = normalize(check.amountCurrencyUomId) ? decimalOf(value) : null
        return amount != null ? amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : normalize(value)
    }

    /**
     * A plausible amount, or null. The values come from another system's rows, so the text is bounded before it is
     * trusted: "1e999999999" parses, and scaling or printing it would build a billion-digit string.
     */
    static BigDecimal decimalOf(Object value) {
        String text = value?.toString()?.trim()
        if (!text || text.length() > 40) return null
        BigDecimal amount
        try { amount = new BigDecimal(text) } catch (NumberFormatException ignored) { return null }
        if (Math.abs(amount.scale()) > 10 || amount.precision() - amount.scale() > 20) return null
        return amount
    }

    /**
     * DAR-BE-208. A run's rules by entity id, each with the names its rows can carry: the id itself (RuleEngineSupport names a
     * parsed rule by its ruleId) and every `rule "..."` header in its ruleLogic (a raw block keeps its own, e.g.
     * FIELD_COMPARISON_2: FieldComparisonRuleLogicGenerator's name for a rule saved before it had an id, copied as-is by a
     * config transfer). RuleDiffSupport reports kcontext.getRule().getName(), so without this every rule-linked check
     * (Dolls Kill R3/R4, gorjana R4/R5) recorded nothing. Gated on the run (RuleSet) being the tenant's, by explicit tenant as the
     * rest of findingsFrom reads (a play runs with no active tenant); its rules are then read by ruleSetId, since not every
     * Rule row carries its tenant yet (migration RULE_TENANT_STAMPS).
     */
    static Map<String, Set<String>> ruleNamesById(def ec, String tenantId, Object savedRunId) {
        String runId = normalize(savedRunId)
        if (!runId || !tenantId) return [:]
        if (!tenantRows(ec, DarpanEntityConstants.RULE_SET, tenantId).condition("ruleSetId", runId).list()) return [:]
        Map<String, Set<String>> out = [:]
        (TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.Rule",
                "DAR-BE-208 audit hook: a run's rules by ruleSetId, after its RuleSet was read with an explicit companyUserGroupId")
                .condition("ruleSetId", runId).useCache(false)
                .list() ?: []).each { rule ->
            String id = normalize(rule.ruleId)
            if (!id) return
            Set<String> names = new LinkedHashSet<String>([id])
            names.addAll(drlRuleNames(rule.ruleLogic as String))
            out.put(id, names)
        }
        return out
    }

    /** DAR-BE-208. The names of the `rule "..."` headers in a DRL block, unescaped. */
    static List<String> drlRuleNames(String ruleLogic) {
        if (!ruleLogic) return []
        return (ruleLogic =~ /(?m)^\s*rule\s+"((?:[^"\\]|\\.)*)"/).collect { List m ->
            (m[1] as String).replaceAll(/\\(.)/, '$1')
        }.findAll { it } as List<String>
    }

    /**
     * OC33. A link that names rules, none of which is a rule of the run by its id or by a DRL name it carries (namesById:
     * ruleNamesById, empty when the run is not the tenant's or has no rules). Blank names any rule.
     */
    static boolean namesNoRuleOfTheRun(String ruleIds, Map<String, Set<String>> namesById) {
        List<String> ids = (ruleIds ?: "").split(",")*.trim().findAll { it }
        Set<String> known = new HashSet<String>((namesById ?: [:]).keySet())
        (namesById ?: [:]).values().each { known.addAll(it) }
        return ids && !ids.any { known.contains(it) }
    }

    /** DAR-BE-208. A link's ruleIds with each id's DRL names added; blank stays blank (any rule). */
    static String ruleIdsWithNames(String ruleIds, Map<String, Set<String>> namesById) {
        List<String> ids = (ruleIds ?: "").split(",")*.trim().findAll { it }
        if (!ids) return ruleIds
        return ids.collectMany { String id -> (namesById?.get(id) ?: [id]) as List }.unique().join(",")
    }

    /**
     * A row's diff type against a link's diffTypes. A link that names types takes those; one that names none takes every
     * type but a duplicate's paired partner (DUPLICATE_IN_FILE_n_PAIRED, OC14): only a link that names it records it, so a
     * statement that claims "any" never grows findings when the compare starts writing those rows (S12 release gate).
     */
    static boolean matchesDiffType(String allowed, String type) {
        List<String> named = (allowed ?: "").split(",")*.trim().findAll { it }
        return named ? (type != null && named.contains(type)) : !(type?.endsWith("_PAIRED"))
    }

    /**
     * D11. Whether a statement's link counts a diff row as its finding: findingsFrom's one decision (AuditChangeBacktest asks
     * it too). A row whose cause is expected never counts; its type must match the link (matchesDiffType) and its rule the
     * link's rules. An Unexplained row counts on a link that names its type (an explicit claim; Aditi 2026-11-24, Unexplained
     * findings are valid) and, on a link that takes any type, only when the run's conclusion tree targets the row's bucket
     * (targetedBuckets: AuditSettingsSupport.targetedBuckets; empty means no tree, and every row counts as before). A row with
     * a cause, or with no conclusion at all, counts as before.
     */
    static boolean countsRow(Map link, String type, String ruleId, boolean expected, String conclusionCode, Set<String> targetedBuckets) {
        if (expected || !matchesDiffType(link?.diffTypes as String, type) || !matches(link?.ruleIds as String, ruleId)) return false
        if (conclusionCode != ConclusionTreeSupport.UNEXPLAINED || namesTypes(link?.diffTypes as String)) return true
        return treeTargets(targetedBuckets, type)
    }

    /** D11. A link's diffTypes names at least one type (blank: any type). */
    static boolean namesTypes(String diffTypes) {
        return (diffTypes ?: "").split(",")*.trim().any { it }
    }

    /** D11. Whether a tree's targeted buckets reach a row of this type: no tree, a node for any bucket, a single-sided tree (FINDING), or the row's own bucket. */
    static boolean treeTargets(Set<String> targetedBuckets, String type) {
        if (!targetedBuckets) return true
        if (targetedBuckets.contains(AuditSettingsSupport.ANY_BUCKET) || targetedBuckets.contains("FINDING")) return true
        return targetedBuckets.contains(RunConclusionSupport.bucketOf([diffType: type], null, null))
    }

    static boolean matches(String allowed, String value) {
        List<String> list = (allowed ?: "").split(",")*.trim().findAll { it }
        return list.isEmpty() || (value != null && list.contains(value))
    }

    /**
     * DAR-BE-117. The records in OTHER systems a row's record names, under the field names Darpan's sources give them
     * (RuleDiffSupport.RECORD_ID_FIELDS): a NetSuite record's HotWax order (otherrefnum is its name, custbody_hc_order_id
     * its id) and HotWax return, a HotWax record's Shopify and NetSuite orders. `id` is tried in order; `internalId`
     * is kept when it differs. A HotWax return's externalId is its Shopify RETURN, so it names no Shopify order there.
     */
    static final List<Map> RELATED_IDS = [
            [system: "OMS", recordType: "order", id: ["otherRef", "orderName", "orderId"], internalId: "orderId"],
            [system: "OMS", recordType: "return", id: ["returnId"]],
            [system: "SHOPIFY", recordType: "order", id: ["etailOrderId", "shopifyOrderId", "orderExternalId", "externalId"]],
            [system: "NETSUITE", recordType: "salesOrder", id: ["netsuiteOrderId"]],
    ].asImmutable()

    /**
     * The row's record in each system it names. The row carries ONE record, on the side RunConclusionSupport reads it
     * from: there the id is the one a person searches by (its `tranId`, when the record has one and the compare keyed on
     * something else; DAR-BE-095) and internalId is the system's own key (its `internalId`, else the compare key) when it
     * differs. A diff on a matched pair (a rule's FIELD_MISMATCH) carries both records' ids in `ids` instead (DAR-BE-117).
     * A check that names a record type for one side only lists that side unless the row holds the other side's ids.
     * recordStatus is the conclusion step's evidence state for that side:
     * the connector's state field in its labels, so it reads in that system's words. A check with no conclusions
     * has no evidence, and its records no status. After them, the records in other systems those records name
     * (RELATED_IDS), each once.
     */
    static List<Map> recordsOf(Map check, Map row, def file1Source, def file2Source, Map<String, String> sideConfigIds = null) {
        String type = row.type as String
        // A missing row also names the other side's record when the run found one (screened out, or under another key):
        // its ids ride on the row (RunConclusionSupport), e.g. the NetSuite order of a line not billed (R1 F-100781).
        Map rowIds = row.ids instanceof Map ? (Map) row.ids : [:]
        List<String> sides = type == "MISSING_IN_FILE_1" ? ["FILE_2"] + (rowIds.file1 ? ["FILE_1"] : []) :
                type == "MISSING_IN_FILE_2" || type == "FINDING" ? ["FILE_1"] + (rowIds.file2 ? ["FILE_2"] : []) :
                type?.startsWith("DUPLICATE_IN_FILE_1") ? ["FILE_1"] :
                type?.startsWith("DUPLICATE_IN_FILE_2") ? ["FILE_2"] : ["FILE_1", "FILE_2"]
        String dataSide = type == "MISSING_IN_FILE_1" || type?.startsWith("DUPLICATE_IN_FILE_2") ? "FILE_2" : "FILE_1"
        String key = AuditReportAssembler.displayKey(row.id as String)
        boolean typed = normalize(check.file1RecordType) || normalize(check.file2RecordType)
        List<Map> own = []
        List<Map> related = []
        sides.each { String side ->
            def source = side == "FILE_1" ? file1Source : file2Source
            String systemEnumId = normalize(readField(source, "systemEnumId"))
            String recordType = normalize(side == "FILE_1" ? check.file1RecordType : check.file2RecordType)
            Map ids = row.ids instanceof Map ? (Map) ((Map) row.ids).get(side == "FILE_1" ? "file1" : "file2") : null
            Map data = (side == dataSide ? (Map) row.data : null) ?: ids
            // DAR-UI-080: everything the read gave for this record, kept on the finding; named from data, as before.
            Map whole = (side == dataSide ? (Map) row.data : null) ?:
                    (row.sideRecords instanceof Map ? (Map) ((Map) row.sideRecords).get(side == "FILE_1" ? "file1" : "file2") : null) ?: ids
            if (!systemEnumId || (typed && !recordType && !ids)) return
            related.addAll(relatedRecords(data, systemEnumId))
            // DAR-BE-125: a HotWax order joined on its Shopify id is named by its own name and id (RELATED_IDS), not the key.
            Map ownIds = RELATED_IDS.find { it.system == rootSystem(systemEnumId) && it.recordType == recordType }
            String recordId = normalize(data?.get("tranId")) ?: ownIds?.with { relatedValue(data, it.id as List<String>) } ?: key
            // The key stands in for the internal id only on the row's own record: on a pair's other side it is the
            // shared join value (R13's memo id on the replacement order), not that record's key.
            String internalId = normalize(data?.get("internalId")) ?:
                    (ownIds?.internalId ? relatedValue(data, [ownIds.internalId as String]) : null) ?:
                    (recordId != key && (row.data || !ids) ? key : null)
            Map evidence = ((row.systems ?: []) as List).find { it instanceof Map && it.side == side } as Map
            own << [systemEnumId: systemEnumId,
                    // A check that names no record type (an agent's new check) shows the side's key: order_name -> "Order name".
                    recordType  : recordType ?: AuditAutomationSupport.keyRecordType(readField(source, "primaryIdExpression")) ?: "record",
                    recordId    : recordId,
                    internalId  : internalId == recordId ? null : internalId,
                    recordStatus: normalize(evidence?.state),
                    // DAR-BE-284: read through this side's connection (related records below were not read, so none).
                    sourceConfigId: normalize(sideConfigIds?.get(side)),
                    recordData  : whole ?: null]
        }
        Set<String> seen = own.collect { "${rootSystem(it.systemEnumId as String)}|${it.recordType}|${it.recordId}".toString() } as Set
        // DAR-BE-372 rule 2: a record a conclusion lookup read (R13's HotWax order) is kept whole, ahead of the bare id
        // the row names for it, so the finding says that system's status.
        List<Map> lookedUp = lookedUpRecords(row)
        return own + lookedUp.findAll { seen.add("${it.systemEnumId}|${it.recordType}|${it.recordId}".toString()) } +
                related.findAll { seen.add("${it.systemEnumId}|${it.recordType}|${it.recordId}".toString()) } +
                orderLineRecords(row, file1Source, file2Source, sideConfigIds)
    }

    /** DAR-UI-099: the record type of every other line of a finding's order (firstKeyRecords). */
    static final String ORDER_LINE = "orderLine"

    /**
     * DAR-UI-099 (Aditi: "always make sure all lines are present within the order... in the case of store credit, a store
     * credit line item is added"). The other lines of the row's order on each side, as the run read them (kept or set aside
     * by a filter), one record per line key: units of one line count as its quantity. A line with no key of its own (a
     * NetSuite store-credit payment line) is named by its item. The row's own line is not repeated.
     */
    static List<Map> orderLineRecords(Map row, def file1Source, def file2Source, Map<String, String> sideConfigIds = null) {
        Map byFile = row.firstKeyRecords instanceof Map ? (Map) row.firstKeyRecords : null
        if (!byFile) return []
        List<Map> out = []
        [file1: file1Source, file2: file2Source].each { String file, def source ->
            String systemEnumId = normalize(readField(source, "systemEnumId"))
            List entries = byFile.get(file) instanceof List ? (List) byFile.get(file) : []
            if (!systemEnumId || !entries) return
            Map<String, Map> byLine = new LinkedHashMap<>()
            entries.each { Object e ->
                if (!(e instanceof Map)) return
                Map entry = (Map) e
                Map record = entry.record instanceof Map ? (Map) entry.record : [:]
                String key = normalize(entry.key)
                if (key && key == row.id) return
                String id = key ? AuditReportAssembler.displayKey(key) : (normalize(record.itemName) ?: normalize(record.itemType) ?: "line")
                Map line = byLine.get(id)
                if (line != null) { line.recordData.units = ((line.recordData.units ?: 1) as int) + 1; return }
                byLine.put(id, [systemEnumId: systemEnumId, recordType: ORDER_LINE, recordId: id, internalId: null,
                                recordStatus: null, sourceConfigId: normalize(sideConfigIds?.get(file == "file1" ? "FILE_1" : "FILE_2")),
                                recordData: record + [presence: normalize(entry.presence), units: 1]])
            }
            out.addAll(byLine.values())
        }
        return out
    }

    /**
     * The records in other systems one record names (RELATED_IDS); a "NO_..." or "NONE" value is a sentinel for none
     * (NONE is the OMS units' "no NETSUITE_ORDER_ID", also when the read did not ask: never a sales order called NONE).
     */
    static List<Map> relatedRecords(Map data, String systemEnumId) {
        if (!data) return []
        String own = rootSystem(systemEnumId)
        return RELATED_IDS.findAll { it.system != own }.collect { Map spec ->
            String id = relatedValue(data, spec.id as List<String>)
            String internalId = spec.internalId ? relatedValue(data, [spec.internalId as String]) : null
            id ? [systemEnumId: spec.system, recordType: spec.recordType, recordId: id,
                  internalId: internalId == id ? null : internalId, recordStatus: null] : null
        }.findAll()
    }

    /**
     * DAR-BE-372. The records the row's conclusion lookups found (RunConclusionSupport `lookedUp`), each as its system's
     * record named the way RELATED_IDS names it, with everything the lookup kept as its data. One the spec cannot name
     * is left out.
     */
    static List<Map> lookedUpRecords(Map row) {
        return ((row?.lookedUp ?: []) as List).findAll { it instanceof Map && ((Map) it).record instanceof Map }.collect { Map l ->
            String system = rootSystem(l.systemEnumId as String)
            Map record = (Map) l.record
            Map spec = RELATED_IDS.find { it.system == system && relatedValue(record, it.id as List<String>) }
            String id = spec ? relatedValue(record, spec.id as List<String>) : null
            if (!id) return null
            String internalId = spec.internalId ? relatedValue(record, [spec.internalId as String]) : null
            [systemEnumId: system, recordType: spec.recordType, recordId: id, internalId: internalId == id ? null : internalId,
             recordStatus: null, recordData: record]
        }.findAll() as List<Map>
    }

    /** The first of fields with a value; "NO_..."/"NONE" is none, and a return's externalId is its Shopify RETURN. */
    static String relatedValue(Map data, List<String> fields) {
        if (!data) return null
        return fields.findResult { String field ->
            String v = normalize(data.get(field))
            v && !v.startsWith("NO_") && v != "NONE" && !(field == "externalId" && normalize(data.get("returnId"))) ? v : null
        }
    }

    /** NETSUITE_SUITEQL -> NETSUITE, OMS_RETURNS_GQL -> OMS: an endpoint's system is the code before its first "_". */
    static String rootSystem(String systemEnumId) {
        return systemEnumId?.split("_")?.first()
    }

    /** A row's `data` is the record as a JSON string on disk, a Map in memory; anything else reads as no record. */
    static Map rowData(Object data) {
        if (data instanceof Map) return (Map) data
        if (data == null) return null
        try {
            Object parsed = new JsonSlurper().parseText(data.toString())
            return parsed instanceof Map ? (Map) parsed : null
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * The check's headline template filled from the row ({recordKey}, {note}, {type}, {conclusion}: the row's
     * conclusion label, and {file1Value} / {file2Value}: a mismatch's two values) and its records ({<recordType>}: that record's id, as fix texts take them), else the row's own
     * note; one line, at most 120 characters.
     */
    static String headline(Map check, Map row, List<Map> records = []) {
        Map<String, String> values = [:]
        (records ?: []).each { Map r -> values.putIfAbsent(r.recordType as String, r.recordId as String) }
        values.putAll([recordKey: AuditReportAssembler.displayKey(row.id as String), note: normalize(row.note), type: row.type as String,
                       conclusion: normalize(row.conclusionLabel), file1Value: AuditAutomationSupport.valueText(check, row.file1Value),
                       file2Value: AuditAutomationSupport.valueText(check, row.file2Value)])
        String filled = AuditReportAssembler.fillTemplate(check.headlineTemplate as String, values)
        String text = filled ?: AuditAutomationSupport.mismatchHeadline(values, records) ?:
                "${AuditReportAssembler.displayKey(row.id as String)}: ${normalize(row.note) ?: row.type}".toString()
        text = text.replaceAll(/\s+/, " ").trim().replaceAll(/\.+$/, "")
        return text.length() > 120 ? text.take(119).trim() + "…" : text
    }

    /** A side's key field as a record type (order_name -> orderName, which the page shows as "Order name"); null for a
     * path or a composite key, so those keep "record". */
    static String keyRecordType(Object primaryIdExpression) {
        String key = normalize(primaryIdExpression?.toString())
        if (!key || !(key ==~ /[A-Za-z][A-Za-z0-9_]*/)) return null
        List<String> parts = key.split("_").findAll { it }
        return parts.first() + parts.drop(1).collect { it.capitalize() }.join("")
    }

    /** The page's name for a system (HotWax for the OMS), from a record's system or endpoint id (OMS_GQL is HotWax). */
    static final Map<String, String> SYSTEM_NAMES = [OMS: "HotWax", NETSUITE: "NetSuite", SHOPIFY: "Shopify"].asImmutable()

    /**
     * A mismatch's default headline when the check has no template: each system with its own value, in the run's side
     * order ("ACME-1120: Billed in HotWax, Pending Billing in NetSuite"), instead of the engine's "Field comparison failed:
     * billing_status = billing_status". Null unless the row is a mismatch with both values and a record from each side.
     */
    static String mismatchHeadline(Map<String, String> values, List<Map> records) {
        if (values.type != "FIELD_MISMATCH" || !values.file1Value || !values.file2Value) return null
        List<String> systems = (records ?: []).findAll { it.systemEnumId }.collect { Map r ->
            String root = rootSystem(r.systemEnumId as String); SYSTEM_NAMES[root] ?: root
        }.unique()
        if (systems.size() < 2) return null
        return "${values.recordKey}: ${values.file1Value} in ${systems[0]}, ${values.file2Value} in ${systems[1]}".toString()
    }

    static String conclusionEnumId(def ec, String code) {
        if (!code) return null
        def byId = ec.entity.find("moqui.basic.Enumeration").condition("enumId", code).useCache(true).one()
        if (byId != null) return byId.enumId
        return ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "DarpanConclusion")
                .condition("enumCode", code).useCache(true).list().find()?.enumId
    }

    private static List<String> readKeys(String location) {
        if (!location) return []
        File f = new File(location)
        return f.isFile() ? f.readLines("UTF-8")*.trim().findAll { it } : []
    }

    private static ZoneId zoneOf(def automation) {
        String zone = normalize(readField(automation, "windowTimeZone"))
        try { return ZoneId.of(zone ?: "UTC") } catch (Exception ignored) { return ZoneId.of("UTC") }
    }

    private static def tenantRows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }
}
