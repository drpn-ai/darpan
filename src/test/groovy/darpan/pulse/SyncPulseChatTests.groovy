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
    private static final String DOT = " · "
    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z")

    private ExecutionContext ec
    private Map<String, Instant> upstream = [:]
    private Set<String> arrived = [] as Set
    private Map upstreamFailure = null
    private List<Map> posts = Collections.synchronizedList([])
    private boolean postsThrow = false
    private List<Thread> pulseThreads = Collections.synchronizedList([])

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "sync-pulse-chat")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SyncPulseSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml")
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
        upstream.clear(); arrived.clear(); posts.clear(); pulseThreads.clear()
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
            posts << [ref: ref, text: payload.text]
            return [ok: true]
        })
        AutomationExecutionSupport.setRunAdmission(admission())
    }

    @AfterEach
    void resetHooks() {
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

    @Test
    void failurePostedOnSecondInARowThenOnceMore() {
        upstreamFailure = [ok: false, records: [], errors: ["HotWax answered 503"]]
        pulse(T0)
        assertEquals([], posts, "one failed pulse is not news")
        pulse(T0.plus(Duration.ofMinutes(5)))
        assertEquals(["${LABEL}: the sync pulse could not read for 2 pulses.".toString()], posts*.text)
        pulse(T0.plus(Duration.ofMinutes(10)))
        pulse(T0.plus(Duration.ofMinutes(15)))
        assertEquals(1, posts.size(), "not again until an OK pulse")

        upstreamFailure = null
        pulse(T0.plus(Duration.ofMinutes(20)))
        upstreamFailure = [ok: false, records: [], errors: ["HotWax answered 503"]]
        pulse(T0.plus(Duration.ofMinutes(25)))
        assertEquals(1, posts.size())
        pulse(T0.plus(Duration.ofMinutes(30)))
        assertEquals(2, posts.size(), "after an OK pulse, a new second failure posts again")
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

        scan(T0)
        String start = "Black Friday started. Until Nov 23:\nNetSuite sync allowance 6h${DOT}pulse every hour${DOT}heavy runs wait 08:00–23:00".toString()
        assertEquals([[ref: URL1, text: start], [ref: URL2, text: start]] as Set, posts as Set)
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
