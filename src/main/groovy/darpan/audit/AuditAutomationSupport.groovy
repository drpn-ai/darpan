package darpan.audit

import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.DiffDocumentStreamSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
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
 * Windows: one per run the audit owes that morning (audit.AuditReportServices.get#AuditWindow): the current period to
 * date, plus each ended period a user extended. "On the 14th it reconciles 1-14."
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
     * run's last day). Each window carries its audit period and report day for the ledger.
     */
    static List<Map<String, Object>> resolveWindows(def ec, def automation, Map audit, Timestamp scheduledFireTime) {
        ZoneId zone = zoneOf(automation)
        LocalDate reportDay = scheduledFireTime.toInstant().atZone(zone).toLocalDate()
        Map out = AutomationExecutionSupport.dispatchInternalService(ec, "audit.AuditReportServices.get#AuditWindow",
                [companyUserGroupId: audit.companyUserGroupId, auditId: audit.auditId, reportDate: Date.valueOf(reportDay)])
        if (ec.message.hasError()) {
            String errors = ec.message.errorsString
            ec.message.clearErrors()
            throw new IllegalStateException("Audit ${audit.auditCode}: ${sanitizeText(errors)}")
        }
        return ((out.runs ?: []) as List<Map>).withIndex().collect { Map run, int index ->
            Timestamp start = Timestamp.from((run.windowFromDate as Date).toLocalDate().atStartOfDay(zone).toInstant())
            Timestamp end = Timestamp.from((run.windowThruDate as Date).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant())
            [sequenceNum         : index + 1,
             windowStartDate     : start,
             windowEndDate       : end,
             childWindowStartDate: start,
             childWindowEndDate  : end,
             auditId             : audit.auditId,
             auditPeriod         : run.period,
             auditReportDate     : Date.valueOf(reportDay)] as Map<String, Object>
        }
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
                             Map reconcileResult, Throwable failure) {
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
            List<String> examined = readKeys(window.auditFile1KeysOut as String) + readKeys(window.auditFile2KeysOut as String)
            checks.each { Map check ->
                recordCheck(ec, tenantId, check, window, reads, notRunReason, diff, file1Source, file2Source, examined.unique(), now)
            }
        } catch (Exception e) {
            logger.error("Audit ledger write failed for automation ${readField(automation, 'automationId')} window ${window.auditPeriod}: ${sanitizeErrorDetail(e)}")
        } finally {
            ec.message.clearErrors()
            priorErrors.each { ec.message.addError(it) }
        }
    }

    private static void recordCheck(def ec, String tenantId, Map check, Map window, List<Map> reads, String notRunReason,
                                    File diff, def file1Source, def file2Source, List<String> examined, Timestamp now) {
        Map base = [companyUserGroupId: tenantId, checkId: check.checkId, reportDate: window.auditReportDate,
                    period: window.auditPeriod]
        Map run = call(ec, "${LEDGER}.record#CheckRun", base + [runStamp: now, sourceReads: reads, notRunReason: notRunReason])
        if (run == null || run.checkStatusEnumId == "AUDCS_NOT_RUN") return
        List<Map> findings = diff != null ? findingsFrom(ec, tenantId, check, diff, file1Source, file2Source) : []
        if (call(ec, "${LEDGER}.record#CheckFindings", base + [checkStamp: now, findings: findings]) == null) return
        call(ec, "${LEDGER}.close#ResolvedFindings", base + [checkStamp: now, checkStatusEnumId: run.checkStatusEnumId,
                                                            examinedRecordKeys: examined])
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
        // Forward-compatible: a connector that reports a partial read (DAR-BE-092 follow-up) says so here.
        boolean partial = result.partial == true
        return [systemEnumId: systemEnumId, statusEnumId: partial ? "AUDSR_PARTIAL" : "AUDSR_COMPLETE",
                recordCount: result.recordCount != null ? (result.recordCount as Long) : null, readStamp: now,
                reason: partial ? (truncate(sanitizeText(normalize(result.partialReason)), REASON_MAX) ?: "The connector reported a partial read.") : null]
    }

    /**
     * The check's findings in one diff document: a row breaks a statement when its type is in the link's diffTypes
     * (blank: any) and its conclusion code is in the link's conclusionCodes (blank: any). One finding per statement and
     * record; the row's record lives on the side(s) its type names.
     */
    static List<Map> findingsFrom(def ec, String tenantId, Map check, File diff, def file1Source, def file2Source) {
        List<Map> links = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId).condition("checkId", check.checkId)
                .orderBy("sequenceNum").list()*.getMap()
        Map<String, String> codeById = tenantRows(ec, "darpan.audit.AuditStatement", tenantId)
                .condition("statementId", EntityCondition.IN, links*.statementId).list()
                .collectEntries { [(it.statementId): it.statementCode] }
        Map<String, Map> byKey = new LinkedHashMap<>()
        DiffDocumentStreamSupport.forEachRow(diff) { Map raw ->
            // The document on disk names a row diffType / primaryId / message; the compare's in-memory rows say
            // type / id / note. Read either.
            Map row = [type: (raw.diffType ?: raw.type) as String, id: (raw.primaryId ?: raw.id) as String,
                       note: normalize(raw.message ?: raw.note), data: AuditAutomationSupport.rowData(raw.data),
                       systems: raw.conclusion instanceof Map ? ((Map) raw.conclusion).systems : null,
                       conclusionLabel: raw.conclusion instanceof Map ? normalize(((Map) raw.conclusion).label) : null]
            String type = row.type as String
            String code = (raw.conclusion instanceof Map ? ((Map) raw.conclusion).code : null) as String
            links.each { Map link ->
                // Qualified: from a closure nested in a closure, Groovy resolves a bare static call through the outer
                // closure's metaclass and fails with "argument type mismatch".
                if (!AuditAutomationSupport.matches(link.diffTypes as String, type) ||
                        !AuditAutomationSupport.matches(link.conclusionCodes as String, code)) return
                String statementCode = codeById[link.statementId as String]
                String recordKey = row.id as String
                if (!statementCode || !recordKey) return
                byKey.putIfAbsent("${statementCode}|${recordKey}".toString(), [
                        statementCode   : statementCode,
                        recordKey       : recordKey,
                        headline        : AuditAutomationSupport.headline(check, row),
                        ruleText        : row.note,
                        conclusionEnumId: AuditAutomationSupport.conclusionEnumId(ec, code),
                        records         : AuditAutomationSupport.recordsOf(check, row, file1Source, file2Source)])
            }
        }
        return byKey.values() as List<Map>
    }

    static boolean matches(String allowed, String value) {
        List<String> list = (allowed ?: "").split(",")*.trim().findAll { it }
        return list.isEmpty() || (value != null && list.contains(value))
    }

    /**
     * The row's record in each system it names. internalId is the system's own key from the record the row carries
     * (its `internalId` field, when it differs from the id a person searches by); the row carries ONE record, on the
     * side RunConclusionSupport reads it from. recordStatus is the conclusion step's evidence state for that side:
     * the connector's state field in its labels, so it reads in that system's words. A check with no conclusions
     * has no evidence, and its records no status.
     */
    static List<Map> recordsOf(Map check, Map row, def file1Source, def file2Source) {
        String type = row.type as String
        List<String> sides = type == "MISSING_IN_FILE_1" ? ["FILE_2"] :
                type == "MISSING_IN_FILE_2" || type == "FINDING" ? ["FILE_1"] :
                type?.startsWith("DUPLICATE_IN_FILE_1") ? ["FILE_1"] :
                type?.startsWith("DUPLICATE_IN_FILE_2") ? ["FILE_2"] : ["FILE_1", "FILE_2"]
        String dataSide = type == "MISSING_IN_FILE_1" || type?.startsWith("DUPLICATE_IN_FILE_2") ? "FILE_2" : "FILE_1"
        String recordId = AuditReportAssembler.displayKey(row.id as String)
        return sides.collect { String side ->
            def source = side == "FILE_1" ? file1Source : file2Source
            String internalId = side == dataSide ? normalize(((Map) row.data)?.get("internalId")) : null
            Map evidence = ((row.systems ?: []) as List).find { it instanceof Map && it.side == side } as Map
            [systemEnumId: normalize(readField(source, "systemEnumId")),
             recordType  : normalize(side == "FILE_1" ? check.file1RecordType : check.file2RecordType) ?: "record",
             recordId    : recordId,
             internalId  : internalId == recordId ? null : internalId,
             recordStatus: normalize(evidence?.state)]
        }.findAll { it.systemEnumId }
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
     * The check's headline template filled from the row ({recordKey}, {note}, {type}, and {conclusion}: the row's
     * conclusion label), else the row's own note; one line, at most 120 characters.
     */
    static String headline(Map check, Map row) {
        String filled = AuditReportAssembler.fillTemplate(check.headlineTemplate as String,
                [recordKey: AuditReportAssembler.displayKey(row.id as String), note: normalize(row.note), type: row.type as String,
                 conclusion: normalize(row.conclusionLabel)])
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
