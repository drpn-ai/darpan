package darpan.audit

import darpan.common.DarpanEntityConstants
import darpan.email.EmailCopyReader
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
    static final String EMAIL_TEMPLATE = "DARPAN_AUDIT_DIGEST"
    private static final java.util.regex.Pattern EMAIL = ~/[^@\s,;]+@[^@\s,;]+\.[^@\s,;]+/

    /** Every digest email goes through here, so tests record sends instead of making them (as PasswordResetSupport). */
    private static final Closure DEFAULT_SEND_EMAIL = { def ec, String toAddresses, Map bodyParameters ->
        AutomationExecutionSupport.dispatchInternalService(ec, "org.moqui.impl.EmailServices.send#EmailTemplate",
                [emailTemplateId: EMAIL_TEMPLATE, toAddresses: toAddresses, bodyParameters: bodyParameters])
    }
    static Closure sendEmail = DEFAULT_SEND_EMAIL

    /** Test-only: restores the seam to its production value. Idempotent; never called from src/main. */
    static void resetForTests() { sendEmail = DEFAULT_SEND_EMAIL }

    /**
     * The digest text. weekAgo is the report of seven mornings before, for a weekly summary; null for a daily one.
     * fullList lists every open finding the report carries, as far as one message holds.
     * DAR-BE-303: the fixed words are DARPAN_AUDIT_DIGEST copy blocks read through copy; the counts, finding rows and the
     * report link stay here. Chat and email get the same text, so both read the same blocks, except the not-run notice:
     * withNotice false (the chat room's copy) leaves it out, since a client room never gets a warning (DAR-BE-378).
     */
    static String render(Map report, String kindEnumId, boolean fullList, Map weekAgo, String reportUrl, EmailCopyReader copy, EmailCopyReader runCopy,
                         boolean withNotice = true) {
        Map head = (report?.report ?: [:]) as Map
        Map health = (report?.health ?: [:]) as Map
        Map summary = (report?.summary ?: [:]) as Map
        boolean weekly = kindEnumId == WEEKLY
        List<String> lines = []

        String day = dayLabel(head.date as String)
        lines.add([head.audit instanceof Map ? (head.audit as Map).name : null, head.period,
                   weekly ? copy.text("week", [day: day]) : day].findAll { it }.join(" · "))
        // DAR-BE-363: a morning with a check that did not run carries the run update's neutral notice (DARPAN_AUDIT_RUN
        // blocks, read through runCopy), built from the same rule facts; never health.summary, which names systems and causes.
        String notice = withNotice ? AuditRunMessage.notRunNotice(AuditRunFacts.build(report, [], null), runCopy) : null
        if (notice) lines.add(notice)
        lines.add("")

        if (weekly) {
            // DAR-BE-361: today's acknowledged rows left the open count; each side counts open + acknowledged, so a
            // week of acknowledging reads as no change.
            Map before = (weekAgo?.summary ?: [:]) as Map
            lines.add(copy.text("open.weekly", [open: count(summary.open) + count(summary.acknowledged),
                                                before: count(before.open) + count(before.acknowledged)]))
        } else {
            lines.add(copy.text("open.daily", [open: count(summary.open), new: count(summary.newToday), fixed: count(summary.fixedToday)]))
        }
        if (count(summary.acknowledged) > 0) lines.add(copy.text("acknowledged", [count: count(summary.acknowledged)]))
        ((report?.categories ?: [:]) as Map).each { String id, Object raw ->
            Map category = raw as Map
            Map counts = (category.counts ?: [:]) as Map
            if (count(counts.open)) lines.add(copy.text("category", [label: category.label ?: id, open: count(counts.open)]))
        }

        List<Map> open = findings(report, "open")
        if (!open) {
            lines.add("")
            lines.add(copy.text("none"))
        }
        List<String> tail = reportUrl ? ["", "${copy.text('report')} ${reportUrl}".toString()] : []
        int omitted = fullList ? omittedCount(report) : 0
        List<List> groups = fullList ?
                [[copy.text("list.all", [count: open.size()]), open]] :
                [[copy.text("list.oldest"), open.take(OLDEST_SHOWN)]]
        int left = fullList ? open.size() + omitted : 0
        boolean full = false
        for (List group : groups) {
            List<Map> rows = group[1] as List<Map>
            if (!rows || full) continue
            String title = group[0]
            if (length(lines + ["", title] + tail) > MESSAGE_LIMIT) { full = true; continue }
            lines.add("")
            lines.add(title as String)
            for (Map finding : rows) {
                String line = findingLine(finding, copy)
                // Leave room for the "more" line, so the cut is always said.
                if (length(lines + [line, copy.text("more", [left: 9999])] + tail) > MESSAGE_LIMIT) { full = true; break }
                lines.add(line)
                left--
            }
        }
        if (fullList && left > 0) lines.add(copy.text("more", [left: left]))
        lines.addAll(tail)
        return lines.join("\n")
    }

    /** "F-100000 · R2 · 6 days · Invoice INV8412671 has no Etail id". */
    static String findingLine(Map finding, EmailCopyReader copy) {
        int age = count(finding.ageDays)
        return [finding.id, finding.statement, age == 1 ? copy.text("age.one") : copy.text("age.many", [days: age]), finding.headline]
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
     * weekly, and not sent yet that day. Each is claimed before it is sent, so two job threads send it once. A run digest
     * (DAR-BE-207) is not on a clock: AuditRunUpdateSupport sends it when the audit's run ends.
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
                .condition(active).condition("kindEnumId", EntityCondition.NOT_EQUAL, AuditRunUpdateSupport.RUN)
                .orderBy("auditDigestId").useCache(false).list()
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
        Map destination = null
        if (chatSpaceId) {
            def space = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.TENANT_CHAT_SPACE,
                    "chatSpaceId", chatSpaceId, tenantId, "audit digest destination — owner checked against the digest's tenant")
            destination = TenantNotificationSupport.describeDestination(ec, tenantId, chatSpaceId, space)
            if (destination == null) logger.warn("Audit digest {}: chat space {} cannot be used (missing, another tenant's, inactive or without a webhook)",
                    digest.auditDigestId, chatSpaceId)
        }
        List<String> emails = withoutDisabled(ec, emailsOf(digest.emailAddresses))
        if (destination == null && !emails) return [sent: false, reason: "NO_DESTINATION"]
        Map report = assemble(ec, tenantId, digest.auditId as String, today)
        if (report == null) return [sent: false, reason: "NO_REPORT"]
        Map weekAgo = weekly ? assemble(ec, tenantId, digest.auditId as String, today.minusDays(7)) : null
        EmailCopyReader digestCopy = new EmailCopyReader(ec, EMAIL_TEMPLATE)
        EmailCopyReader runCopy = new EmailCopyReader(ec, AuditRunMessage.TEMPLATE)
        String text = render(report, digest.kindEnumId as String, digest.fullList == "Y", weekAgo, reportUrl(ec, report), digestCopy, runCopy)
        boolean delivered = false
        if (destination != null) {
            // DAR-BE-378: the client's room gets the digest without the not-run notice.
            String chatText = render(report, digest.kindEnumId as String, digest.fullList == "Y", weekAgo, reportUrl(ec, report),
                    digestCopy, runCopy, false)
            Map delivery = TenantNotificationSupport.deliverToDestination(destination, [text: chatText])
            if (delivery?.ok == true) delivered = true
            // A failed post keeps the day's claim, as a run notification does: no retry storm on a dead webhook.
            else logger.warn("Audit digest {} to space {} returned status {}{}", digest.auditDigestId, chatSpaceId,
                    delivery?.statusCode, delivery?.providerMessage ? " (${delivery.providerMessage})" : "")
        }
        if (emails) {
            sendEmail.call(ec, emails.join(","), [digestSubject: text.readLines()[0], digestText: text])
            if (ec.message.hasError()) {
                logger.warn("Audit digest {} email refused: {}", digest.auditDigestId, ec.message.errorsString)
                ec.message.clearErrors()
            } else {
                delivered = true
            }
        }
        return delivered ? [sent: true, reason: null] : [sent: false, reason: "DELIVERY_FAILED"]
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

    /** The digest's addresses: comma- or semicolon-separated, each shaped like an address; anything else is dropped. */
    static List<String> emailsOf(Object raw) {
        return (raw?.toString() ?: "").split(/[,;]/)*.trim().findAll { it && it.length() <= 254 && EMAIL.matcher(it).matches() }.unique()
    }

    /**
     * DAR-BE-397 (Aditi 2026-11-27: a disabled person must not be mailed). The addresses less any that belong to a disabled
     * Darpan account, compared without case; addresses of people with no account stay. Never throws: on a failed read the
     * list stands, so a lookup problem never stops an email the tenant is owed.
     */
    static List<String> withoutDisabled(def ec, List<String> emails) {
        if (!emails) return emails ?: []
        try {
            Set<String> off = (TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                    "email recipients: an address of a disabled account is never mailed")
                    .condition("disabled", "Y").useCache(false).list()*.emailAddress)
                    .collect { (it as String)?.trim()?.toLowerCase() }.findAll { it } as Set
            return off ? emails.findAll { !off.contains(it?.trim()?.toLowerCase()) } : emails
        } catch (Throwable t) {
            logger.warn("Disabled-account check on email recipients skipped: {}", t.message ?: t.toString())
            return emails
        }
    }

    /** The app's audit page for that morning; null when the app's address is not configured (the line drops). */
    static String reportUrl(def ec, Map report) {
        String base = TenantNotificationSupport.resolveAppBaseUrl(ec)
        Map head = (report?.report ?: [:]) as Map
        String code = head.audit instanceof Map ? (head.audit as Map).id : null
        if (!base || !code || !head.date) return null
        String tenant = head.tenant instanceof Map ? (head.tenant as Map).id : null
        // DAR-BE-293: names its tenant, as a run link does, so the app switches into it.
        String url = "${base}/audits/${URLEncoder.encode(code, 'UTF-8')}?date=${head.date}"
        return (tenant ? "${url}&tenantId=${URLEncoder.encode(tenant, 'UTF-8')}" : url).toString()
    }

    static ZoneId tenantZone(def ec, String tenantId) {
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
