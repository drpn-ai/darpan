package darpan.audit

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-102. The audit run's scheduled chat digest: the morning's report JSON (darpan.audit.report/v1) in a few
 * lines, sent to a tenant chat space. The text reads the report only — no field of its own — so the digest, the app
 * and GraphQL never disagree. Sending runs on the job thread with no user: every read carries the digest's tenant.
 */
class AuditDigestSupport {
    private static final Logger logger = LoggerFactory.getLogger(AuditDigestSupport.class)
    private static final String WHY = "audit digest job on a scheduled thread: explicit companyUserGroupId condition applied"
    static final String DAILY = "AUDDK_DAILY"
    static final String WEEKLY = "AUDDK_WEEKLY"
    /** Under Google Chat's 4,096-character text limit, with room for the closing lines. */
    static final int MESSAGE_LIMIT = 3600
    static final int OLDEST_SHOWN = 5
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /**
     * The digest text. weekAgo is the report of seven mornings before, for a weekly summary; null for a daily one.
     * fullList lists every open and pending finding the report carries, as far as one message holds.
     */
    static String render(Map report, String kindEnumId, boolean fullList, Map weekAgo, String reportUrl) {
        Map head = (report?.report ?: [:]) as Map
        Map health = (report?.health ?: [:]) as Map
        Map summary = (report?.summary ?: [:]) as Map
        boolean weekly = kindEnumId == WEEKLY
        List<String> lines = []

        String day = dayLabel(head.date as String)
        lines.add([head.audit instanceof Map ? (head.audit as Map).name : null, head.period,
                   weekly ? "week to ${day}" : day].findAll { it }.join(" · "))
        // The report says why a morning is not complete (a check that did not run, a partial read); the digest
        // repeats it, so a quiet count is never mistaken for a clean one.
        if (health.complete != true && health.summary) lines.add(health.summary as String)
        lines.add("")

        if (weekly) {
            Map before = (weekAgo?.summary ?: [:]) as Map
            lines.add("Open ${count(summary.open)} (${count(before.open)} a week ago) · " +
                    "Pending ${count(summary.pending)} (${count(before.pending)} a week ago)")
        } else {
            lines.add("Open ${count(summary.open)} · Pending ${count(summary.pending)} · " +
                    "New today ${count(summary.newToday)} · Fixed today ${count(summary.fixedToday)}")
        }
        ((report?.categories ?: [:]) as Map).each { String id, Object raw ->
            Map category = raw as Map
            Map counts = (category.counts ?: [:]) as Map
            List<String> parts = []
            if (count(counts.open)) parts.add("open ${count(counts.open)}")
            if (count(counts.pending)) parts.add("pending ${count(counts.pending)}")
            if (parts) lines.add("${category.label ?: id}: ${parts.join(', ')}")
        }

        List<Map> open = findings(report, "open")
        List<Map> pending = fullList ? findings(report, "pending") : []
        if (!open && !pending) {
            lines.add("")
            lines.add(count(summary.pending) ? "Nothing open." : "Nothing open or pending.")
        }
        List<String> tail = reportUrl ? ["", "Report: ${reportUrl}".toString()] : []
        int omitted = fullList ? omittedCount(report) : 0
        List<List> groups = fullList ?
                [["Open", open], ["Pending", pending]] :
                [["Oldest open", open.take(OLDEST_SHOWN)]]
        int left = fullList ? open.size() + pending.size() + omitted : 0
        boolean full = false
        for (List group : groups) {
            List<Map> rows = group[1] as List<Map>
            if (!rows || full) continue
            String title = fullList ? "${group[0]} (${rows.size()})" : group[0]
            if (length(lines + ["", title] + tail) > MESSAGE_LIMIT) { full = true; continue }
            lines.add("")
            lines.add(title as String)
            for (Map finding : rows) {
                String line = findingLine(finding)
                // Leave room for the "more" line, so the cut is always said.
                if (length(lines + [line, "… 9999 more in the report"] + tail) > MESSAGE_LIMIT) { full = true; break }
                lines.add(line)
                left--
            }
        }
        if (fullList && left > 0) lines.add("… ${left} more in the report".toString())
        lines.addAll(tail)
        return lines.join("\n")
    }

    /** "F-100000 · R2 · 6 days · Invoice INV8412671 has no Etail id". */
    static String findingLine(Map finding) {
        int age = count(finding.ageDays)
        return [finding.id, finding.statement, age == 1 ? "1 day" : "${age} days", finding.headline]
                .findAll { it != null && it != "" }.join(" · ")
    }

    /** Findings in one state across every statement, oldest first; each carries its statement id. */
    static List<Map> findings(Map report, String state) {
        List<Map> found = []
        ((report?.categories ?: [:]) as Map).values().each { Object category ->
            (((category as Map).statements ?: [:]) as Map).values().each { Object raw ->
                Map statement = raw as Map
                ((statement.findings ?: []) as List).each { Object finding ->
                    Map row = finding as Map
                    if (row.state == state) found.add(row + [statement: statement.id])
                }
            }
        }
        return found.sort { a, b -> count(b.ageDays) <=> count(a.ageDays) ?: (a.id as String) <=> (b.id as String) }
    }

    /** Findings a statement holds beyond the 50 the report lists. */
    static int omittedCount(Map report) {
        int omitted = 0
        ((report?.categories ?: [:]) as Map).values().each { Object category ->
            (((category as Map).statements ?: [:]) as Map).values().each { Object statement ->
                omitted += count((statement as Map).findingsOmitted)
            }
        }
        return omitted
    }

    /**
     * Sends every active digest that is due at `now`: past its send time on the tenant's day, on its weekday when
     * weekly, and not sent yet that day. Each is claimed before it is sent, so two job threads send it once.
     * Returns what happened to each, for the job's result and the tests.
     */
    static List<Map> sendDue(def ec, Instant now) {
        List<Map> outcomes = []
        // isActive <> 'N' alone would drop the rows that leave it blank: SQL never matches a null against a value.
        def active = ec.entity.conditionFactory.makeCondition([
                ec.entity.conditionFactory.makeCondition("isActive", EntityCondition.EQUALS, null),
                ec.entity.conditionFactory.makeCondition("isActive", EntityCondition.NOT_EQUAL, "N")], EntityCondition.OR)
        List rows = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.audit.AuditDigest",
                "digest scan across tenants: each row's own tenant pins every read and write after it")
                .condition(active).orderBy("auditDigestId").useCache(false).list()
        for (def row : rows) {
            Map digest = row.getMap()
            try {
                outcomes.add([auditDigestId: digest.auditDigestId] + sendIfDue(ec, digest, now))
            } catch (Throwable t) {
                logger.warn("Audit digest {} for tenant {} failed: {}", digest.auditDigestId, digest.companyUserGroupId, t.message)
                outcomes.add([auditDigestId: digest.auditDigestId, sent: false, reason: "FAILED"])
            }
        }
        return outcomes
    }

    private static Map sendIfDue(def ec, Map digest, Instant now) {
        String tenantId = normalize(digest.companyUserGroupId)
        ZonedDateTime local = now.atZone(tenantZone(ec, tenantId))
        LocalDate today = local.toLocalDate()
        LocalTime sendTime
        try { sendTime = LocalTime.parse(normalize(digest.sendTime) ?: "") }
        catch (DateTimeParseException ignored) {
            logger.warn("Audit digest {}: sendTime '{}' is not HH:mm; not sent", digest.auditDigestId, digest.sendTime)
            return [sent: false, reason: "BAD_SEND_TIME"]
        }
        if (digest.lastSentDate != null && !(digest.lastSentDate as Date).toLocalDate().isBefore(today))
            return [sent: false, reason: "SENT_TODAY"]
        if (local.toLocalTime().isBefore(sendTime)) return [sent: false, reason: "NOT_YET"]
        boolean weekly = digest.kindEnumId == WEEKLY
        if (weekly && today.dayOfWeek.value != ((digest.dayOfWeek ?: 1) as int)) return [sent: false, reason: "NOT_TODAY"]
        if (!claim(ec, digest, tenantId, today)) return [sent: false, reason: "SENT_TODAY"]

        String chatSpaceId = normalize(digest.chatSpaceId)
        def space = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.TENANT_CHAT_SPACE,
                "chatSpaceId", chatSpaceId, tenantId, "audit digest destination — owner checked against the digest's tenant")
        Map destination = TenantNotificationSupport.describeDestination(ec, tenantId, chatSpaceId, space)
        if (destination == null) {
            logger.warn("Audit digest {}: chat space {} cannot be used (missing, another tenant's, inactive or without a webhook); not sent",
                    digest.auditDigestId, chatSpaceId)
            return [sent: false, reason: "NO_DESTINATION"]
        }
        Map report = assemble(ec, tenantId, digest.auditId as String, today)
        if (report == null) return [sent: false, reason: "NO_REPORT"]
        Map weekAgo = weekly ? assemble(ec, tenantId, digest.auditId as String, today.minusDays(7)) : null
        String text = render(report, digest.kindEnumId as String, digest.fullList == "Y", weekAgo, reportUrl(ec, report))
        Map delivery = TenantNotificationSupport.deliverToDestination(destination, [text: text])
        if (delivery?.ok != true) {
            // A failed post keeps the day's claim, as a run notification does: no retry storm on a dead webhook.
            logger.warn("Audit digest {} to space {} returned status {}{}", digest.auditDigestId, chatSpaceId,
                    delivery?.statusCode, delivery?.providerMessage ? " (${delivery.providerMessage})" : "")
            return [sent: false, reason: "DELIVERY_FAILED"]
        }
        return [sent: true, reason: null]
    }

    private static boolean claim(def ec, Map digest, String tenantId, LocalDate today) {
        Date day = Date.valueOf(today)
        def notToday = ec.entity.conditionFactory.makeCondition([
                ec.entity.conditionFactory.makeCondition("lastSentDate", EntityCondition.EQUALS, null),
                ec.entity.conditionFactory.makeCondition("lastSentDate", EntityCondition.LESS_THAN, day)],
                EntityCondition.OR)
        long updated = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.audit.AuditDigest",
                "atomic digest claim: conditional update keyed by digest id and tenant where not sent today")
                .condition("auditDigestId", digest.auditDigestId).condition("companyUserGroupId", tenantId)
                .condition(notToday).updateAll([lastSentDate: day]) ?: 0
        return updated > 0
    }

    private static Map assemble(def ec, String tenantId, String auditId, LocalDate day) {
        Map out = AutomationExecutionSupport.dispatchInternalService(ec, "audit.AuditReportServices.assemble#AuditReport",
                [companyUserGroupId: tenantId, auditId: auditId, reportDate: Date.valueOf(day)])
        if (ec.message.hasError()) {
            logger.warn("Audit digest: report for audit {} on {} refused: {}", auditId, day, ec.message.errorsString)
            ec.message.clearErrors()
            return null
        }
        return out?.document as Map
    }

    /** The app's audit page for that morning; null when the app's address is not configured (the line drops). */
    static String reportUrl(def ec, Map report) {
        String base = TenantNotificationSupport.resolveAppBaseUrl(ec)
        Map head = (report?.report ?: [:]) as Map
        String code = head.audit instanceof Map ? (head.audit as Map).id : null
        if (!base || !code || !head.date) return null
        return "${base}/audits/${URLEncoder.encode(code, 'UTF-8')}?date=${head.date}".toString()
    }

    private static ZoneId tenantZone(def ec, String tenantId) {
        def setting = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.TenantSetting", WHY)
                .condition("companyUserGroupId", tenantId).useCache(false).one()
        try { return setting?.timeZone ? ZoneId.of(setting.timeZone as String) : ZoneId.of("UTC") }
        catch (Exception ignored) { return ZoneId.of("UTC") }
    }

    private static String dayLabel(String date) {
        try { return date ? LocalDate.parse(date).format(DAY) : null }
        catch (DateTimeParseException ignored) { return date }
    }

    private static int count(Object value) {
        return value instanceof Number ? (value as Number).intValue() : 0
    }

    private static int length(List<String> lines) {
        return lines.join("\n").length()
    }
}
