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
        String webhookUrl = null
        try {
            webhookUrl = DarpanSettings.stringValue(WEBHOOK_SETTING)
            if (!webhookUrl) return false

            Timestamp when = (event.scheduledDate ?: event.completedDate ?: new Timestamp(System.currentTimeMillis())) as Timestamp
            LocalDate day = RunErrorKinds.runDay(when, event.zoneId as String)
            String subjectKey = event.checkId ? "${event.auditId ?: ''}:${event.checkId}".toString() : (event.automationId as String)
            String triggerType = event.scheduled ? "scheduled" : ((event.trigger ?: "manual") as String)
            // The claim runs in its own transaction and behind a fresh message scope: a failure there never rolls back the run's
            // status and never leaves an error in the caller's messages, and an error the caller already holds never blocks it.
            Map claim = claimSlot(ec, [tenantId: event.tenantId, subjectKey: subjectKey, runDay: SqlDate.valueOf(day),
                    triggerType: triggerType, errorKind: event.kind, executionId: event.executionId], webhookUrl)
            if (claim == null || !claim.isNew) return false

            int streak = event.scheduled ? streakDays(day, (claim.priorDays ?: []) as List) : 1
            Map payload = [text: cardText(event, streak)]
            Map delivery = (postHook != null ? postHook.call(webhookUrl, payload) :
                    TenantNotificationSupport.deliverToWebhook(webhookUrl, payload)) as Map
            boolean ok = delivery?.ok == true
            if (!ok) logger.warn("Error card for {} {} was not delivered (status {})", event.tenantId, subjectKey, delivery?.statusCode)
            return ok
        } catch (Throwable t) {
            logger.warn("Error card for {} failed: {}", event?.tenantId, failureNote(t, webhookUrl))
            return false
        }
    }

    /**
     * The claim call, isolated from the caller: its own transaction (requireNewTransaction, so a rollback-only caller cannot stop
     * it), and its errors and messages are discarded, not merged into the caller's (clearErrors would turn them into danger
     * messages a UI-started run could show). The caller's messages, public messages and errors come back exactly as they were.
     */
    private static Map claimSlot(def ec, Map params, String webhookUrl) {
        List infos = new ArrayList(ec.message.messageInfos)
        List publics = new ArrayList(ec.message.publicMessageInfos)
        ec.message.pushErrors()
        Map claim = null
        try {
            claim = ec.service.sync().name("ops.OpsServices.record#RunErrorNotice").requireNewTransaction(true).parameters(params).call()
            if (ec.message.hasError()) claim = null
        } catch (Throwable t) {
            logger.warn("Error card claim failed: {}", failureNote(t, webhookUrl))
        } finally {
            ec.message.clearAll()
            // addPublic puts one MessageInfo in both lists, so the snapshot of the general list already holds the public ones
            infos.each { mi ->
                if (publics.any { it.is(mi) }) ec.message.addPublic(mi.message, mi.type)
                else ec.message.addMessage(mi.message, mi.type)
            }
            ec.message.popErrors()
        }
        return claim
    }

    /** The exception class and the webhook's host: never the exception message (it can carry the URL's key and token) nor the URL. */
    static String failureNote(Throwable t, String webhookUrl) {
        String host = null
        try { host = new URI(webhookUrl?.trim()).host } catch (Exception ignored) { }
        return "${t.getClass().name} (webhook host ${host ?: 'unreadable'})".toString()
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
