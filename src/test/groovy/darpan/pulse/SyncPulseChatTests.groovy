package darpan.pulse

import darpan.peak.PeakWindow
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor

import darpan.config.DarpanSettings
import darpan.ops.ErrorChatSupport

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328 plan 3, Task 5. The sync pulse's chat posts against a real database with the reader and the chat delivery
 * stubbed: one post per change (past, clear), the hourly summary, the failure notice, period start and end once each, no
 * space no post, and a pulse cadence of 0 in a period meaning off.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncPulseChatTests {

    private static final String A = "KREWE"
    private static final String AUTO = "PULSE_C_AUTO"
    private static final String SP = "SPC_MAIN"
    private static final String SP2 = "SPC_SECOND"
    private static final String SPACE = "PULSE_C_SPACE"
    private static final String SPACE2 = "PULSE_C_SPACE2"
    private static final String URL1 = "https://chat.googleapis.com/v1/spaces/PULSEC1/messages?key=k&token=t"
    private static final String URL2 = "https://chat.googleapis.com/v1/spaces/PULSEC2/messages?key=k&token=t"
    private static final String LABEL = "HotWax → NetSuite orders"
    /** DAR-BE-388: a period post names the systems only (a pulse always reads orders). */
    private static final String SYS = "HotWax → NetSuite"
    private static final String DOT = " · "
    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z")

    private ExecutionContext ec
    private Map<String, Instant> upstream = [:]
    private Set<String> arrived = [] as Set
    private Map upstreamFailure = null
    private List<Map> posts = Collections.synchronizedList([])
    /** Every payload as delivered (after provider shaping), for the card checks. */
    private List<Map> payloads = Collections.synchronizedList([])
    /** DAR-BE-391: each post's last-line pulse link, kept apart so the word checks stay about the words. */
    private List<String> links = Collections.synchronizedList([])
    /** DAR-BE-382: what reached Darpan's alerts room (ErrorChatSupport.postHook). */
    private List<Map> alerts = Collections.synchronizedList([])
    private static final String ALERTS = "https://chat.googleapis.com/v1/spaces/DARPANALERTS/messages?key=k&token=t"
    private String alertsRoom = ALERTS
    private boolean postsThrow = false
    private List<Thread> pulseThreads = Collections.synchronizedList([])

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "sync-pulse-chat")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SyncPulseSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        String xml = """<entity-facade-xml type="seed">
            <darpan.reconciliation.ReconciliationAutomation automationId="${AUTO}" automationName="${AUTO}" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="PULSE_C_RS" isActive="Y" isArchived="N"
                relativeWindowTypeEnumId="${AutomationExecutionSupport.WINDOW_PREVIOUS_DAY}" windowTimeZone="UTC"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${AUTO}" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="OMS_GQL"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${AUTO}" fileSide="FILE_2" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="NETSUITE_SUITEQL"/>
            <darpan.reconciliation.TenantChatSpace chatSpaceId="${SPACE}" companyUserGroupId="${A}" spaceName="Pulse chat 1" webhookUrl="${URL1.replace('&', '&amp;')}" isActive="Y"/>
            <darpan.reconciliation.TenantChatSpace chatSpaceId="${SPACE2}" companyUserGroupId="${A}" spaceName="Pulse chat 2" webhookUrl="${URL2.replace('&', '&amp;')}" isActive="Y"/>
        </entity-facade-xml>"""
        ec.entity.makeDataLoader().dummyFks(true).xmlText(xml).load()
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        SyncPulseSupport.resetHooks()
        AutomationExecutionSupport.setRunAdmission(null)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ["darpan.pulse.SyncPulseSample", "darpan.pulse.SyncPulseOrder", "darpan.pulse.SyncPulsePresentKey", "darpan.pulse.PeakPeriodSync", "darpan.peak.PeakPeriod",
         "darpan.pulse.SyncPair"].each { ec.entity.find(it).disableAuthz().deleteAll() }
        SyncPulseSupport.forgetPresentMemoryForTests()
        upstream.clear(); arrived.clear(); posts.clear(); payloads.clear(); links.clear(); alerts.clear(); pulseThreads.clear()
        alertsRoom = ALERTS
        DarpanSettings.rowLoader = { -> alertsRoom ? [(ErrorChatSupport.WEBHOOK_SETTING): [settingValue: alertsRoom]] : [:] }
        DarpanSettings.expire()
        ErrorChatSupport.postHook = { String url, Map payload -> alerts << [url: url, text: payload.text]; return [ok: true] }
        upstreamFailure = null
        postsThrow = false
        ec.message.clearAll()
        pair(SP, 60, 60, SPACE)
        SyncPulseSupport.setReaders({ def rec, Map p, Map automation, Instant from, Instant thru ->
            if (upstreamFailure != null) return upstreamFailure
            List records = upstream.findAll { String k, Instant at -> at.isAfter(from) && !at.isAfter(thru) }
                    .collect { String k, Instant at -> [key: k, orderName: k, createdAt: at, otherKey: k] }
            return [ok: true, records: records, errors: [], warnings: []]
        }, { def rec, Map p, Map automation, Collection keys ->
            return [ok: true, present: (keys.findAll { arrived.contains(it) } as Set<String>), unasked: [] as Set<String>,
                    errors: [], warnings: []]
        })
        TenantNotificationSupport.setDeliveryHook({ String ref, Map payload ->
            if (postsThrow) throw new IllegalStateException("chat is down")
            String said = (payload.text ?: payload.fallbackText) as String
            java.util.regex.Matcher link = said == null ? null : (said =~ /\n<([^|>]+)\|Open the pulse>\z/)
            if (link?.find()) { links << link.group(1); said = said.substring(0, link.start()) }
            // a card carries its link as its button (DAR-BE-391)
            Object button = ((payload.cardsV2 as List<Map>)?.getAt(0)?.card?.sections as List<Map>)?.getAt(0)?.widgets?.find { it.buttonList }
            if (button) links << (button.buttonList.buttons[0].onClick.openLink.url as String)
            posts << [ref: ref, text: said]
            payloads << [ref: ref, payload: payload]
            return [ok: true]
        })
        AutomationExecutionSupport.setRunAdmission(admission())
    }

    @AfterEach
    void resetHooks() {
        ErrorChatSupport.resetForTests()
        DarpanSettings.resetForTests()
        TenantNotificationSupport.resetDeliveryHook()
        SyncPulseSupport.resetHooks()
        AutomationExecutionSupport.setRunAdmission(null)
    }

    // ------------------------------------------------------------------------------------------------ pulse posts

    @Test
    void pastPostedOnceWhileItStays() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(100))

        pulse(T0)
        assertEquals([[ref: URL1, text: "${LABEL}: 1 past the 1h allowance.\nOldest #A1, 1h 40m${DOT}0 waiting".toString()]], posts)
        assertEquals(["http://localhost:5173/pulses/${SP}?tenantId=${A}".toString()], links, "the post links to its pulse, in its tenant")
        // DAR-BE-391: a card alone in Google Chat (no text above it); the words ride as fallbackText
        Map sent = payloads[0].payload as Map
        assertNull(sent.text, "no text above the card")
        assertNull(sent.slackText, "Slack's words never reach Google Chat")
        Map card = (sent.cardsV2 as List<Map>)[0].card as Map
        assertEquals([title: LABEL, subtitle: "1 past the 1h allowance", imageUrl: "https://drpn.ai/favicon.png", imageType: "CIRCLE"], card.header)
        List<Map> widgets = (card.sections as List<Map>)[0].widgets as List<Map>
        assertEquals(2, widgets.size(), "the oldest and the button; no waiting row")
        assertEquals("<font color=\"#d93025\">#A1</font>${DOT}waiting 1h 40m".toString(), widgets[0].decoratedText.text)

        pulse(T0.plus(Duration.ofMinutes(5)))
        pulse(T0.plus(Duration.ofMinutes(10)))
        assertEquals(1, posts.size(), "the same past count is not said again")
        assertEquals(1, pairMap(SP).lastPostedPastCount as Integer)

        upstream["#A2"] = T0.minus(Duration.ofMinutes(150))
        pulse(T0.plus(Duration.ofMinutes(15)))
        assertEquals(1, posts.size(), "a rise while some are already past posts nothing")
        assertEquals(2, pairMap(SP).lastPostedPastCount as Integer, "the count follows")
    }

    @Test
    void clearPostedWhenPastGoesToZero() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        pulse(T0)
        posts.clear()

        arrived << "#A1"
        pulse(T0.plus(Duration.ofHours(6)))

        assertEquals([[ref: URL1, text: "${LABEL}: caught up. Nothing past the 1h allowance.\n0 waiting${DOT}oldest none${DOT}1 finding closed".toString()]], posts)
        assertEquals(0, pairMap(SP).lastPostedPastCount as Integer)
        pulse(T0.plus(Duration.ofHours(6)).plus(Duration.ofMinutes(5)))
        assertEquals(1, posts.size(), "caught up is said once")
    }

    @Test
    void clearCountsTheWholeEpisodeNotOnePulse() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(95))
        upstream["#A3"] = T0.minus(Duration.ofMinutes(100))
        pulse(T0)
        assertEquals(1, posts.size())
        assertNotNull(pairMap(SP).episodeStartStamp)
        posts.clear()

        arrived << "#A1"
        pulse(T0.plus(Duration.ofMinutes(5)))
        arrived << "#A2"
        pulse(T0.plus(Duration.ofMinutes(10)))
        assertEquals([], posts, "still one past: silent")
        arrived << "#A3"
        pulse(T0.plus(Duration.ofMinutes(15)))

        assertEquals(["${LABEL}: caught up. Nothing past the 1h allowance.\n0 waiting${DOT}oldest none${DOT}3 findings closed".toString()], posts*.text)
        assertNull(pairMap(SP).episodeStartStamp, "the clear post ends the episode")
    }

    @Test
    void clearCountsArrivalsAndNamesWhatLeftTheCheck() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(95))
        pulse(T0)
        posts.clear()

        arrived << "#A1"
        upstream.remove("#A2")
        pulse(T0.plus(Duration.ofMinutes(60)))

        assertEquals(["${LABEL}: caught up. Nothing past the 1h allowance.\n0 waiting${DOT}oldest none${DOT}1 finding closed${DOT}1 left the check".toString()], posts*.text)
    }

    @Test
    void aFreshEpisodeCountsFromZero() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        pulse(T0)
        arrived << "#A1"
        pulse(T0.plus(Duration.ofMinutes(5)))
        posts.clear()

        upstream["#B1"] = T0.plus(Duration.ofMinutes(10)).minus(Duration.ofMinutes(90))
        pulse(T0.plus(Duration.ofMinutes(10)))
        assertEquals(1, posts.size(), "a new episode is announced")
        arrived << "#B1"
        pulse(T0.plus(Duration.ofMinutes(15)))

        assertEquals("${LABEL}: caught up. Nothing past the 1h allowance.\n0 waiting${DOT}oldest none${DOT}1 finding closed".toString(), posts[1].text,
                "the first episode's arrival is not carried over")
    }

    @Test
    void noPostWhenNothingChanged() {
        pair(SP, 180, 60, SPACE)
        upstream["#W1"] = T0.minus(Duration.ofMinutes(10))
        pulse(T0)
        pulse(T0.plus(Duration.ofMinutes(5)))
        pulse(T0.plus(Duration.ofMinutes(65)))
        assertEquals([], posts, "waiting records, nothing past, no hourly summary: silent")
    }

    @Test
    void hourlySummaryOncePerHour() {
        pair(SP, 180, 5, SPACE, [hourlySummary: "Y"])
        upstream["#W1"] = T0.minus(Duration.ofMinutes(10))

        pulse(T0)
        assertEquals(["${LABEL}: 1 waiting${DOT}oldest 10m${DOT}0 past allowance".toString()], posts*.text)
        pulse(T0.plus(Duration.ofMinutes(5)))
        pulse(T0.plus(Duration.ofMinutes(10)))
        assertEquals(1, posts.size(), "the same hour is not summarized twice")
        pulse(T0.plus(Duration.ofMinutes(60)))
        assertEquals(2, posts.size(), "the next hour's first pulse is")
        assertEquals("${LABEL}: 1 waiting${DOT}oldest 1h 10m${DOT}0 past allowance".toString(), posts[1].text)
    }

    /**
     * DAR-BE-382. A pulse that did not run is Darpan's problem, not the client's: two in a row post to Darpan's alerts
     * room (errors.chat.webhookUrl), never the pair's chat space, with the tenant and the last pulse's reason. GORJANA's
     * client room got "could not read for 2 pulses" on 2026-11-26 for two pulses that were only skipped.
     */
    @Test
    void twoFailedPulsesAlertDarpanNeverTheClientRoom() {
        upstreamFailure = [ok: false, records: [], errors: ["HotWax answered 503"]]
        pulse(T0)
        assertEquals([], alerts, "one failed pulse is not news")
        pulse(T0.plus(Duration.ofMinutes(5)))
        assertEquals([], posts, "nothing reaches the client's room")
        assertEquals(["${A}${DOT}${LABEL}: 2 pulses in a row did not finish.\nLast: HotWax answered 503".toString()],
                alerts*.text)
        assertEquals([ALERTS], alerts*.url)
        pulse(T0.plus(Duration.ofMinutes(10)))
        pulse(T0.plus(Duration.ofMinutes(15)))
        assertEquals(1, alerts.size(), "not again until an OK pulse")

        upstreamFailure = null
        pulse(T0.plus(Duration.ofMinutes(20)))
        upstreamFailure = [ok: false, records: [], errors: ["HotWax answered 503"]]
        pulse(T0.plus(Duration.ofMinutes(25)))
        assertEquals(1, alerts.size())
        pulse(T0.plus(Duration.ofMinutes(30)))
        assertEquals(2, alerts.size(), "after an OK pulse, a new second failure alerts again")
        assertEquals([], posts, "and the client's room still heard nothing")
    }

    /** DAR-BE-382. Two skipped pulses (no slot) say they were skipped, in the alerts room only; nothing to the client. */
    @Test
    void twoSkippedPulsesAlertDarpanAsSkipped() {
        SyncPulseSupport.recordSkipped(ec, pairMap(SP), T0, 60, "This pulse was skipped: all 4 light run slots are in use")
        SyncPulseSupport.recordSkipped(ec, pairMap(SP), T0.plus(Duration.ofMinutes(60)), 60,
                "This pulse was skipped: all 4 light run slots are in use")
        assertEquals([], posts)
        assertEquals(["${A}${DOT}${LABEL}: 2 pulses in a row did not finish.\nLast: This pulse was skipped: all 4 light run slots are in use".toString()],
                alerts*.text)
    }

    /** DAR-BE-382. A pair with no chat space still tells Darpan when its pulses stop. */
    @Test
    void aPairWithNoChatSpaceStillAlertsDarpan() {
        pair(SP, 60, 60, null)
        upstreamFailure = [ok: false, records: [], errors: ["HotWax answered 503"]]
        pulse(T0)
        pulse(T0.plus(Duration.ofMinutes(5)))
        assertEquals([], posts)
        assertEquals(1, alerts.size())
    }

    /** DAR-BE-382. No alerts room set: the notice is dropped (logged), and still never goes to the client's room. */
    @Test
    void noAlertsRoomStillNeverTheClientRoom() {
        alertsRoom = null
        DarpanSettings.expire()
        upstreamFailure = [ok: false, records: [], errors: ["HotWax answered 503"]]
        pulse(T0)
        pulse(T0.plus(Duration.ofMinutes(5)))
        assertEquals([], posts)
        assertEquals([], alerts)
    }

    @Test
    void noChatSpaceNoPost() {
        pair(SP, 60, 60, null, [hourlySummary: "Y"])
        upstream["#A1"] = T0.minus(Duration.ofMinutes(100))
        pulse(T0)
        upstreamFailure = [ok: false, records: [], errors: ["down"]]
        pulse(T0.plus(Duration.ofMinutes(5)))
        pulse(T0.plus(Duration.ofMinutes(10)))
        assertEquals([], posts)
        assertNull(pairMap(SP).lastPostedPastCount)
    }

    @Test
    void aFailingPostNeverFailsThePulse() {
        postsThrow = true
        upstream["#A1"] = T0.minus(Duration.ofMinutes(100))
        Map out = pulse(T0)
        assertEquals("SPS_OK", out.status)
        assertEquals(1, openOrders())
        assertNull(pairMap(SP).lastPostedPastCount, "an unsent post is retried on the next pulse")
        postsThrow = false
        pulse(T0.plus(Duration.ofMinutes(5)))
        assertEquals(1, posts.size())
    }

    // ------------------------------------------------------------------------------------------------ periods

    @Test
    void periodStartPostedOnceAcrossTicks() {
        pair(SP, 60, 60, SPACE, [lastPulseStamp: Timestamp.from(T0)])
        pair(SP2, 60, 60, SPACE2, [lastPulseStamp: Timestamp.from(T0)])
        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_BF", companyUserGroupId: A, periodName: "Black Friday",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today.plusDays(2)), isActive: "Y",
                peakHoursStart: "08:00", peakHoursEnd: "23:00"]).create()
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PC_BF", syncPairId: SP, companyUserGroupId: A,
                allowanceMinutes: 360, pulseEveryMinutes: 60]).create()
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PC_BF", syncPairId: SP2, companyUserGroupId: A,
                allowanceMinutes: 240]).create()

        scan(T0)
        // DAR-BE-388: each room hears only its own pairs, one line each; peak hours are Darpan's own scheduling, not said
        assertEquals([[ref: URL1, text: "Black Friday started. Until Nov 23:\n${SYS} · hourly · late after 6h".toString()],
                      [ref: URL2, text: "Black Friday started. Until Nov 23:\n${SYS} · hourly · late after 4h".toString()]] as Set,
                posts as Set, "a null cadence in the period is the pair's own (hourly)")
        assertEquals(2, posts.size(), "one post per distinct space")
        assertNotNull(period("PC_BF").startPostedStamp)

        scan(T0.plus(Duration.ofMinutes(5)))
        scan(T0.plus(Duration.ofMinutes(10)))
        assertEquals(2, posts.size(), "the stamp holds across ticks")

        posts.clear()
        Instant after = T0.plus(Duration.ofDays(4))
        scan(after)
        assertEquals(2, posts.size())
        assertEquals(["Black Friday ended. Normal settings are back."] * 2, posts*.text)
        assertNotNull(period("PC_BF").endPostedStamp)
        scan(after.plus(Duration.ofMinutes(5)))
        assertEquals(2, posts.size(), "the end is once too")
    }

    // DAR-BE-389 (Aditi 2026-11-27, RAILS "PEAK Nov 27 started. Until Nov 27:" then six pairs on one line): "dont like how
    // this message looks". A one-day period says so; each setting is its own line. DAR-BE-388: a period that changes no pair
    // the room hears from posts nothing, and is still stamped (so it is not looked at again every tick).
    @Test
    void aOneDayPeriodSaysTodayOnly() {
        pair(SP, 60, 60, SPACE, [lastPulseStamp: Timestamp.from(T0)])
        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_ONE", companyUserGroupId: A, periodName: "PEAK Nov 21",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today), isActive: "Y",
                peakHoursStart: "00:00", peakHoursEnd: "01:30"]).create()
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PC_ONE", syncPairId: SP, companyUserGroupId: A,
                allowanceMinutes: 360, pulseEveryMinutes: 60]).create()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_BARE", companyUserGroupId: A, periodName: "Quiet",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today.plusDays(2)), isActive: "Y",
                peakHoursStart: "00:00", peakHoursEnd: "01:30"]).create()
        scan(T0)
        assertEquals(["PEAK Nov 21 started. Today only:\n${SYS} · hourly · late after 6h".toString()], posts*.text)
        assertNotNull(period("PC_BARE").startPostedStamp, "nothing to say is still done")

        posts.clear()
        scan(T0.plus(Duration.ofDays(4)))
        assertEquals(["PEAK Nov 21 ended. Normal settings are back."], posts*.text, "a period that said nothing says nothing at its end")
        assertNotNull(period("PC_BARE").endPostedStamp)
    }

    // DAR-BE-388 (Aditi 2026-11-27 15:18 IST, GORJANA "Gorjana Go live": "PEAK Nov 27 started. Until Nov 27:" then 16 pairs). Of
    // GORJANA's 16 pairs only two are checked in the period; the returns pair and the 13 month-close pairs are there only so the
    // period moves their check's start, and never reach the client room.
    @Test
    void onlyThePairsCheckedInThePeriodAreSaid() {
        Map<String, Map> pairs = [N: [syncPairId: "N", chatSpaceId: SPACE, allowanceMinutes: 120, pulseEveryMinutes: null],
                                  S: [syncPairId: "S", chatSpaceId: SPACE, allowanceMinutes: 120, pulseEveryMinutes: 60]]
        Map<String, String> labels = [N: SYS, S: "Shopify → HotWax"]
        List<Map> rows = [[syncPairId: "N", allowanceMinutes: 360, pulseEveryMinutes: 60],
                          [syncPairId: "R", allowanceMinutes: 360],
                          [syncPairId: "S", allowanceMinutes: 120]] +
                (51..63).collect { [syncPairId: "M${it}".toString(), allowanceMinutes: 1440] }
        // R and M51..M63 cannot pulse (returns upstream, month-close checks): the caller leaves them out of pairs
        assertEquals([(SPACE): ["${SYS} · hourly · late after 6h".toString(), "Shopify → HotWax · hourly · late after 2h"]],
                SyncPulseSupport.clientLines(rows, pairs, labels))

        Map<String, Map> off = [N: [syncPairId: "N", chatSpaceId: SPACE, allowanceMinutes: 120, pulseEveryMinutes: 60]]
        assertEquals([:], SyncPulseSupport.clientLines([[syncPairId: "N", pulseEveryMinutes: 0]], off, labels), "0 in the period is off")
        assertEquals([:], SyncPulseSupport.clientLines([[syncPairId: "N", allowanceMinutes: 60]],
                [N: off.N + [chatSpaceId: null]], labels), "a pair with no room is said nowhere")

        Map<String, Map> five = (1..5).collectEntries { ["S${it}".toString(), [syncPairId: "S${it}".toString(), chatSpaceId: SPACE, allowanceMinutes: 60, pulseEveryMinutes: 30]] }
        assertEquals([(SPACE): ["Shopify → HotWax · every 30m · late after 2h (5 pairs)"]],
                SyncPulseSupport.clientLines((1..5).collect { [syncPairId: "S${it}".toString(), allowanceMinutes: 120] }, five,
                        five.keySet().collectEntries { [(it): "Shopify → HotWax"] }), "the same line for several pairs is said once")
    }

    // DAR-BE-388: GORJANA's periods run back to back (PEAK Nov 27, then Sale Nov 28-29 from the next midnight). The new period's
    // start says the new settings; "Normal settings are back" would be wrong, so the ending period is stamped without a post.
    @Test
    void backToBackPeriodsPostOnlyTheNextStart() {
        pair(SP, 60, null, SPACE, [lastPulseStamp: Timestamp.from(T0)])
        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_PEAK", companyUserGroupId: A, periodName: "PEAK Nov 20",
                fromDate: java.sql.Date.valueOf(today.minusDays(1)), thruDate: java.sql.Date.valueOf(today.minusDays(1)), isActive: "Y",
                startPostedStamp: Timestamp.from(T0.minus(Duration.ofDays(1)))]).create()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_SALE", companyUserGroupId: A, periodName: "Sale Nov 21-22",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today.plusDays(1)), isActive: "Y"]).create()
        [PC_PEAK: 360, PC_SALE: 240].each { String id, Integer minutes ->
            ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: id, syncPairId: SP, companyUserGroupId: A,
                    allowanceMinutes: minutes, pulseEveryMinutes: 60]).create()
        }
        scan(T0)
        assertEquals(["Sale Nov 21-22 started. Until Nov 22:\n${SYS} · hourly · late after 4h".toString()], posts*.text)
        assertNotNull(period("PC_PEAK").endPostedStamp, "the ended period is done, without a post")
    }

    // DAR-BE-388 (Coordinator review 2026-11-27): GORJANA's PEAK Nov 27 ends into Sale Nov 28-29, which checks no pair hourly
    // but still holds other allowances, so "Normal settings are back" would not be true: the end says only that it ended.
    @Test
    void anEndInsideAnotherPeriodSaysOnlyThatItEnded() {
        pair(SP, 60, null, SPACE, [lastPulseStamp: Timestamp.from(T0)])
        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_PEAK", companyUserGroupId: A, periodName: "PEAK Nov 20",
                fromDate: java.sql.Date.valueOf(today.minusDays(1)), thruDate: java.sql.Date.valueOf(today.minusDays(1)), isActive: "Y",
                startPostedStamp: Timestamp.from(T0.minus(Duration.ofDays(1)))]).create()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_SALE", companyUserGroupId: A, periodName: "Sale Nov 21-22",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today.plusDays(1)), isActive: "Y"]).create()
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PC_PEAK", syncPairId: SP, companyUserGroupId: A,
                allowanceMinutes: 360, pulseEveryMinutes: 60]).create()
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PC_SALE", syncPairId: SP, companyUserGroupId: A,
                allowanceMinutes: 240]).create()
        scan(T0)
        assertEquals(["PEAK Nov 20 ended."], posts*.text)
        assertNotNull(period("PC_SALE").startPostedStamp, "the sale has nothing to say and is still done")
    }

    // DAR-BE-388 (Aditi 2026-11-27 15:42 IST: "can it be a card?"): a Google Chat room gets a card, the Darpan header with the
    // period and a row per pair; a Slack room gets the same words as text, never cardsV2.
    @Test
    void aPeriodPostIsACardInGoogleChatAndTextInSlack() {
        Map start = SyncPulseSupport.periodMessage("PEAK Nov 27 started", "Today only",
                [[label: SYS, cadence: 60, allowance: 360, count: 1], [label: "Shopify → HotWax", cadence: 60, allowance: 120, count: 2]])
        assertEquals("PEAK Nov 27 started. Today only:\n${SYS} · hourly · late after 6h\nShopify → HotWax · hourly · late after 2h (2 pairs)".toString(), start.fallbackText)
        Map card = (start.cardsV2 as List<Map>)[0].card as Map
        assertEquals([title: "PEAK Nov 27 started", subtitle: "Today only"], (card.header as Map).subMap(["title", "subtitle"]))
        List<Map> rows = (card.sections as List<Map>)[0].widgets as List<Map>
        assertEquals(["${SYS} · hourly · late after 6h".toString(), "Shopify → HotWax · hourly · late after 2h (2 pairs)"],
                rows.collect { it.decoratedText.text })
        Map end = SyncPulseSupport.periodMessage("PEAK Nov 27 ended", null, [])
        assertNull(start.text, "the card alone: Chat shows a message's text above its card")
        assertEquals([text: "PEAK Nov 27 ended."], end, "an end is a plain message, not a card")
        assertEquals([text: "Black Friday ended. Normal settings are back."], SyncPulseSupport.periodMessage("Black Friday ended", "Normal settings are back", []))

        String slackUrl = "https://hooks.slack.com/services/T000/B000/XXXX"
        ec.entity.makeDataLoader().dummyFks(true).xmlText("""<entity-facade-xml type="seed">
            <darpan.reconciliation.TenantChatSpace chatSpaceId="PULSE_C_SLACK" companyUserGroupId="${A}" spaceName="Slack room" webhookUrl="${slackUrl}" chatProviderEnumId="CHAT_PROV_SLACK" isActive="Y"/>
        </entity-facade-xml>""").load()
        pair(SP, 60, 60, SPACE, [lastPulseStamp: Timestamp.from(T0)])
        pair(SP2, 60, 60, "PULSE_C_SLACK", [lastPulseStamp: Timestamp.from(T0)])
        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_CARD", companyUserGroupId: A, periodName: "PEAK Nov 21",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today), isActive: "Y"]).create()
        [SP, SP2].each { String id ->
            ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PC_CARD", syncPairId: id, companyUserGroupId: A,
                    allowanceMinutes: 360, pulseEveryMinutes: 60]).create()
        }
        scan(T0)
        Map google = payloads.find { it.ref == URL1 }?.payload as Map
        Map slack = payloads.find { it.ref == slackUrl }?.payload as Map
        assertNotNull(google?.cardsV2, "the Google Chat room gets the card")
        assertNull(google.text, "and no text above it")
        assertEquals([text: "PEAK Nov 21 started. Today only:\n${SYS} · hourly · late after 6h".toString()], slack, "Slack gets the text alone")
    }

    // DAR-BE-388: a pair whose upstream a pulse cannot read (here NetSuite; GORJANA's returns pair) is never said.
    @Test
    void aPairThatCannotPulseIsNeverSaid() {
        pair(SP, 60, 60, SPACE, [upstreamSide: "FILE_2", lastPulseStamp: Timestamp.from(T0)])
        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_NP", companyUserGroupId: A, periodName: "PEAK Nov 21",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today), isActive: "Y"]).create()
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PC_NP", syncPairId: SP, companyUserGroupId: A,
                allowanceMinutes: 1440, pulseEveryMinutes: 60]).create()
        scan(T0)
        assertEquals([], posts*.text)
        assertNotNull(period("PC_NP").startPostedStamp)
    }

    @Test
    void noChatSpaceNoPeriodPostAndNoStamp() {
        pair(SP, 60, 60, null, [lastPulseStamp: Timestamp.from(T0)])
        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PC_NS", companyUserGroupId: A, periodName: "No space",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today.plusDays(2)), isActive: "Y"]).create()
        scan(T0)
        assertEquals([], posts)
        assertNull(period("PC_NS").startPostedStamp, "a space added later still gets the start")
    }

    @Test
    void periodZeroTurnsThePulseOff() {
        LocalDate d = LocalDate.parse("2026-11-21")
        List<PeakWindow> windows = [new PeakWindow("P1", "Sale", d, d.plusDays(2), null, null, [])]
        Map pair = [allowanceMinutes: 60, pulseEveryMinutes: 60]

        Map off = SyncPulseSupport.effectiveValues(pair, windows, [P1: [pulseEveryMinutes: 0]], d)
        assertEquals(0, off.pulseEveryMinutes, "0 in a period is off, not the pair's own value")
        assertTrue(!SyncPulseSupport.isDue(pair, off.pulseEveryMinutes as Integer, T0))

        Map normal = SyncPulseSupport.effectiveValues(pair, windows, [P1: [pulseEveryMinutes: null]], d)
        assertEquals(60, normal.pulseEveryMinutes, "null in a period is the pair's normal value")
        assertEquals(60, SyncPulseSupport.effectiveValues(pair, windows, [:], d.plusDays(10)).pulseEveryMinutes)
    }

    @Test
    void wordsAreShort() {
        assertEquals(["6h", "90m", "1h", "0m"], [360, 90, 60, 0].collect { SyncPulseSupport.allowanceWords(it) })
        assertEquals(["6h 40m", "6h", "40m", "0m"], [400, 360, 40, 0].collect { SyncPulseSupport.ageWords(it) })
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Map pulse(Instant now) { SyncPulseSupport.runPulse(ec, pairMap(SP), now) }

    /** run#DuePulses as its ServiceJob runs it: nobody logged in, authz on; then waits for the pulses it started. */
    private void scan(Instant now) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.user.logoutUser()
        ec.artifactExecution.enableAuthz()
        ec.message.clearAll()
        try {
            ec.service.sync().name("pulse.SyncPulseServices.run#DuePulses").parameters([nowTimestamp: Timestamp.from(now)]).call()
            assertEquals([], ec.message.errors.collect { it.toString() })
        } finally {
            ec.message.clearAll()
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
        new ArrayList<Thread>(pulseThreads).each { it.join(60000) }
    }

    private void pair(String id, Integer allowance, Integer cadence, String space, Map extra = [:]) {
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll([syncPairId: id, companyUserGroupId: A, automationId: AUTO,
                upstreamSide: "FILE_1", allowanceMinutes: allowance, pulseEveryMinutes: cadence, isActive: "Y", chatSpaceId: space,
                hourlySummary: "N", lastPulseStamp: null, lastPulseStatusEnumId: null, lastPostedPastCount: null] + extra).createOrUpdate()
    }

    private Map pairMap(String id) {
        ec.entity.find("darpan.pulse.SyncPair").condition("syncPairId", id).disableAuthz().useCache(false).one()?.getMap()
    }

    private Map period(String id) {
        ec.entity.find("darpan.peak.PeakPeriod").condition("peakPeriodId", id).disableAuthz().useCache(false).one()?.getMap()
    }

    private int openOrders() {
        ec.entity.find("darpan.pulse.SyncPulseOrder").condition("closedStamp", null).disableAuthz().useCache(false).list().size()
    }

    /** An admission whose pulses run on their own threads (joined by scan), as RunAdmission's pool runs them. */
    private RunAdmission admission() {
        Executor inline = { Runnable r -> Thread t = new Thread(r, "pulse-chat-" + pulseThreads.size()); pulseThreads << t; t.start() } as Executor
        return new RunAdmission(8, 5, 101, { -> 0 }, inline, 10)
    }
}
