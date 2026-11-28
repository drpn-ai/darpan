package darpan.ops

import darpan.common.MessageScope
import darpan.common.SecretRedactor
import darpan.config.DarpanSettings
import darpan.email.EmailCopyReader
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date as SqlDate
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * DAR-BE-015 (spec F). The daily Darpan error email, sent by the hourly job (ops.ErrorDigestServices.send#DueErrorDigest)
 * once a day in the UTC hour errors.digest.hourUtc, every day ("none" included), so a missing email means the service is
 * broken. The day is claimed first (ops.OpsServices.claim#ErrorDigestDay), so a second caller sends nothing. The email
 * covers everything since the last email that went out (capped at 30 days back): run errors, the watchdog and the ERROR log
 * lines, read across every tenant (TenantScopedFinder.findGlobalUnscoped, read-only: this is Darpan's own report, never a
 * tenant's) and assembled by ErrorDigestAssembler. It goes to errors.email.to, and a one-line summary to
 * errors.chat.webhookUrl. A blank destination is skipped with one warning each.
 */
class ErrorDigestSupport {
    private static final Logger logger = LoggerFactory.getLogger(ErrorDigestSupport)
    static final String TEMPLATE = ErrorDigestMessage.TEMPLATE
    static final String EMAIL_SETTING = "errors.email.to"
    static final String WEBHOOK_SETTING = ErrorChatSupport.WEBHOOK_SETTING
    static final String HOUR_SETTING = "errors.digest.hourUtc"
    static final String WATCHDOG_SETTING = "errors.watchdog.hours"
    static final String EMPTY_STREAK_SETTING = "errors.emptyStreak.days"
    /** DAR-BE-396: regexes, one per line, for log lines Darpan wrote while refusing something on purpose. */
    static final String IGNORE_SETTING = "errors.email.ignorePatterns"
    /** The furthest back an email reaches (a long outage of the email does not make one huge email); also the streak history read. */
    static final int LOOKBACK_DAYS = 30
    private static final String WHY = "DAR-BE-015 daily Darpan error email: read-only scan across every tenant for Darpan's own staff"

    static final String NOT_THE_HOUR = "NOT_THE_HOUR", ALREADY_SENT = "ALREADY_SENT", NO_DESTINATION = "NO_DESTINATION",
                        DELIVERY_FAILED = "DELIVERY_FAILED", CLAIM_FAILED = "CLAIM_FAILED"

    private static final Closure DEFAULT_CLAIM = { def ec, LocalDate day, Timestamp now -> claimDay(ec, day, now) }
    private static final Closure DEFAULT_FINISH = { def ec, LocalDate day, Map fields -> finishDay(ec, day, fields) }
    private static final Closure DEFAULT_LOAD = { def ec, Timestamp since, Timestamp now, int watchdogHours, int emptyStreakDays ->
        readInput(ec, since, now, watchdogHours, emptyStreakDays)
    }
    /** The real send: true only when the mail server took it (sendEmailTemplate set a messageId). */
    static final Closure DEFAULT_SEND_EMAIL = { def ec, String to, Map bodyParameters -> sendThroughTemplate(ec, to, bodyParameters) }
    private static final Closure DEFAULT_POST_CHAT = { String url, Map payload -> TenantNotificationSupport.deliverToWebhook(url, payload) }
    private static final Closure DEFAULT_COPY = { def ec -> new EmailCopyReader(ec, TEMPLATE) }

    /** Seam: claims the UTC day, (ec, day, now) -> [isNew, previousSentDate]. */
    static Closure claim = DEFAULT_CLAIM
    /** Seam: records the day's outcome, (ec, day, [delivered, checksCount, logErrorsCount]). */
    static Closure finish = DEFAULT_FINISH
    /** Seam: the cross-tenant read, (ec, since, now, watchdogHours, emptyStreakDays) -> ErrorDigestAssembler input plus instance, tenantNames, automationNames. */
    static Closure loadInput = DEFAULT_LOAD
    /** Seam: the email send, (ec, toAddresses, bodyParameters) -> true when it went out. */
    static Closure sendEmail = DEFAULT_SEND_EMAIL
    /** Seam: the chat post, (webhookUrl, payload) -> delivery map. */
    static Closure postChat = DEFAULT_POST_CHAT
    /** Seam: the words, (ec) -> EmailCopyReader for DARPAN_ERROR_DIGEST. */
    static Closure copy = DEFAULT_COPY

    /** Test-only: restores every seam. Idempotent; never called from src/main. */
    static void resetForTests() {
        claim = DEFAULT_CLAIM
        finish = DEFAULT_FINISH
        loadInput = DEFAULT_LOAD
        sendEmail = DEFAULT_SEND_EMAIL
        postChat = DEFAULT_POST_CHAT
        copy = DEFAULT_COPY
    }

    /**
     * Sends the day's email when now is in the hour errors.digest.hourUtc (UTC) and the day is not claimed yet.
     * Returns [sent, reason]: reason null when the email went out, else NOT_THE_HOUR, ALREADY_SENT, CLAIM_FAILED (the claim failed twice), NO_DESTINATION (no
     * address set; the chat summary still goes when the webhook is set) or DELIVERY_FAILED; plus subject and warnings when
     * it ran. Never throws past the claim: a failure after it is a warning and DELIVERY_FAILED.
     */
    static Map sendIfDue(def ec, Timestamp now) {
        ZonedDateTime utc = now.toInstant().atZone(ZoneOffset.UTC)
        if (utc.hour != DarpanSettings.intValue(HOUR_SETTING)) return [sent: false, reason: NOT_THE_HOUR]
        List<String> warnings = []
        List<String> addresses = addressesOf(DarpanSettings.stringValue(EMAIL_SETTING))
        String webhookUrl = DarpanSettings.stringValue(WEBHOOK_SETTING)?.trim() ?: null
        if (!addresses && !webhookUrl) {
            warn(warnings, "${EMAIL_SETTING} and ${WEBHOOK_SETTING} are both blank: the daily error email and its summary were not sent")
            return [sent: false, reason: NO_DESTINATION, warnings: warnings]
        }

        LocalDate day = utc.toLocalDate()
        // A failed claim is null: another node's create won the key, or the store failed. One retry tells them apart: after a
        // lost race the day's row exists, so the retry answers isNew false. A second failure is a real one, never "already sent".
        Map claimed = (claim.call(ec, day, now) ?: claim.call(ec, day, now)) as Map
        if (claimed == null) {
            logger.error("Daily error email for {} not sent: the day could not be claimed (see the warning above)", day)
            return [sent: false, reason: CLAIM_FAILED]
        }
        if (claimed.isNew != true) return [sent: false, reason: ALREADY_SENT]

        boolean emailed = false
        Map counts = [checks: 0, logErrors: 0]
        String subject = null
        try {
            Timestamp earliest = minus(now, Duration.ofDays(LOOKBACK_DAYS))
            Timestamp since = (claimed.previousSentDate ?: minus(now, Duration.ofHours(24))) as Timestamp
            if (since.before(earliest)) since = earliest
            int watchdogHours = DarpanSettings.intValue(WATCHDOG_SETTING)
            int emptyStreakDays = DarpanSettings.intValue(EMPTY_STREAK_SETTING)
            Map input = (loadInput.call(ec, since, now, watchdogHours, emptyStreakDays) ?: [:]) as Map
            List<String> ignorePatterns = (DarpanSettings.stringValue(IGNORE_SETTING) ?: "").readLines()
            Map digest = ErrorDigestAssembler.assemble(input + [since: since, now: now, watchdogHours: watchdogHours,
                                                                emptyStreakDays: emptyStreakDays, ignorePatterns: ignorePatterns])
            counts = digest.counts as Map
            subject = digest.subject
            Map body = [instance       : input.instance, since: since, now: now, generatedAt: new Timestamp(System.currentTimeMillis()),
                        runErrors      : digest.runErrors, watchdog: digest.watchdog, logErrors: digest.logErrors, ignoredLogErrors: digest.ignoredLogErrors ?: [], counts: counts,
                        tenantNames    : input.tenantNames ?: [:], automationNames: input.automationNames ?: [:],
                        emptyStreakDays: emptyStreakDays]
            if (addresses) {
                Object sent = sendEmail.call(ec, addresses.join(", "), [digestSubject: subject, digestBody: body])
                emailed = sent == true
                if (!emailed) warn(warnings, "the daily error email to ${addresses.size()} address(es) could not be sent" +
                        (sent instanceof String ? ": ${sent}" : ""))
            } else {
                warn(warnings, "${EMAIL_SETTING} is blank: the daily error email was not sent")
            }
        } catch (Throwable t) {
            warn(warnings, "the daily error email could not be built: ${t.getClass().name}: ${SecretRedactor.redact(t.message)}")
        }

        if (webhookUrl) {
            String state = emailed ? "sent" : (addresses ? "failed" : "noemail")
            try {
                // Built inside the try: the claim is taken, so nothing past it may throw (the day must still be finished).
                String line = ErrorDigestMessage.chatLine(counts, addresses.join(", "), state, copy.call(ec) as EmailCopyReader)
                Map delivery = postChat.call(webhookUrl, [text: line]) as Map
                if (delivery?.ok != true) warn(warnings, "the daily error summary was not delivered (status ${delivery?.statusCode})")
            } catch (Throwable t) {
                warn(warnings, "the daily error summary failed: ${ErrorChatSupport.failureNote(t, webhookUrl)}")
            }
        } else {
            warn(warnings, "${WEBHOOK_SETTING} is blank: the daily error summary was not posted")
        }

        try {
            finish.call(ec, day, [delivered: emailed, checksCount: counts.checks ?: 0, logErrorsCount: counts.logErrors ?: 0])
        } catch (Throwable t) {
            warn(warnings, "the day's record could not be finished: ${t.getClass().name}")
        }
        return [sent: emailed, reason: emailed ? null : (addresses ? DELIVERY_FAILED : NO_DESTINATION), subject: subject, warnings: warnings]
    }

    /** The name the subject carries: the host of the app's base address (e.g. app.drpn.ai), else "unknown". */
    static String instanceName(def ec) {
        try {
            String base = TenantNotificationSupport.resolveAppBaseUrl(ec)
            String host = base ? new URI(base).host : null
            return host ?: "unknown"
        } catch (Throwable ignored) {
            return "unknown"
        }
    }

    /** The comma- or semicolon-separated addresses, trimmed, blanks dropped. */
    static List<String> addressesOf(String raw) {
        return (raw ?: "").split(/[,;]/)*.trim().findAll { it }.unique()
    }

    private static void warn(List<String> warnings, String text) {
        warnings << text
        logger.warn("Daily error email: {}", text)
    }

    private static Timestamp minus(Timestamp at, Duration d) { new Timestamp(at.time - d.toMillis()) }

    // ---------------------------------------------------------------- the claim (ops.OpsServices, own transactions)

    private static Map claimDay(def ec, LocalDate day, Timestamp now) {
        return isolated(ec, "ops.OpsServices.claim#ErrorDigestDay", [digestDay: SqlDate.valueOf(day), nowStamp: now])
    }

    private static void finishDay(def ec, LocalDate day, Map fields) {
        isolated(ec, "ops.OpsServices.finish#ErrorDigestDay", [digestDay: SqlDate.valueOf(day)] + fields)
    }

    /** The call in its own transaction; a failure (another node's claim on the key) is null and leaves no error or message behind. */
    private static Map isolated(def ec, String service, Map params) {
        return MessageScope.isolated(ec) {
            try {
                Map out = ec.service.sync().name(service).requireNewTransaction(true).parameters(params).call() as Map
                return ec.message.hasError() ? null : out
            } catch (Throwable t) {
                logger.warn("Daily error email: {} failed ({})", service, t.getClass().name)
                return null
            }
        }
    }

    /**
     * send#EmailTemplate, judged by what it returns. It never fails as an error: a mail server that refuses or cannot be
     * reached becomes a message "Error sending email: ...", an EmailServer with no smtpHost and To addresses all outside the
     * allowed domains return quietly. Only a messageId (set once the server took the message) is a send. Returns true, or why
     * not (a String, also logged). The caller's messages, public messages and errors come back exactly as they were.
     */
    static Object sendThroughTemplate(def ec, String to, Map bodyParameters) {
        String why = MessageScope.isolated(ec) {
            int before = ec.message.messageInfos.size()
            try {
                Map out = AutomationExecutionSupport.dispatchInternalService(ec, "org.moqui.impl.EmailServices.send#EmailTemplate",
                        [emailTemplateId: TEMPLATE, toAddresses: to, bodyParameters: bodyParameters])
                // a message id means the server took it; an error after that (recording the EmailMessage row) is not a failed send
                if (out?.messageId) return null
                if (ec.message.hasError()) return ec.message.errorsString as String
                List added = ec.message.messageInfos.drop(before)
                String said = added.collect { it.message as String }.find { it?.trim() }
                if (said) return said
                return noSmtpHost(ec) ? "the email server of ${TEMPLATE} has no SMTP host" : "the mail server returned no message id"
            } catch (Throwable t) {
                return t.getClass().name
            }
        }
        if (why == null) return true
        logger.warn("Daily error email not sent: {}", why)
        return why
    }

    private static boolean noSmtpHost(def ec) {
        try {
            def template = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.email.EmailTemplate", WHY)
                    .condition("emailTemplateId", TEMPLATE).useCache(false).one()
            def server = template?.emailServerId ? TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.email.EmailServer", WHY)
                    .condition("emailServerId", template.emailServerId).useCache(false).one() : null
            return server != null && !server.smtpHost
        } catch (Throwable ignored) {
            return false
        }
    }

    // ---------------------------------------------------------------- the cross-tenant read

    private static final List<String> EXECUTION_FIELDS = ["automationExecutionId", "automationId", "companyUserGroupId", "statusEnumId",
            "noDataKind", "triggerType", "scheduledDate", "completedDate", "errorMessage", "file1ReadCount", "file2ReadCount",
            "file1RecordCount", "file2RecordCount", "file1Name", "file2Name", "reconciliationRunResultId", "resultDataManagerPath", "retryCount"]
    private static final String EXECUTION = "darpan.reconciliation.ReconciliationAutomationExecution"
    private static final String SUCCESS = "AUT_STAT_SUCCESS", NO_DATA = "AUT_STAT_NO_DATA"

    /**
     * Everything the assembler needs, across every tenant: executions that ended in the window (the earlier of since and
     * now − watchdogHours) plus the error executions of the last 30 days (for "days in a row"), each with its automation's
     * zone and, for the window's errors, the run's link; one row per audit check whose audit runs and whose statement is on,
     * its last healthy scheduled run floored at its automation's creation (a check younger than the watchdog is not stale)
     * and its last scheduled healthy reads (error runs never count as reading nothing); the ERROR log rows written since
     * (flushed first). Read-only.
     */
    static Map readInput(def ec, Timestamp since, Timestamp now, int watchdogHours, int emptyStreakDays) {
        def cf = ec.entity.conditionFactory
        Timestamp watchStart = minus(now, Duration.ofHours(watchdogHours))
        Timestamp windowStart = since.before(watchStart) ? since : watchStart
        List<Map> executions = TenantScopedFinder.findGlobalUnscoped(ec, EXECUTION, WHY)
                .condition("completedDate", EntityCondition.GREATER_THAN_EQUAL_TO, windowStart)
                .condition("completedDate", EntityCondition.LESS_THAN_EQUAL_TO, now)
                .selectFields(EXECUTION_FIELDS).useCache(false).list()*.getMap()
        executions.addAll(TenantScopedFinder.findGlobalUnscoped(ec, EXECUTION, WHY)
                .condition("completedDate", EntityCondition.GREATER_THAN_EQUAL_TO, minus(now, Duration.ofDays(LOOKBACK_DAYS)))
                .condition("completedDate", EntityCondition.LESS_THAN, windowStart)
                .condition(cf.makeCondition([cf.makeCondition("statusEnumId", EntityCondition.IN, ["AUT_STAT_FAILED", "AUT_STAT_DEAD_LETTER"]),
                        cf.makeCondition([cf.makeCondition("statusEnumId", EntityCondition.EQUALS, NO_DATA),
                                          cf.makeCondition("noDataKind", EntityCondition.NOT_EQUAL, null)], EntityCondition.AND)], EntityCondition.OR))
                .selectFields(EXECUTION_FIELDS).useCache(false).list()*.getMap().findAll { RunErrorKinds.of(it) != null })

        List<Map> checkRows = auditChecks(ec)
        Set<String> automationIds = (executions*.automationId + checkRows*.automationId).findAll { it } as Set<String>
        Map<String, Map> automations = !automationIds ? [:] : TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationAutomation", WHY)
                .condition("automationId", EntityCondition.IN, automationIds).useCache(false).list()*.getMap()
                .collectEntries { [(it.automationId as String): it] }

        executions = executions.collect { Map ex ->
            Map automation = automations[ex.automationId as String]
            Map row = new LinkedHashMap(ex)
            row.zoneId = automation?.windowTimeZone
            Timestamp end = ex.completedDate as Timestamp
            if (automation != null && RunErrorKinds.of(ex) != null && end != null && end.after(since)) {
                row.runUrl = AutomationExecutionSupport.runErrorUrl(ec, automation, ex, ex.reconciliationRunResultId as String)
            }
            row
        }

        Map<String, Map> health = [:]
        List<Map> checks = checkRows.collect { Map c ->
            Map automation = c.automationId ? automations[c.automationId as String] : null
            boolean active = automation != null && (automation.isActive ?: "Y") != "N" && automation.isArchived != "Y"
            Map h = active ? health.computeIfAbsent(c.automationId as String) { String id -> automationHealth(ec, cf, automation, emptyStreakDays) } : [:]
            c + [automationId: automation ? c.automationId : null, automationActive: active,
                 lastSuccessDate: h.lastSuccessDate, recentScheduledReads: h.reads ?: []]
        }

        ErrorLogCapture.flush(ec)
        List<Map> logRows = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.ops.ErrorLogEntry", WHY)
                .condition("lastSeen", EntityCondition.GREATER_THAN, since).orderBy("-count").useCache(false).list()*.getMap()

        Set<String> tenantIds = (executions*.companyUserGroupId + checks*.tenantId + logRows*.tenantId).findAll { it } as Set<String>
        Map tenantNames = !tenantIds ? [:] : TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup", WHY)
                .condition("userGroupId", EntityCondition.IN, tenantIds).useCache(false).list()
                .collectEntries { [(it.userGroupId): it.description ?: it.userGroupId] }
        return [instance: instanceName(ec), executions: executions, checks: checks, logRows: logRows, tenantNames: tenantNames,
                automationNames: automations.collectEntries { k, v -> [(k): v.automationName] }]
    }

    /**
     * One row per audit check that a running audit's active statement links to: its statements' codes, its audit's name, its
     * automation id. A check whose statements are all off, or whose audits are all paused, is not watched.
     */
    private static List<Map> auditChecks(def ec) {
        Map<String, Map> audits = rows(ec, "darpan.audit.Audit").collectEntries { [(it.auditId as String): it] }
        Map<String, Map> statements = rows(ec, "darpan.audit.AuditStatement").collectEntries { [(it.statementId as String): it] }
        Map<String, List<Map>> links = rows(ec, "darpan.audit.AuditStatementCheck").groupBy { it.checkId as String }
        List<Map> out = []
        rows(ec, "darpan.audit.AuditCheck").each { Map check ->
            List<Map> live = (links[check.checkId as String] ?: []).collect { statements[it.statementId as String] }.findAll { Map st ->
                st != null && (st.isActive ?: "Y") != "N" && audits[st.auditId as String]?.scheduleActive == "Y" &&
                        st.companyUserGroupId == check.companyUserGroupId
            }
            if (!live) return
            Map audit = audits[live[0].auditId as String]
            out << [tenantId: check.companyUserGroupId, auditName: audit?.auditName,
                    statementId: (live*.statementCode as List<String>).sort().join(", "), checkId: check.checkCode,
                    automationId: check.automationId ?: null, auditScheduleActive: true, statementActive: true]
        }
        return out
    }

    private static List<Map> rows(def ec, String entity) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entity, WHY).useCache(false).list()*.getMap()
    }

    /** An automation's last healthy scheduled run (floored at its creation) and its last healthy scheduled reads, newest first. */
    private static Map automationHealth(def ec, def cf, Map automation, int emptyStreakDays) {
        def scheduled = cf.makeCondition([cf.makeCondition("triggerType", EntityCondition.EQUALS, null),
                                          cf.makeCondition("triggerType", EntityCondition.EQUALS, "scheduled")], EntityCondition.OR)
        def healthy = cf.makeCondition([cf.makeCondition("statusEnumId", EntityCondition.EQUALS, SUCCESS),
                                        cf.makeCondition([cf.makeCondition("statusEnumId", EntityCondition.EQUALS, NO_DATA),
                                                          cf.makeCondition("noDataKind", EntityCondition.EQUALS, null)], EntityCondition.AND)],
                EntityCondition.OR)
        def runs = { -> TenantScopedFinder.findGlobalUnscoped(ec, EXECUTION, WHY)
                .condition("automationId", automation.automationId).condition("companyUserGroupId", automation.companyUserGroupId)
                .condition(scheduled).condition(healthy).condition("completedDate", EntityCondition.NOT_EQUAL, null)
                .orderBy("-completedDate").useCache(false) }
        Timestamp last = runs().limit(1).list().find()?.completedDate as Timestamp
        Timestamp created = automation.createdDate as Timestamp
        if (created != null && (last == null || created.after(last))) last = created
        List reads = runs().condition("file1ReadCount", EntityCondition.NOT_EQUAL, null)
                .condition("file2ReadCount", EntityCondition.NOT_EQUAL, null).limit(Math.max(emptyStreakDays, 1)).list()
                .collect { [it.file1ReadCount, it.file2ReadCount] }
        return [lastSuccessDate: last, reads: reads]
    }
}
