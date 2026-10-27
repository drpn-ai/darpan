package darpan.audit

import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.DiffDocumentStreamSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.conclusion.ConclusionTreeSupport
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
 * statement-to-check link's diffTypes and conclusionCodes (configuration, not code). Examined records are the keys
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

    /** The audit checks this automation runs; empty when it feeds no audit. */
    static List<Map> checksFor(def ec, def automation) {
        String automationId = normalize(readField(automation, "automationId"))
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        if (!automationId || !tenantId) return []
        return tenantRows(ec, "darpan.audit.AuditCheck", tenantId).condition("automationId", automationId).list()*.getMap()
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
            return pickedWindows(ec, audit, zone, reportDay, toLocalDate(explicit.auditWindowFromDate), toLocalDate(explicit.auditWindowThruDate), checkIds)
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
            windows << ([sequenceNum         : windows.size() + 1,
                         windowStartDate     : start,
                         windowEndDate       : end,
                         childWindowStartDate: start,
                         childWindowEndDate  : end,
                         auditId             : audit.auditId,
                         auditPeriod         : run.period,
                         auditReportDate     : Date.valueOf(reportDay),
                         auditRecentReadStartDate: readStart(ec, audit, checkIds, run.period as String, start, end, zone)] + verify as Map<String, Object>)
        }
        return windows
    }

    /**
     * DAR-UI-063. The days a person picked to play, first to last inclusive, in the automation's zone: one window, dated as
     * picked and reported on the run's own morning. The days must lie in that morning's period; otherwise the run is
     * refused before anything is read, because the ledger would refuse each check's write and the run would record nothing.
     */
    static List<Map<String, Object>> pickedWindows(def ec, Map audit, ZoneId zone, LocalDate reportDay, LocalDate from, LocalDate thru,
                                                   List<String> checkIds = null) {
        if (from > thru) throw new IllegalArgumentException("The days run backwards: ${from} to ${thru}.")
        if (thru > reportDay) throw new IllegalArgumentException("${thru} has not ended yet; pick days up to ${reportDay}.")
        String periodCode = AuditReportAssembler.enumCodes(ec)[audit.periodEnumId as String]
        Map w = AuditReportAssembler.auditRun(ec, audit, reportDay)
        if (from < (w.from as LocalDate)) {
            throw new IllegalStateException("${AuditReportAssembler.periodOf(from, periodCode)} has ended; pick days in ${w.period}.")
        }
        Timestamp end = Timestamp.from(thru.plusDays(1).atStartOfDay(zone).toInstant())
        Timestamp goLive = goLive(ec, audit.companyUserGroupId as String)
        Timestamp start = fromGoLive(Timestamp.from(from.atStartOfDay(zone).toInstant()), end, goLive)
        if (start == null) {
            throw new IllegalStateException("Nothing before go-live (${goLiveText(goLive, zone)}) is audited; pick days from ${goLiveDay(goLive, zone)}.")
        }
        return [[sequenceNum         : 1,
                 windowStartDate     : start,
                 windowEndDate       : end,
                 childWindowStartDate: start,
                 childWindowEndDate  : end,
                 auditId             : audit.auditId,
                 auditPeriod         : w.period,
                 auditReportDate     : Date.valueOf(reportDay),
                 auditWindowFromDate : Date.valueOf(from),
                 auditWindowThruDate : Date.valueOf(thru),
                 auditRecentReadStartDate: readStart(ec, audit, checkIds, w.period as String, start, end, zone)] as Map<String, Object>]
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
     * After one window: health, findings, closings for every check the automation runs. A side with no result was
     * not read: the side that threw failed, a side never reached failed for that reason. Reads that completed but
     * whose compare failed make the check notRun, so nothing closes.
     */
    static void recordWindow(def ec, def automation, List<Map> checks, Map<String, Object> window,
                             def file1Source, def file2Source, Map file1Result, Map file2Result,
                             Map reconcileResult, Throwable failure, String automationExecutionId = null) {
        if (!checks || !window?.auditPeriod) return
        List<String> priorErrors = ec.message.hasError() ? new ArrayList<String>(ec.message.errors) : []
        ec.message.clearErrors()
        try {
            String tenantId = normalize(readField(automation, "companyUserGroupId"))
            Timestamp now = new Timestamp(System.currentTimeMillis())
            List<Map> reads = sourceReads(file1Source, file2Source, file1Result, file2Result, failure, now)
            String notRunReason = failure != null && file1Result != null && file2Result != null ?
                    truncate("The compare failed: ${sanitizeErrorMessage(failure)}".toString(), REASON_MAX) : null
            File diff = reconcileResult && failure == null ? AutomationExecutionSupport.resolveDiffFile(ec, reconcileResult) : null
            // DAR-BE-142: a Set, deduped once. List.unique() compares every key with every key kept so far: gorjana's
            // Shopify vs HotWax window (189k keys, two checks) held a worker thread for minutes after the run succeeded.
            Set<String> examined = new LinkedHashSet<String>(readKeys(window.auditFile1KeysOut as String))
            examined.addAll(readKeys(window.auditFile2KeysOut as String))
            checks.each { Map check ->
                Set<String> unjudged = unjudgedKeys(ec, automation, check, window, diff, file1Source, file2Source)
                List<String> judged = unjudged ? examined.findAll { !unjudged.contains(it) } as List<String> : new ArrayList<String>(examined)
                recordCheck(ec, tenantId, check, window, reads, notRunReason, diff, file1Source, file2Source,
                        judged, now, unjudged, automationExecutionId)
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
        boolean oms1 = AuditRecentReadSupport.readsRecentOnly(normalize(readField(file1Source, "systemEnumId")))
        boolean oms2 = AuditRecentReadSupport.readsRecentOnly(normalize(readField(file2Source, "systemEnumId")))
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
                                    Set<String> unjudged, String automationExecutionId) {
        Map base = [companyUserGroupId: tenantId, checkId: check.checkId, reportDate: window.auditReportDate,
                    period: window.auditPeriod]
        // DAR-UI-063. Played days keep their own dates; the morning run's are the period to date (the ledger's default).
        Map dates = window.auditWindowFromDate ? [windowFromDate: window.auditWindowFromDate, windowThruDate: window.auditWindowThruDate] : [:]
        // DAR-BE-123: a verify writes no check run: it moves neither the check's health nor its incremental read. As
        // record#CheckRun would, a read not complete makes it partial, which closes nothing (DAR-BE-124).
        Map run = !window.auditVerifyKeys ? call(ec, "${LEDGER}.record#CheckRun", base + dates + [runStamp: now, sourceReads: reads,
                notRunReason: notRunReason, automationExecutionId: automationExecutionId]) : notRunReason || !reads ? null :
                [checkStatusEnumId: reads.every { it.statusEnumId == "AUDSR_COMPLETE" } ? "AUDCS_RAN" : "AUDCS_PARTIAL"]
        if (run == null || run.checkStatusEnumId == "AUDCS_NOT_RUN") return
        Map<String, Map> explained = [:]
        List<Map> findings = diff != null ? findingsFrom(ec, tenantId, check, diff, file1Source, file2Source, explained)
                .findAll { !unjudged.contains(it.recordKey as String) } : []
        if (call(ec, "${LEDGER}.record#CheckFindings", base + [checkStamp: now, findings: findings]) == null) return
        // DAR-BE-118: closes what THIS run examined and did not report, so a later run the same morning closes too.
        call(ec, "${LEDGER}.close#ResolvedFindings", base + [checkStamp: now, checkStatusEnumId: run.checkStatusEnumId,
                                                            examinedRecordKeys: examined,
                                                            reportedRecordKeys: findings*.recordKey.unique(),
                                                            explanations: explained])
    }

    /** One ledger call; on an error, log it and answer null so the next step for this check is skipped. */
    private static Map call(def ec, String serviceName, Map params) {
        Map out = AutomationExecutionSupport.dispatchInternalService(ec, serviceName, params)
        if (ec.message.hasError()) {
            logger.error("Audit ledger ${serviceName} for check ${params.checkId} (${params.period}) refused: ${sanitizeText(ec.message.errorsString)}")
            ec.message.clearErrors()
            return null
        }
        return out ?: [:]
    }

    /** One read per side, as record#CheckRun takes them. */
    static List<Map> sourceReads(def file1Source, def file2Source, Map file1Result, Map file2Result, Throwable failure,
                                 Timestamp now) {
        String why = failure ? truncate(sanitizeErrorMessage(failure), REASON_MAX) : null
        String system1 = normalize(readField(file1Source, "systemEnumId"))
        String system2 = normalize(readField(file2Source, "systemEnumId"))
        List<Map> reads = []
        reads << readOf(system1, file1Result, now, why)
        reads << readOf(system2, file2Result, now,
                file1Result == null && failure ? "Not read: the ${system1} read failed first.".toString() : why)
        return reads.findAll { it.systemEnumId }
    }

    private static Map readOf(String systemEnumId, Map result, Timestamp now, String failureReason) {
        if (result == null) {
            return [systemEnumId: systemEnumId, statusEnumId: "AUDSR_FAILED", recordCount: null, readStamp: null,
                    reason: failureReason ?: "The read did not finish."]
        }
        return [systemEnumId: systemEnumId, statusEnumId: "AUDSR_COMPLETE",
                recordCount: result.recordCount != null ? (result.recordCount as Long) : null, readStamp: now, reason: null]
    }

    /**
     * The check's findings in one diff document: a row breaks a statement when its type is in the link's diffTypes
     * (blank: any), its conclusion code is in the link's conclusionCodes (blank: any) and the rule that raised it is in
     * the link's ruleIds (blank: any; DAR-BE-095). One finding per statement and record; the row's record lives on the
     * side(s) its type names. `explained` gets recordKey -> {conclusionEnumId, diagnosisText, headline} for each row of a
     * linked diff type that reached a conclusion the statement does not count (DAR-BE-118), kept on the closed finding.
     */
    static List<Map> findingsFrom(def ec, String tenantId, Map check, File diff, def file1Source, def file2Source,
                                  Map<String, Map> explained) {
        List<Map> links = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId).condition("checkId", check.checkId)
                .orderBy("sequenceNum").list()*.getMap()
        // DAR-UI-063. A statement turned off records no findings.
        List statementRows = tenantRows(ec, "darpan.audit.AuditStatement", tenantId)
                .condition("statementId", EntityCondition.IN, links*.statementId).list()
                .findAll { (it.isActive ?: "Y") != "N" }
        links = links.findAll { link -> statementRows.any { it.statementId == link.statementId } }
        Map<String, String> codeById = statementRows.collectEntries { [(it.statementId): it.statementCode] }
        Map<String, Map> byKey = new LinkedHashMap<>()
        DiffDocumentStreamSupport.forEachRow(diff) { Map raw ->
            // The document on disk names a row diffType / primaryId / message; the compare's in-memory rows say
            // type / id / note. Read either.
            Map row = [type: (raw.diffType ?: raw.type) as String, id: (raw.primaryId ?: raw.id) as String,
                       note: normalize(raw.message ?: raw.note), data: AuditAutomationSupport.rowData(raw.data),
                       ids: AuditAutomationSupport.rowData(raw.ids), sideRecords: AuditAutomationSupport.rowData(raw.sideRecords),
                       file1Value: normalize(raw.file1Value), file2Value: normalize(raw.file2Value),
                       systems: raw.conclusion instanceof Map ? ((Map) raw.conclusion).systems : null,
                       checks: raw.conclusion instanceof Map ? ((Map) raw.conclusion).checks : null,
                       conclusionLabel: raw.conclusion instanceof Map ? normalize(((Map) raw.conclusion).label) : null]
            String type = row.type as String
            String code = (raw.conclusion instanceof Map ? ((Map) raw.conclusion).code : null) as String
            String ruleId = normalize(raw.ruleId)
            links.each { Map link ->
                // Qualified: from a closure nested in a closure, Groovy resolves a bare static call through the outer
                // closure's metaclass and fails with "argument type mismatch".
                if (code && code != ConclusionTreeSupport.UNEXPLAINED && row.id &&
                        AuditAutomationSupport.matches(link.diffTypes as String, type) &&
                        !AuditAutomationSupport.matches(link.conclusionCodes as String, code)) {
                    explained.putIfAbsent(row.id as String, [conclusionEnumId: AuditAutomationSupport.conclusionEnumId(ec, code),
                            diagnosisText: row.conclusionLabel,
                            headline: AuditAutomationSupport.headline(check, row, AuditAutomationSupport.recordsOf(check, row, file1Source, file2Source))])
                }
                if (!AuditAutomationSupport.matches(link.diffTypes as String, type) ||
                        !AuditAutomationSupport.matches(link.conclusionCodes as String, code) ||
                        !AuditAutomationSupport.matches(link.ruleIds as String, ruleId)) return
                String statementCode = codeById[link.statementId as String]
                String recordKey = row.id as String
                if (!statementCode || !recordKey) return
                List<Map> records = AuditAutomationSupport.recordsOf(check, row, file1Source, file2Source)
                byKey.putIfAbsent("${statementCode}|${recordKey}".toString(), [
                        statementCode   : statementCode,
                        recordKey       : recordKey,
                        headline        : AuditAutomationSupport.headline(check, row, records),
                        ruleText        : row.note,
                        conclusionEnumId: AuditAutomationSupport.conclusionEnumId(ec, code),
                        records         : records,
                        checks          : AuditAutomationSupport.checksOf(row.checks)] + AuditAutomationSupport.amountsOf(check, row))
            }
        }
        return byKey.values() as List<Map>
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
    static List<Map> recordsOf(Map check, Map row, def file1Source, def file2Source) {
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
                    recordType  : recordType ?: "record",
                    recordId    : recordId,
                    internalId  : internalId == recordId ? null : internalId,
                    recordStatus: normalize(evidence?.state),
                    recordData  : whole ?: null]
        }
        Set<String> seen = own.collect { "${rootSystem(it.systemEnumId as String)}|${it.recordType}|${it.recordId}".toString() } as Set
        return own + related.findAll { seen.add("${it.systemEnumId}|${it.recordType}|${it.recordId}".toString()) }
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
        String text = filled ?: "${AuditReportAssembler.displayKey(row.id as String)}: ${normalize(row.note) ?: row.type}".toString()
        text = text.replaceAll(/\s+/, " ").trim().replaceAll(/\.+$/, "")
        return text.length() > 120 ? text.take(119).trim() + "…" : text
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
