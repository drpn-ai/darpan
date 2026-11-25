package darpan.ops

import darpan.config.DarpanSettings
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date as SqlDate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorChatSupportTests {
    private static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/AAAA1234ZZZZ/messages?key=k&token=t"
    private ExecutionContext ec
    private List<Map> posted = []
    private int seq = 0
    private String webhook = WEBHOOK

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "error-chat-support")
    }

    @BeforeEach
    void hooks() {
        webhook = WEBHOOK
        DarpanSettings.rowLoader = { -> webhook ? [("errors.chat.webhookUrl".toString()): [settingValue: webhook]] : [:] }
        DarpanSettings.expire()
        ErrorChatSupport.postHook = { String url, Map payload -> posted << [url: url, text: payload.text]; return [ok: true] }
    }

    @AfterEach
    void clear() {
        posted.clear()
        ec.message.clearErrors()
        ErrorChatSupport.resetForTests()
        DarpanSettings.resetForTests()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    private Map event(Map over = [:]) {
        return [tenantId: "OPS_T${++seq}".toString(), tenantName: "Dolls Kill", auditName: "Month close", statementId: "R1",
                auditId: "AUD1", checkId: "completed-not-billed", automationId: "AUTO1", automationName: "R1 nightly", kind: "DEAD_LETTER", attempts: 3,
                errorFirstLine: "Connection reset", readCounts: ["HotWax": 6982, "NetSuite": 29963],
                keptCounts: ["HotWax": 5525, "NetSuite": 0], runUrl: "https://app.test/run/1", scheduled: true, trigger: "scheduled",
                scheduledDate: Timestamp.from(Instant.parse("2026-11-25T15:00:00Z")), zoneId: "America/Los_Angeles"] + over
    }

    @Test
    void theCardNamesTenantStatementCheckAndCounts() {
        assertTrue(ErrorChatSupport.postErrorCard(ec, event()))
        String text = posted[0].text
        assertEquals(WEBHOOK, posted[0].url)
        assertTrue(text.contains("Dolls Kill · Month close R1 · completed-not-billed"), text)
        assertTrue(text.contains("DEAD_LETTER after 3 tries"), text)
        assertTrue(text.contains("Read: HotWax 6,982 · NetSuite 29,963 → kept HotWax 5,525 · NetSuite 0"), text)
        assertTrue(text.contains("https://app.test/run/1"), text)
        assertFalse(text.contains("day in a row"), text)
    }

    @Test
    void aSecondFailureSameDayPostsNothing() {
        Map e = event()
        assertTrue(ErrorChatSupport.postErrorCard(ec, e))
        assertFalse(ErrorChatSupport.postErrorCard(ec, e + [kind: "FAILED"]))
        assertEquals(1, posted.size())
    }

    @Test
    void aStreakSaysTheDayCount() {
        Map e = event()
        [LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 23)].each { LocalDate d ->
            ec.entity.makeValue("darpan.ops.RunErrorNotice").setAll([tenantId: e.tenantId, subjectKey: "AUD1:${e.checkId}".toString(),
                    runDay: SqlDate.valueOf(d), triggerType: "scheduled", errorKind: "FAILED"]).create()
        }
        assertTrue(ErrorChatSupport.postErrorCard(ec, e))
        assertTrue(posted[0].text.contains("3rd day in a row"), posted[0].text)
    }

    @Test
    void theDayIsTheScheduledDateInTheAutomationsZone() {
        Map e = event()
        // 23:50 LA on the 25th and 00:10 LA on the 26th: two days
        assertTrue(ErrorChatSupport.postErrorCard(ec, e + [scheduledDate: Timestamp.from(Instant.parse("2026-11-26T07:50:00Z"))]))
        assertTrue(ErrorChatSupport.postErrorCard(ec, e + [scheduledDate: Timestamp.from(Instant.parse("2026-11-26T08:10:00Z"))]))
        assertEquals(2, posted.size())
        assertTrue(posted[1].text.contains("2nd day in a row"), posted[1].text)
    }

    @Test
    void aBlankWebhookPostsNothing() {
        webhook = null
        DarpanSettings.expire()
        assertFalse(ErrorChatSupport.postErrorCard(ec, event()))
        assertTrue(posted.isEmpty())
    }

    @Test
    void aWebhookThatFailsNeverFailsTheRun() {
        ErrorChatSupport.postHook = { String url, Map payload -> throw new IOException("boom") }
        assertFalse(ErrorChatSupport.postErrorCard(ec, event()))
    }

    @Test
    void aManualRunSaysManual() {
        assertTrue(ErrorChatSupport.postErrorCard(ec, event([scheduled: false, trigger: "manual", kind: "FAILED", attempts: null])))
        assertTrue(posted[0].text.contains("FAILED · manual"), posted[0].text)
    }

    @Test
    void aManualCardDoesNotTakeTheScheduledSlot() {
        Map e = event()
        assertTrue(ErrorChatSupport.postErrorCard(ec, e + [scheduled: false, trigger: "manual"]))
        assertTrue(ErrorChatSupport.postErrorCard(ec, e))
        assertEquals(2, posted.size())
    }

    @Test
    void aManualDayDoesNotMakeAStreak() {
        Map e = event()
        ec.entity.makeValue("darpan.ops.RunErrorNotice").setAll([tenantId: e.tenantId, subjectKey: "AUD1:${e.checkId}".toString(),
                runDay: SqlDate.valueOf(LocalDate.of(2026, 11, 24)), triggerType: "manual", errorKind: "FAILED"]).create()
        assertTrue(ErrorChatSupport.postErrorCard(ec, e))
        assertFalse(posted[0].text.contains("day in a row"), posted[0].text)
    }

    @Test
    void anErrorAlreadyInTheCallersMessagesDoesNotBlockTheCard() {
        ec.message.addError("the run already failed")
        assertTrue(ErrorChatSupport.postErrorCard(ec, event()))
        assertTrue(ec.message.hasError(), "the caller's own error is still there")
        assertEquals(1, ec.message.errors.size())
    }

    @Test
    void aClaimThatFailsLeavesNoErrorAndDoesNotThrow() {
        assertFalse(ErrorChatSupport.postErrorCard(ec, event([tenantId: null])))
        assertFalse(ec.message.hasError())
        assertTrue(posted.isEmpty())
    }

    @Test
    void aMalformedWebhookNeverShowsItsTokenAndNeverFailsTheRun() {
        webhook = "https://chat.googleapis.com/v1/spaces/X/messages?key=k&token=SECRETTOKEN and a space"
        DarpanSettings.expire()
        ErrorChatSupport.postHook = null
        assertFalse(ErrorChatSupport.postErrorCard(ec, event()))
        String note = ErrorChatSupport.failureNote(new IllegalArgumentException("Illegal character in query at index 9: " + webhook), webhook)
        assertFalse(note.contains("SECRETTOKEN"), note)
        assertFalse(note.contains("Illegal character"), note)
    }

    @Test
    void aMissingScheduledDateFallsBackToTheCompletedDate() {
        assertTrue(ErrorChatSupport.postErrorCard(ec, event([scheduledDate: null, completedDate: Timestamp.from(Instant.parse("2026-11-25T15:00:00Z"))])))
        assertTrue(ErrorChatSupport.postErrorCard(ec, event([scheduledDate: null, completedDate: null])))
    }

    @Test
    void sameCheckIdInTwoAuditsIsTwoCards() {
        Map e = event()
        assertTrue(ErrorChatSupport.postErrorCard(ec, e))
        assertTrue(ErrorChatSupport.postErrorCard(ec, e + [auditId: "AUD2"]))
    }
}
