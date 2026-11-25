package darpan.ops

import darpan.config.DarpanSettings
import darpan.reconciliation.notification.TenantNotificationSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date as SqlDate
import java.sql.Timestamp
import java.time.LocalDate

/**
 * DAR-BE-015. The Darpan-only error card: one chat post per check per day to the webhook in the setting
 * errors.chat.webhookUrl. Never reaches a client destination and never fails a run: any problem is logged and reported as
 * not posted. The day's RunErrorNotice row is the dedupe key and the memory of the streak.
 */
class ErrorChatSupport {
    private static final Logger logger = LoggerFactory.getLogger(ErrorChatSupport)
    static final String WEBHOOK_SETTING = "errors.chat.webhookUrl"
    static final int ERROR_MAX_CHARS = 300

    /** Seam: replaces the outgoing post, called with (webhookUrl, payload) and returning the delivery map. Null in production. */
    static Closure postHook = null

    static void resetForTests() { postHook = null }

    static boolean postErrorCard(def ec, Map event) {
        try {
            String webhookUrl = DarpanSettings.stringValue(WEBHOOK_SETTING)
            if (!webhookUrl) return false

            LocalDate day = RunErrorKinds.runDay(event.scheduledDate as Timestamp, event.zoneId as String)
            String subjectKey = ((event.checkId ?: event.automationId) as String)
            Map claim = ec.service.sync().name("ops.OpsServices.record#RunErrorNotice").parameters([
                    tenantId: event.tenantId, subjectKey: subjectKey, runDay: SqlDate.valueOf(day),
                    errorKind: event.kind, executionId: event.executionId]).call()
            if (ec.message.hasError() || claim == null || !claim.isNew) return false

            int streak = event.scheduled ? streakDays(day, (claim.priorDays ?: []) as List) : 1
            Map payload = [text: cardText(event, streak)]
            Map delivery = (postHook != null ? postHook.call(webhookUrl, payload) :
                    TenantNotificationSupport.deliverToWebhook(webhookUrl, payload)) as Map
            boolean ok = delivery?.ok == true
            if (!ok) logger.warn("Error card for {} {} was not delivered: {}", event.tenantId, subjectKey, delivery)
            return ok
        } catch (Throwable t) {
            logger.warn("Error card for {} failed: {}", event?.tenantId, t.toString())
            return false
        }
    }

    /** This day plus the consecutive earlier days that also had a card. priorDays is newest first. */
    static int streakDays(LocalDate day, List priorDays) {
        Set<LocalDate> prior = priorDays.collect { Object d -> d instanceof SqlDate ? ((SqlDate) d).toLocalDate() : LocalDate.parse(d.toString()) } as Set
        int count = 1
        LocalDate cursor = day.minusDays(1)
        while (prior.contains(cursor)) { count++; cursor = cursor.minusDays(1) }
        return count
    }

    static String cardText(Map event, int streak) {
        String dot = " · "
        String head = event.checkId ?
                [event.tenantName, "${event.auditName ?: ''} ${event.statementId ?: ''}".trim(), event.checkId].join(dot) :
                [event.tenantName, event.automationName ?: event.automationId].join(dot)
        String kindLine = event.kind.toString()
        if (event.attempts) kindLine += " after ${event.attempts} tries"
        if (event.scheduled && streak >= 2) kindLine += dot + ordinal(streak) + " day in a row"
        if (event.trigger == "manual" || event.trigger == "play") kindLine += dot + event.trigger
        List<String> lines = [head, kindLine]
        String first = (event.errorFirstLine ?: "").toString().readLines().find { it.trim() }?.trim()
        if (first) lines << (first.length() > ERROR_MAX_CHARS ? first.substring(0, ERROR_MAX_CHARS) : first)
        String counts = countsLine(event.readCounts as Map, event.keptCounts as Map, dot)
        if (counts) lines << counts
        if (event.runUrl) lines << event.runUrl.toString()
        return lines.join("\n")
    }

    private static String countsLine(Map read, Map kept, String dot) {
        if (!read) return null
        String line = "Read: " + read.collect { k, v -> "${k} ${fmt(v)}" }.join(dot)
        if (kept) line += " → kept " + kept.collect { k, v -> "${k} ${fmt(v)}" }.join(dot)
        return line
    }

    private static String fmt(Object n) { return String.format("%,d", ((Number) n).longValue()) }

    static String ordinal(int n) {
        int m100 = n % 100
        if (m100 >= 11 && m100 <= 13) return n + "th"
        switch (n % 10) {
            case 1: return n + "st"
            case 2: return n + "nd"
            case 3: return n + "rd"
            default: return n + "th"
        }
    }
}
