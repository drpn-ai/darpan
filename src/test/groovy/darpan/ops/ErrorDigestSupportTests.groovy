package darpan.ops

import darpan.config.DarpanSettings
import darpan.email.EmailCopyReader
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-015 (spec F). The daily error email through its seams: no Moqui, no database. The claim store is a map, the
 * cross-tenant read returns what each test sets, and the email and the chat post are recorded.
 */
class ErrorDigestSupportTests {
    static final Timestamp AT_THE_HOUR = ts("2026-11-25T12:05:00Z")
    static final Timestamp AN_HOUR_EARLY = ts("2026-11-25T11:05:00Z")
    static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/OPS/messages?key=k&token=t"

    private Map<LocalDate, Map> claims = [:]
    private List<Map> finished = []
    private List<Map> loads = []
    private List<Map> mails = []
    private List<Map> posts = []
    private Map settings = [:]
    private Map input = [:]
    private boolean mailWorks = true

    private static Timestamp ts(String instant) { Timestamp.from(Instant.parse(instant)) }

    @BeforeEach
    void seams() {
        settings = ["errors.email.to": "ops@drpn.test, oncall@drpn.test", "errors.chat.webhookUrl": WEBHOOK]
        input = [instance: "prod", executions: [], checks: [], logRows: [], tenantNames: [:], automationNames: [:]]
        DarpanSettings.rowLoader = { -> settings.collectEntries { k, v -> [(k): [settingValue: v]] } as Map<String, Map> }
        DarpanSettings.expire()
        ErrorDigestSupport.claim = { def ec, LocalDate day, Timestamp now ->
            if (claims.containsKey(day)) return [isNew: false]
            Timestamp previous = claims.values().findAll { it.sentDate != null }*.sentDate.max()
            claims[day] = [sentDate: now]
            return [isNew: true, previousSentDate: previous]
        }
        ErrorDigestSupport.finish = { def ec, LocalDate day, Map fields ->
            finished << ([day: day] + fields)
            if (!fields.delivered) claims[day].sentDate = null
        }
        ErrorDigestSupport.loadInput = { def ec, Timestamp since, Timestamp now, int watchdogHours, int emptyStreakDays ->
            loads << [since: since, now: now, watchdogHours: watchdogHours, emptyStreakDays: emptyStreakDays]
            return input
        }
        ErrorDigestSupport.sendEmail = { def ec, String to, Map body -> mails << [to: to, body: body]; return mailWorks }
        ErrorDigestSupport.postChat = { String url, Map payload -> posts << [url: url, text: payload.text]; return [ok: true] }
        ErrorDigestSupport.copy = { def ec -> shippedCopy() }
    }

    @AfterEach
    void reset() {
        ErrorDigestSupport.resetForTests()
        DarpanSettings.resetForTests()
    }

    /** The words as data/OpsSeedData.xml ships them. */
    static EmailCopyReader shippedCopy() {
        Map<String, String> seed = [:]
        new XmlSlurper().parse(new File("data/OpsSeedData.xml")).children().each { node ->
            if (node.name() == "darpan.email.EmailCopyBlock") seed.put("${node.@emailTemplateId}/${node.@blockId}".toString(), node.@defaultText.text())
        }
        return EmailCopyReader.ofDefaults(ErrorDigestSupport.TEMPLATE, seed)
    }

    private static Map failedRun(Map over = [:]) {
        return [statusEnumId: "AUT_STAT_DEAD_LETTER", automationId: "A1", companyUserGroupId: "DOLLSKILL", triggerType: "scheduled",
                scheduledDate: ts("2026-11-25T09:00:00Z"), completedDate: ts("2026-11-25T09:20:00Z"),
                errorMessage: "Keep runs on the extract's records array <and this side's key paths do not read it>",
                file1ReadCount: 6982, file2ReadCount: 29963, file1RecordCount: 5525, file2RecordCount: 0,
                file1Name: "oms-order-lines.json", file2Name: "netsuite-invoices.json", runUrl: "https://app.drpn.ai/run/1"] + over
    }

    private static Map check(Map over = [:]) {
        return [tenantId: "DOLLSKILL", auditName: "Month close", statementId: "R1", checkId: "completed-not-billed",
                auditScheduleActive: true, statementActive: true, automationId: "A1", automationActive: true,
                lastSuccessDate: ts("2026-11-25T06:00:00Z"), recentScheduledReads: []] + over
    }

    @Test
    void notTheHourSendsNothing() {
        Map out = ErrorDigestSupport.sendIfDue(null, AN_HOUR_EARLY)
        assertEquals([sent: false, reason: "NOT_THE_HOUR"], out.subMap(["sent", "reason"]))
        assertTrue(claims.isEmpty() && loads.isEmpty() && mails.isEmpty() && posts.isEmpty())
    }

    @Test
    void theHourSendsOnceAndClaims() {
        input.executions = [failedRun()]
        input.checks = [check()]
        input.logRows = [[fingerprint: "darpan.x Boom #", count: 37, loggerName: "darpan.x"]]
        Map out = ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)

        assertTrue(out.sent as boolean)
        assertNull(out.reason)
        assertEquals([LocalDate.parse("2026-11-25")], claims.keySet() as List)
        assertEquals(1, mails.size())
        assertEquals("ops@drpn.test, oncall@drpn.test", mails[0].to)
        assertEquals("[prod] Darpan errors 2026-11-25: 1 checks, 37 log errors", mails[0].body.digestSubject)
        assertEquals(["DEAD_LETTER"], mails[0].body.digestBody.runErrors*.kind)
        assertEquals([[url: WEBHOOK, text: "Darpan errors today: 1 checks, 37 log errors. Email sent to ops@drpn.test, oncall@drpn.test."]], posts)
        assertEquals([[day: LocalDate.parse("2026-11-25"), delivered: true, checksCount: 1, logErrorsCount: 37]], finished)
        assertEquals(30, loads[0].watchdogHours)
        assertEquals(7, loads[0].emptyStreakDays)
    }

    @Test
    void aSecondSendInTheSameDayFindsTheClaimAndSendsNothing() {
        assertTrue(ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR).sent as boolean)
        Map again = ErrorDigestSupport.sendIfDue(null, ts("2026-11-25T12:35:00Z"))
        assertEquals([sent: false, reason: "ALREADY_SENT"], again.subMap(["sent", "reason"]))
        assertEquals(1, mails.size())
        assertEquals(1, posts.size())
        assertEquals(1, loads.size(), "the second caller reads nothing")
    }

    @Test
    void aBlankEmailAddressSendsNothingAndWarnsOnce() {
        settings["errors.email.to"] = "  "
        DarpanSettings.expire()
        Map out = ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)

        assertFalse(out.sent as boolean)
        assertEquals("NO_DESTINATION", out.reason)
        assertTrue(mails.isEmpty())
        assertEquals(1, (out.warnings as List).size(), "one warning: ${out.warnings}")
        assertEquals([[url: WEBHOOK, text: "Darpan errors today: 0 checks, 0 log errors. No email was sent: no address is set."]], posts)
        assertFalse(finished[0].delivered as boolean, "a day with no email does not move the next digest's start")
    }

    @Test
    void aBlankWebhookStillSendsTheEmailAndWarnsOnce() {
        settings.remove("errors.chat.webhookUrl")
        DarpanSettings.expire()
        Map out = ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)

        assertTrue(out.sent as boolean, "${out}")
        assertEquals(1, mails.size())
        assertTrue(posts.isEmpty())
        List warnings = out.warnings as List
        assertEquals(1, warnings.size(), "one warning: ${warnings}")
        assertTrue(warnings[0].toString().contains("errors.chat.webhookUrl"), "${warnings}")
    }

    @Test
    void noDestinationAtAllClaimsNothing() {
        settings = [:]
        DarpanSettings.expire()
        Map out = ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        assertEquals("NO_DESTINATION", out.reason)
        assertEquals(1, (out.warnings as List).size())
        assertTrue(claims.isEmpty() && mails.isEmpty() && posts.isEmpty())
    }

    @Test
    void anEmptyDaySendsNone() {
        Map out = ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        assertTrue(out.sent as boolean)
        assertEquals("[prod] Darpan errors 2026-11-25: none", mails[0].body.digestSubject)
        assertEquals("Darpan errors today: 0 checks, 0 log errors. Email sent to ops@drpn.test, oncall@drpn.test.", posts[0].text)
    }

    @Test
    void aFailedEmailSaysSoInChatAndKeepsTheWindowOpen() {
        mailWorks = false
        Map out = ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        assertEquals([sent: false, reason: "DELIVERY_FAILED"], out.subMap(["sent", "reason"]))
        assertEquals("Darpan errors today: 0 checks, 0 log errors. The email to ops@drpn.test, oncall@drpn.test could not be sent.", posts[0].text)
        assertNull(claims[LocalDate.parse("2026-11-25")].sentDate, "the next digest covers this day again")
    }

    @Test
    void sinceIsThePreviousSend() {
        ErrorDigestSupport.sendIfDue(null, ts("2026-11-24T12:05:00Z"))
        assertEquals(ts("2026-11-23T12:05:00Z"), loads[0].since, "the first digest covers the last 24 hours")
        ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        assertEquals(ts("2026-11-24T12:05:00Z"), loads[1].since, "the next one starts where the last sent one ended")
        assertEquals(AT_THE_HOUR, loads[1].now)
    }

    @Test
    void aWindowNeverReachesBackMoreThanThirtyDays() {
        claims[LocalDate.parse("2026-09-01")] = [sentDate: ts("2026-09-01T12:05:00Z")]
        ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        assertEquals(ts("2026-10-26T12:05:00Z"), loads[0].since)
    }

    @Test
    void theEmailListsRunErrorsThenTheWatchdogThenLogsThenTheFooterAndEscapesEveryValue() {
        input.executions = [failedRun()]
        input.checks = [check(), check(checkId: "returns-vs-memos", automationId: null, statementId: "R4")]
        input.logRows = [[fingerprint: "darpan.x Boom #", count: 2, loggerName: "darpan.x", sampleMessage: "Boom 4",
                          sampleStack: "java.lang.IllegalStateException: Boom 4\n\tat darpan.x(X.groovy:1)",
                          firstSeen: ts("2026-11-25T01:00:00Z"), lastSeen: ts("2026-11-25T02:00:00Z")]]
        input.tenantNames = [DOLLSKILL: "Dolls Kill"]
        ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        Map body = mails[0].body.digestBody as Map
        EmailCopyReader copy = shippedCopy()

        String html = ErrorDigestMessage.emailHtml(body, copy)
        List<Integer> order = ["Run errors", "Watchdog", "Log errors", "Instance prod."].collect { html.indexOf(it) }
        assertTrue(order.every { it > 0 } && order == order.sort(false), "sections in order: ${order}\n${html}")
        assertTrue(html.contains("Dolls Kill") && html.contains("completed-not-billed") && html.contains("DEAD_LETTER"), html)
        assertTrue(html.contains("&lt;and this side&#39;s key paths do not read it&gt;") || html.contains("&lt;and this side's key paths do not read it&gt;"), html)
        assertFalse(html.contains("<and this side"), "error text is escaped")
        assertTrue(html.contains("oms-order-lines.json") && html.contains("https://app.drpn.ai/run/1"), html)
        assertTrue(html.contains("<details>"), "the stack is collapsed")
        assertFalse((html =~ /href="https:\/\/app\./).find(), "the run address is text, not a link")

        String text = ErrorDigestMessage.emailText(body, copy)
        List<Integer> textOrder = ["Run errors", "Watchdog", "Log errors", "Instance prod."].collect { text.indexOf(it) }
        assertTrue(textOrder.every { it >= 0 } && textOrder == textOrder.sort(false), text)
        assertTrue(text.contains("Keep runs on the extract's records array <and this side's key paths do not read it>"), text)
        assertTrue(text.contains("NO_AUTOMATION") && text.contains("returns-vs-memos"), text)
    }

    /** A log row's count is its UTC day's whole count (one row per fingerprint per day), so each says which day and "so far". */
    @Test
    void aLogRowNamesItsUtcDayAndSaysSoFar() {
        input.logRows = [[fingerprint: "darpan.x Boom #", count: 5, logDay: java.sql.Date.valueOf("2026-11-24"),
                          firstSeen: ts("2026-11-24T01:00:00Z"), lastSeen: ts("2026-11-24T23:00:00Z")],
                         [fingerprint: "darpan.x Boom #", count: 2, logDay: java.sql.Date.valueOf("2026-11-25"),
                          firstSeen: ts("2026-11-25T01:00:00Z"), lastSeen: ts("2026-11-25T02:00:00Z")]]
        ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        Map body = mails[0].body.digestBody as Map
        assertEquals(2, (body.logErrors as List).size(), "a fingerprint on two UTC days stays two rows")
        String text = ErrorDigestMessage.emailText(body, shippedCopy())
        assertTrue(text.contains("5 times on 2026-11-24 (UTC) so far") && text.contains("2 times on 2026-11-25 (UTC) so far"), text)
    }

    // DAR-BE-396: the shipped errors.email.ignorePatterns leave out the 2026-11-27 email's deliberate refusals, and the
    // email says how many lines it left out; a setting row replaces the shipped list.
    @Test
    void theShippedIgnoreListLeavesOutDeliberateRefusalsAndTheEmailSaysHowMany() {
        Closure row = { String logger, String message, int n -> [fingerprint: "${logger} ${message}".toString(), loggerName: logger, sampleMessage: message, count: n] }
        input.logRows = [
                row("org.moqui.impl.actions.XmlAction", "Error running groovy script (org.moqui.impl.webapp.ScreenResourceNotFoundException: Could not find subscreen or transition or file/content [Login] under screen [null] while finding url", 91),
                row("org.moqui.impl.context.MessageFacadeImpl", "Password incorrect for username someone", 1),
                row("org.moqui.impl.context.MessageFacadeImpl", "Invalid username or password", 1),
                row("org.moqui.impl.service.ServiceJsonRpcDispatcher", "Error calling service facade.SyncPulseFacadeServices.get#SyncPair from JSON-RPC request: org.moqui.context.AuthenticationRequiredException: User must be logged in to call service facade.SyncPulseFacadeServices.get#SyncPair", 3),
                row("org.moqui.impl.webapp.ElasticRequestLogFilter", "In ElasticRequestLogFilter init could not find ElasticClient with name logger or default, not starting", 9),
                row("org.moqui.impl.context.MessageFacadeImpl", "'first: 250' on 'orders' exceeds maxFirst 100", 2),
                row("org.moqui.impl.context.MessageFacadeImpl", "search term 'OR' must be key:value", 1),
                row("org.moqui.impl.context.MessageFacadeImpl", "Darpan reads Shopify customer data at Level 1 only, so a search on ordersCount by \"name:EXC*\" was not sent", 1),
                row("org.moqui.impl.service.ServiceCallSyncImpl", "Error running service create#moqui.security.UserLoginHistory", 2),
                row("org.moqui.impl.context.MessageFacadeImpl", "Duplicate entry '100000-2026-11-27 05:07:03.653' for key 'USER_LOGIN_HISTORY.PRIMARY'", 2),
                // Kept: these may be Darpan's own bug, not a refusal.
                row("org.moqui.impl.context.MessageFacadeImpl", "Sync pair '100102' was not found.", 652),
                row("org.moqui.impl.context.MessageFacadeImpl", "[serviceName:facade.ReconciliationFacadeServices.get#Automation, message:Field cannot be empty]", 4),
                row("org.moqui.impl.context.MessageFacadeImpl", "Shopify auth config '100005 Outlet' was not found.", 1)]
        ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        Map body = mails[0].body.digestBody as Map
        assertEquals(["Sync pair '100102' was not found.", "[serviceName:facade.ReconciliationFacadeServices.get#Automation, message:Field cannot be empty]",
                      "Shopify auth config '100005 Outlet' was not found."], (body.logErrors as List<Map>)*.sampleMessage)
        assertEquals(113, body.counts.ignoredLogErrors)
        assertEquals("[prod] Darpan errors 2026-11-25: 0 checks, 657 log errors", mails[0].body.digestSubject)
        String text = ErrorDigestMessage.emailText(body, shippedCopy())
        assertTrue(text.contains("Left out 113 log lines that match the ignore patterns (Errors settings)."), text)
        assertTrue(ErrorDigestMessage.emailHtml(body, shippedCopy()).contains("Left out 113 log lines that match the ignore patterns (Errors settings)."))

        mails.clear(); claims.clear()
        settings["errors.email.ignorePatterns"] = "Sync pair '\\d+' was not found"
        DarpanSettings.expire()
        ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        Map edited = mails[0].body.digestBody as Map
        assertEquals(652, edited.counts.ignoredLogErrors, "a setting row replaces the shipped list")
        assertTrue((edited.logErrors as List<Map>)*.sampleMessage.any { it.toString().contains("[Login]") })
    }

    @Test
    void nothingLeftOutSaysNothingAboutIt() {
        input.logRows = [[fingerprint: "darpan.x Boom #", count: 2, loggerName: "darpan.x", sampleMessage: "Boom 4"]]
        ErrorDigestSupport.sendIfDue(null, AT_THE_HOUR)
        String text = ErrorDigestMessage.emailText(mails[0].body.digestBody as Map, shippedCopy())
        assertFalse(text.contains("Left out"), text)
    }

    // ---------------------------------------------------------------- the real send's outcome (DEFAULT_SEND_EMAIL)

    /** Moqui's MessageFacade, as far as the send and its message scope use it. */
    static class StubMessages {
        List<Map> infos = [], publics = [], errors = []
        List<List<Map>> saved = []
        List<Map> getMessageInfos() { infos }
        List<Map> getPublicMessageInfos() { publics }
        void addMessage(String m, Object type = "info") { infos << [message: m, type: type] }
        void addPublic(String m, Object type = "info") { Map mi = [message: m, type: type]; infos << mi; publics << mi }
        void addError(String m) { errors << [message: m] }
        boolean hasError() { !errors.isEmpty() }
        String getErrorsString() { errors*.message.join("\n") }
        void clearErrors() { errors.each { infos << [message: it.message, type: "danger"] }; errors.clear() }
        void clearAll() { errors.clear(); infos.clear(); publics.clear() }
        void pushErrors() { saved.add(0, new ArrayList(errors)); errors.clear() }
        void popErrors() { if (saved) errors.addAll(saved.remove(0)) }
    }

    /** An ec whose send#EmailTemplate call does what `onSend` does to the messages and returns its result. */
    private static Map stubEc(Closure<Map> onSend) {
        StubMessages messages = new StubMessages()
        messages.addPublic("Saved the run", "info")
        Map call = [:]
        call.name = { String n -> call }
        call.parameters = { Map p -> call.params = p; call }
        call.call = { -> onSend.call(messages) }
        return [message: messages, service: [sync: { -> call }], entity: null, call: call]
    }

    @Test
    void aMailServerFailureIsNotSentAndChatSaysSo() {
        ErrorDigestSupport.sendEmail = ErrorDigestSupport.DEFAULT_SEND_EMAIL
        Map ec = stubEc { StubMessages m -> m.addMessage("Error sending email: javax.mail.MessagingException: Could not connect"); [:] }
        Map out = ErrorDigestSupport.sendIfDue(ec, AT_THE_HOUR)

        assertEquals([sent: false, reason: "DELIVERY_FAILED"], out.subMap(["sent", "reason"]))
        assertNull(claims[LocalDate.parse("2026-11-25")].sentDate, "the next digest covers this day again")
        assertEquals("Darpan errors today: 0 checks, 0 log errors. The email to ops@drpn.test, oncall@drpn.test could not be sent.", posts[0].text)
        assertTrue((out.warnings as List).any { it.contains("Error sending email") }, "the warning says why: ${out.warnings}")
        assertEquals([[message: "Saved the run", type: "info"]], ec.message.infos, "the caller's messages come back unchanged")
        assertEquals(ec.message.infos, ec.message.publics)
        assertFalse(ec.message.hasError())
        assertEquals("DARPAN_ERROR_DIGEST", ec.call.params.emailTemplateId)
    }

    @Test
    void aSendWithNoMessageIdIsNotSent() {
        // no SMTP host, or every address filtered by the allowed domains: Moqui returns quietly, with no message id
        ErrorDigestSupport.sendEmail = ErrorDigestSupport.DEFAULT_SEND_EMAIL
        Map ec = stubEc { StubMessages m -> [:] }
        Map out = ErrorDigestSupport.sendIfDue(ec, AT_THE_HOUR)
        assertEquals([sent: false, reason: "DELIVERY_FAILED"], out.subMap(["sent", "reason"]))
        assertTrue(posts[0].text.contains("could not be sent"), posts[0].text)
    }

    @Test
    void aMessageIdIsASendEvenWhenAnErrorFollowsIt() {
        // the server took the message (messageId set), then recording the EmailMessage row failed: that is still a send
        ErrorDigestSupport.sendEmail = ErrorDigestSupport.DEFAULT_SEND_EMAIL
        Map ec = stubEc { StubMessages m -> m.addError("Could not update EmailMessage EM_1"); [messageId: "<abc@mail.drpn.test>"] }
        Map out = ErrorDigestSupport.sendIfDue(ec, AT_THE_HOUR)
        assertTrue(out.sent as boolean, "${out}")
        assertNull(out.reason)
        assertTrue(posts[0].text.contains("Email sent to"), posts[0].text)
        assertFalse(ec.message.hasError(), "the send's own error does not leak to the caller")
    }

    @Test
    void aSendWithAMessageIdIsSent() {
        ErrorDigestSupport.sendEmail = ErrorDigestSupport.DEFAULT_SEND_EMAIL
        Map ec = stubEc { StubMessages m -> [messageId: "<abc@mail.drpn.test>"] }
        Map out = ErrorDigestSupport.sendIfDue(ec, AT_THE_HOUR)
        assertTrue(out.sent as boolean, "${out}")
        assertTrue(posts[0].text.contains("Email sent to"), posts[0].text)
        assertEquals([[message: "Saved the run", type: "info"]], ec.message.infos)
    }
}
