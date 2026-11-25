package darpan.audit

import darpan.config.DarpanSettings
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.ZoneId

/**
 * DAR-BE-361. Feedback a tenant user sends Darpan about one finding. The note is saved first, in its own transaction, so
 * nothing after it can lose it; then a card goes to Darpan's one chat space (feedback.chat.webhookUrl, unset = no card) and
 * the sender gets the DARPAN_FEEDBACK_RECEIVED thank-you. A failed card or email is logged at ERROR, so the DAR-BE-015
 * log capture (ErrorLogCapture keeps ERROR and above) reports it, and is never thrown: the note stays saved and listed,
 * and the person still sees it sent. Log lines name the tenant, finding and user id, never an email address or the webhook URL.
 * The card puts the trusted fields first and the person's note last, flattened to one line with chat markup characters
 * replaced (neutral), so a note can neither fake a card line nor mention, link or format anything.
 *
 * Notes group by tenant + statementId + conclusionCode (null for an Unexplained finding, which groups by statement alone)
 * and are listed for audit.feedback.shownDays; rows are never deleted by that window. statementId, conclusionCode,
 * recordKey and headline are snapshots taken at send, so a note reads well after its finding changes or is purged.
 * Darpan answers by email only (DARPAN_FEEDBACK_ANSWER, admin side); an answer is never shown in the app.
 */
class FeedbackSupport {
    private static final Logger logger = LoggerFactory.getLogger(FeedbackSupport)
    private static final String WHY = "feedback on a finding: explicit companyUserGroupId condition applied"
    static final String ENTITY = "darpan.audit.AuditFindingFeedback"
    static final String WEBHOOK_SETTING = "feedback.chat.webhookUrl"
    static final String SHOWN_DAYS_SETTING = "audit.feedback.shownDays"
    static final String TEMPLATE_RECEIVED = "DARPAN_FEEDBACK_RECEIVED"
    static final String TEMPLATE_ANSWER = "DARPAN_FEEDBACK_ANSWER"
    /** Room under Google Chat's 4,096-character text limit for the card's other lines. */
    static final int CARD_NOTE_MAX = 2000
    private static final long DAY_MILLIS = 24L * 3600_000L

    /** Its own transaction, so SMTP time and a mail failure never sit inside the request's; the messageId, null when not sent. */
    private static final Closure DEFAULT_SEND_EMAIL = { def ec, String to, String templateId, Map body ->
        Map result = ec.service.sync().name("org.moqui.impl.EmailServices.send#EmailTemplate").requireNewTransaction(true)
                .parameters([emailTemplateId: templateId, toAddresses: to, bodyParameters: body]).call()
        return result?.messageId
    }

    /** Seam: replaces the outgoing chat post, called with (webhookUrl, payload) and returning the delivery map. Null in production. */
    static Closure postHook = null
    /** Seam: every feedback email goes through here, (ec, to, templateId, bodyParameters), so tests record sends. */
    static Closure sendEmail = DEFAULT_SEND_EMAIL

    /** Test-only: restores both seams to their production values. Idempotent. */
    static void resetForTests() {
        postHook = null
        sendEmail = DEFAULT_SEND_EMAIL
    }

    /**
     * Saves the note on the tenant's finding, then posts the card and thanks the sender. [feedbackId, note, sentAt, by: [id,
     * label]]; null when the finding is not the tenant's or the note is blank (the facade refuses both first).
     */
    static Map send(def ec, String tenantId, String findingId, String note) {
        String text = note?.trim()
        if (!text) return null
        def finding = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, tenantId, WHY)
        if (finding == null) return null
        String code = conclusionCode(ec, finding.conclusionEnumId as String)
        int shownDays = DarpanSettings.intValue(SHOWN_DAYS_SETTING)
        Timestamp now = ec.user.nowTimestamp
        String userId = ec.user.userId
        Map row = [companyUserGroupId: tenantId, findingId: findingId, statementId: finding.statementId, conclusionCode: code,
                   recordKey: finding.recordKey, headline: finding.headline, note: text, sentByUserId: userId, sentStamp: now]

        // Saved and counted in one new transaction: committed before anything is delivered, and the count sees this note
        // whatever the caller's isolation.
        Map saved = (Map) ec.transaction.runRequireNew(30, "Error saving feedback on finding ${findingId}".toString(), {
            def value = ec.entity.makeValue(ENTITY).setAll(row)
            value.setSequencedIdPrimary()
            value.create()
            return [feedbackId: value.feedbackId, count: groupFind(ec, tenantId, row.statementId as String, code, shownDays, now).count()]
        })

        Map byUser = userInfo(ec, userId)
        ZoneId zone = AuditDigestSupport.tenantZone(ec, tenantId)
        Map out = [feedbackId: saved.feedbackId, note: text, sentAt: AuditReportAssembler.isoDateTime(now, zone),
                   by: [id: userId, label: byUser.label]]
        Map facts = [tenantId: tenantId, findingId: findingId, row: row, count: saved.count as long, shownDays: shownDays,
                     user: byUser]
        postCard(ec, facts)
        thankSender(ec, facts)
        return out
    }

    /** The finding's group, newest first: [feedbackId, note, sentAt, by: [id, label], record]. */
    static List<Map> recent(def ec, String tenantId, String statementId, String conclusionCode, int shownDays) {
        if (!tenantId || !statementId) return []
        List rows = groupFind(ec, tenantId, statementId, conclusionCode, shownDays, ec.user.nowTimestamp)
                .orderBy(["-sentStamp", "-feedbackId"]).list()
        if (!rows) return []
        Map<String, String> labels = [:]
        List<String> userIds = rows*.sentByUserId.findAll().unique() as List<String>
        TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount", "feedback on a finding: who sent a note, by name")
                .condition("userId", EntityCondition.IN, userIds).useCache(false).list()
                .each { labels[it.userId as String] = (it.userFullName ?: it.username) as String }
        ZoneId zone = AuditDigestSupport.tenantZone(ec, tenantId)
        return rows.collect { r ->
            [feedbackId: r.feedbackId, note: r.note, sentAt: AuditReportAssembler.isoDateTime(r.sentStamp as Timestamp, zone),
             by: [id: r.sentByUserId, label: labels[r.sentByUserId as String]], record: r.recordKey] as Map
        }
    }

    /** How many notes the finding's group holds in the window (the finding document's feedback.recentCount). */
    static int recentCount(def ec, String tenantId, String statementId, String conclusionCode, int shownDays) {
        if (!tenantId || !statementId) return 0
        return groupFind(ec, tenantId, statementId, conclusionCode, shownDays, ec.user.nowTimestamp).count() as int
    }

    /** The recent notes of the finding's group; empty when the finding is not the tenant's. */
    static List<Map> recentForFinding(def ec, String tenantId, String findingId, int shownDays) {
        def finding = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, tenantId, WHY)
        if (finding == null) return []
        return recent(ec, tenantId, finding.statementId as String, conclusionCode(ec, finding.conclusionEnumId as String), shownDays)
    }

    /** The finding's diagnosis code, as the finding document's diagnosis.conclusion.code; null for an Unexplained finding. */
    static String conclusionCode(def ec, String conclusionEnumId) {
        if (!conclusionEnumId) return null
        def e = ec.entity.find("moqui.basic.Enumeration").condition("enumId", conclusionEnumId).useCache(true).one()
        return (e?.enumCode ?: conclusionEnumId) as String
    }

    /** One tenant's group (statement + cause; a null cause matches only Unexplained notes) sent within the window. */
    private static def groupFind(def ec, String tenantId, String statementId, String conclusionCode, int shownDays, Timestamp now) {
        Timestamp since = new Timestamp(now.time - shownDays * DAY_MILLIS)
        return TenantScopedFinder.findGlobalUnscoped(ec, ENTITY, WHY).condition("companyUserGroupId", tenantId)
                .condition("statementId", statementId).condition("conclusionCode", conclusionCode)
                .condition("sentStamp", EntityCondition.GREATER_THAN_EQUAL_TO, since).useCache(false)
    }

    // ---------------------------------------------------------------------------------------------------------
    // After the save: the card and the thank-you. Each logs its own failure and never throws.
    // ---------------------------------------------------------------------------------------------------------

    private static void postCard(def ec, Map facts) {
        String webhookUrl = null
        try {
            webhookUrl = DarpanSettings.stringValue(WEBHOOK_SETTING)
            if (!webhookUrl) return
            Map payload = [text: cardText(cardFacts(ec, facts))]
            Map delivery = isolated(ec, "card", facts) {
                (postHook != null ? postHook.call(webhookUrl, payload) : TenantNotificationSupport.deliverToWebhook(webhookUrl, payload)) as Map
            } as Map
            if (delivery?.ok != true) {
                logger.error("Feedback card for {} {} was not delivered (status {})", facts.tenantId, facts.findingId, delivery?.statusCode)
            }
        } catch (Throwable t) {
            logger.error("Feedback card for {} {} failed: {}", facts?.tenantId, facts?.findingId, failureNote(t, webhookUrl))
        }
    }

    private static void thankSender(def ec, Map facts) {
        try {
            Map user = facts.user as Map
            if (!user.email) {
                logger.info("Feedback {} on {}: the sender {} has no email address, so no thank-you", facts.findingId, facts.tenantId, user.id)
                return
            }
            Map row = facts.row as Map
            Map statement = statementOf(ec, facts.tenantId as String, row.statementId as String)
            Map body = [firstName: user.firstName, note: row.note, statementCode: statement?.statementCode ?: "",
                        recordKey: row.recordKey ?: "", headline: row.headline ?: "", shownDays: facts.shownDays]
            Object messageId = isolated(ec, "thank-you", facts) { sendEmail.call(ec, user.email, TEMPLATE_RECEIVED, body) }
            if (!messageId) logger.error("Feedback thank-you for {} {} to user {} was not sent", facts.tenantId, facts.findingId, user.id)
        } catch (Throwable t) {
            logger.error("Feedback thank-you for {} {} to user {} failed: {}", facts?.tenantId, facts?.findingId,
                    (facts?.user as Map)?.id, t.getClass().name)
        }
    }

    /**
     * Darpan's answer to one note, by email to its sender (DARPAN_FEEDBACK_ANSWER: their note quoted, then the answer). The
     * messageId, or null when it was not sent (no address on the sender, or the mail failed; logged at ERROR with the user id,
     * never the address). The caller saves the answer only on a messageId, so a failed send leaves nothing to retry around.
     */
    static Object sendAnswer(def ec, def feedback, String answer) {
        Map facts = [tenantId: feedback.companyUserGroupId, findingId: feedback.findingId, user: [id: feedback.sentByUserId]]
        try {
            Map user = userInfo(ec, feedback.sentByUserId as String)
            if (!user.email) {
                logger.error("Feedback answer {} for {}: the sender {} has no email address", feedback.feedbackId, feedback.companyUserGroupId, user.id)
                return null
            }
            Map statement = statementOf(ec, feedback.companyUserGroupId as String, feedback.statementId as String)
            Map body = [firstName: user.firstName, note: feedback.note, statementCode: statement?.statementCode ?: "",
                        recordKey: feedback.recordKey ?: "", headline: feedback.headline ?: "", answer: answer]
            Object messageId = isolated(ec, "answer", facts) { sendEmail.call(ec, user.email, TEMPLATE_ANSWER, body) }
            if (!messageId) logger.error("Feedback answer {} for {} to user {} was not sent", feedback.feedbackId, feedback.companyUserGroupId, user.id)
            return messageId
        } catch (Throwable t) {
            logger.error("Feedback answer {} for {} to user {} failed: {}", feedback.feedbackId, feedback.companyUserGroupId,
                    feedback.sentByUserId, t.getClass().name)
            return null
        }
    }

    /** What the card names, read after the save: tenant, statement, cause label, audit code, the app's link. */
    private static Map cardFacts(def ec, Map facts) {
        String tenantId = facts.tenantId as String
        Map row = facts.row as Map
        Map statement = statementOf(ec, tenantId, row.statementId as String)
        def audit = statement?.auditId ? TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId",
                statement.auditId, tenantId, WHY) : null
        def group = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup", WHY)
                .condition("userGroupId", tenantId).useCache(false).one()
        def finding = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", facts.findingId, tenantId, WHY)
        def cause = finding?.conclusionEnumId ? ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", finding.conclusionEnumId).useCache(true).one() : null
        return [tenantName: group?.description ?: tenantId, from: (facts.user as Map).label,
                statementCode: statement?.statementCode, statementText: statement?.statementText,
                cause: row.conclusionCode ? (cause?.description ?: row.conclusionCode) : null, recordKey: row.recordKey,
                note: row.note, count: facts.count, shownDays: facts.shownDays,
                link: findingUrl(TenantNotificationSupport.resolveAppBaseUrl(ec), audit?.auditCode as String,
                        facts.findingId as String, tenantId)]
    }

    /** Trusted lines first, then the link, then the note: one quoted line, last, so it cannot pose as another line. */
    static String cardText(Map c) {
        String note = neutral(c.note)
        if (note.length() > CARD_NOTE_MAX) note = note.substring(0, CARD_NOTE_MAX) + "\u2026"
        long n = (c.count ?: 1) as long
        List<String> lines = ["Feedback from ${neutral(c.tenantName)}".toString(),
                              "From: ${neutral(c.from)}".toString(),
                              "Statement: ${neutral([c.statementCode, c.statementText].findAll().join(' '))}".toString(),
                              "Cause: ${c.cause ? neutral(c.cause) : 'Unexplained'}".toString(),
                              "Record: ${neutral(c.recordKey)}".toString(),
                              "${n} ${n == 1 ? 'note' : 'notes'} on this statement + cause in ${c.shownDays} days".toString()]
        if (c.link) lines << (c.link as String)
        lines << "Note: \"${note}\"".toString()
        return lines.join("\n")
    }

    /**
     * A value as plain chat text on one line: line breaks (and other control characters) collapse to one space, and the
     * characters Google Chat and Slack read as markup for mentions and links (<users/all>, <!channel>, <https://x|text>,
     * &lt; entities) become look-alikes, so nothing in a value can mention, link or start a line of its own.
     */
    static String neutral(Object value) {
        String text = (value ?: "").toString()
        // Escapes, not literals (Groovy reads source in the platform encoding): single angle quotes, broken bar, fullwidth ampersand.
        return text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replace("<", "\u2039").replace(">", "\u203A")
                .replace("|", "\u00A6").replace("&", "\uFF06").replaceAll(" {2,}", " ").trim()
    }

    /**
     * The finding in the app, on the audit route the run email links (AuditRunMessage.reportUrl): base/audits/code?finding=id,
     * naming its tenant last so the app switches into it (DAR-BE-293). With no app address configured, the path alone.
     */
    static String findingUrl(String base, String auditCode, String findingId, String tenantId) {
        if (!auditCode || !findingId) return null
        String url = "${base ?: ''}/audits/${enc(auditCode)}?finding=${enc(findingId)}"
        return (tenantId ? "${url}&tenantId=${enc(tenantId)}" : url).toString()
    }

    private static String enc(String s) { return URLEncoder.encode(s, "UTF-8") }

    private static Map statementOf(def ec, String tenantId, String statementId) {
        return TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId", statementId, tenantId, WHY)?.getMap()
    }

    /** The sender: id, label (full name, else username), first name (the label's first word) and email address. */
    private static Map userInfo(def ec, String userId) {
        def account = userId ? TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount", "feedback on a finding: the sender")
                .condition("userId", userId).useCache(false).one() : null
        String label = (account?.userFullName ?: account?.username ?: userId) as String
        return [id: userId, label: label, firstName: label?.trim()?.split(/\s+/)?.getAt(0) ?: "there",
                email: (account?.emailAddress as String)?.trim() ?: null]
    }

    /**
     * Runs a delivery behind a fresh error scope: an error it adds (send#EmailTemplate reports SMTP trouble as one) is logged and
     * dropped, never left for the caller, so the facade still answers ok; errors the caller already held come back. The
     * error text is not logged: it can carry the recipient's address.
     */
    private static Object isolated(def ec, String what, Map facts, Closure work) {
        ec.message.pushErrors()
        try {
            Object result = work.call()
            if (ec.message.hasError()) {
                logger.error("Feedback {} for {} {} to user {} reported {} error(s)", what, facts?.tenantId, facts?.findingId,
                        (facts?.user as Map)?.id, ec.message.errors.size())
                return null
            }
            return result
        } finally {
            ec.message.clearErrors()
            ec.message.popErrors()
        }
    }

    /** The exception class and the webhook's host: never the exception message (it can carry the URL's key) nor the URL. */
    private static String failureNote(Throwable t, String webhookUrl) {
        String host = null
        try { host = new URI(webhookUrl?.trim()).host } catch (Exception ignored) { }
        return "${t.getClass().name} (webhook host ${host ?: 'unreadable'})".toString()
    }
}
