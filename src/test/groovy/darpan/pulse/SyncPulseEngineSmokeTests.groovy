package darpan.pulse

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission
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
 * DAR-BE-328 plan 3, Task 4. The pulse engine against a real database with the reader stubbed: orders opened once and
 * closed on arrival, FAILED and SKIPPED samples, the window margin across a skipped pulse, silent skips, a peak period's
 * cadence, and run#DuePulses as its ServiceJob runs it (nobody logged in, authz on, each pulse on its own thread).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncPulseEngineSmokeTests {

    private static final String A = "KREWE"
    private static final String AUTO = "PULSE_E_AUTO"
    private static final String AUTO_OFF = "PULSE_E_OFF"
    private static final String AUTO_ARCH = "PULSE_E_ARCH"
    private static final String AUTO_NS = "PULSE_E_NSUP"
    private static final String B = "PULSE_E_OTHER"
    private static final String AUTO_B = "PULSE_E_B"
    private static final String SP = "SPE_MAIN"
    private static final String SERVICE = "pulse.SyncPulseServices.run#DuePulses"
    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z")

    private ExecutionContext ec
    /** The upstream orders the stubbed HotWax read holds: key -> orderDate. */
    private Map<String, Instant> upstream = [:]
    /** Keys the stubbed NetSuite read holds. */
    private Set<String> arrived = [] as Set
    private List<Map> upstreamCalls = Collections.synchronizedList([])
    private List<Collection> targetCalls = Collections.synchronizedList([])
    private Map upstreamFailure = null
    private List<Thread> pulseThreads = Collections.synchronizedList([])

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "sync-pulse-engine")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SyncPulseSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: B, description: "Other",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        StringBuilder xml = new StringBuilder('<entity-facade-xml type="seed">')
        [[AUTO, "Y", "N", "OMS_GQL"], [AUTO_OFF, "N", "N", "OMS_GQL"], [AUTO_ARCH, "Y", "Y", "OMS_GQL"],
         [AUTO_NS, "Y", "N", "NETSUITE_SUITEQL"], [AUTO_B, "Y", "N", "OMS_GQL", B]].each { List a ->
            String tenant = a.size() > 4 ? a[4] : A
            String other = a[3] == "OMS_GQL" ? "NETSUITE_SUITEQL" : "OMS_GQL"
            xml << """<darpan.reconciliation.ReconciliationAutomation automationId="${a[0]}" automationName="${a[0]}" companyUserGroupId="${tenant}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="PULSE_E_RS" isActive="${a[1]}" isArchived="${a[2]}"
                relativeWindowTypeEnumId="${AutomationExecutionSupport.WINDOW_PREVIOUS_DAY}" windowTimeZone="UTC"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${a[0]}" fileSide="FILE_1" companyUserGroupId="${tenant}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="${a[3]}"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${a[0]}" fileSide="FILE_2" companyUserGroupId="${tenant}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="${other}"/>"""
        }
        xml << '</entity-facade-xml>'
        ec.entity.makeDataLoader().dummyFks(true).xmlText(xml.toString()).load()
    }

    @AfterAll
    void cleanup() {
        SyncPulseSupport.resetHooks()
        AutomationExecutionSupport.setRunAdmission(null)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ["darpan.pulse.SyncPulseSample", "darpan.pulse.SyncPulseOrder", "darpan.pulse.PeakPeriodSync", "darpan.peak.PeakPeriod",
         "darpan.pulse.SyncPair"].each { ec.entity.find(it).disableAuthz().deleteAll() }
        upstream.clear(); arrived.clear(); upstreamCalls.clear(); targetCalls.clear(); pulseThreads.clear()
        upstreamFailure = null
        ec.message.clearAll()
        pair(SP, AUTO, 60, 60)
        SyncPulseSupport.setReaders({ def rec, Map p, Map automation, Instant from, Instant thru ->
            upstreamCalls << [syncPairId: p.syncPairId, from: from, thru: thru, thread: Thread.currentThread().name]
            if (upstreamFailure != null) return upstreamFailure
            // the window is (from, thru]
            List records = upstream.findAll { String k, Instant at -> at.isAfter(from) && !at.isAfter(thru) }
                    .collect { String k, Instant at -> [key: k, orderName: k, createdAt: at, otherKey: k] }
            return [ok: true, records: records, errors: [], warnings: []]
        }, { def rec, Map p, Map automation, Collection keys ->
            targetCalls << new ArrayList(keys)
            return [ok: true, present: (keys.findAll { arrived.contains(it) } as Set<String>), unasked: [] as Set<String>,
                    errors: [], warnings: []]
        })
        AutomationExecutionSupport.setRunAdmission(admission(false))
    }

    @AfterEach
    void resetHooks() {
        SyncPulseSupport.resetHooks()
        AutomationExecutionSupport.setRunAdmission(null)
    }

    // ------------------------------------------------------------------------------------------------ engine

    @Test
    void firstPastOpensOneOrderNotTwo() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(10))

        Map first = SyncPulseSupport.runPulse(ec, pairMap(SP), T0)

        assertEquals(["#A1"], first.newlyPast*.recordKey)
        assertEquals(1, first.waitingCount)
        assertEquals(1, first.pastCount)
        assertEquals(0, first.priorPastCount)
        assertEquals([orderName: "#A1", ageMinutes: 90L], first.oldest)
        Map s = first.sample as Map
        assertEquals(["SPS_OK", 1, 90, 1, 60], [s.statusEnumId, s.waitingCount, s.oldestWaitingMinutes, s.pastAllowanceCount, s.allowanceMinutes])

        Map second = SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(5)))
        assertEquals([], second.newlyPast*.recordKey, "#A1 already has its open order")
        assertEquals(1, orders("#A1").size(), "one open order per order, never a second")
        assertEquals(1, second.priorPastCount)
        assertEquals(1, second.pastCount)
        assertEquals(Timestamp.from(T0.plus(Duration.ofMinutes(5))), orders("#A1")[0].lastCheckedStamp)
        assertEquals(Timestamp.from(T0), orders("#A1")[0].firstPastStamp)
        assertEquals(Timestamp.from(upstream["#A1"]), orders("#A1")[0].sourceCreatedStamp)
        assertEquals(2, samples().size())
        Map p = pairMap(SP)
        assertEquals(["SPS_OK", Timestamp.from(T0.plus(Duration.ofMinutes(5)))], [p.lastPulseStatusEnumId, p.lastPulseStamp])
    }

    @Test
    void arrivalClosesTheOrder() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, openOrders().size())

        // hours later the order has left the read window; it is still asked by key, and it has arrived
        upstream.clear()
        arrived << "#A1"
        Instant later = T0.plus(Duration.ofHours(6))
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), later)

        assertTrue(targetCalls.last().contains("#A1"), "an open order is re-asked by key every pulse: ${targetCalls}")
        assertEquals(["#A1"], out.closed*.recordKey)
        assertEquals(0, out.pastCount)
        assertEquals(1, out.priorPastCount)
        assertEquals(0, openOrders().size())
        assertEquals(Timestamp.from(later), orders("#A1")[0].closedStamp)
        Map s = out.sample as Map
        assertEquals(["SPS_OK", 0, null, 0], [s.statusEnumId, s.waitingCount, s.oldestWaitingMinutes, s.pastAllowanceCount])
    }

    @Test
    void failedReadWritesFailedSampleAndKeepsOrders() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)

        upstreamFailure = [ok: false, records: [], errors: ["The upstream read failed: HotWax answered 503"]]
        arrived << "#A1"
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofHours(1)))

        Map s = out.sample as Map
        assertEquals("SPS_FAILED", s.statusEnumId)
        assertTrue((s.message as String).contains("HotWax answered 503"), s.message as String)
        assertEquals(1, s.pastAllowanceCount, "the open orders are still open")
        assertNull(s.waitingCount)
        assertEquals(1, openOrders().size(), "a failed read changes no order")
        assertEquals(Timestamp.from(T0), openOrders()[0].lastCheckedStamp)
        assertEquals([], out.closed)
        assertEquals([], out.newlyPast)
        assertEquals("SPS_FAILED", pairMap(SP).lastPulseStatusEnumId)

        // and a failed target read the same
        upstreamFailure = null
        SyncPulseSupport.setReaders(null, { def rec, Map p, Map automation, Collection keys ->
            [ok: false, present: [] as Set, unasked: [] as Set, errors: ["The target read failed: NetSuite timed out"]]
        })
        upstreamFailure = null
        Map target = SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofHours(2)))
        assertEquals("SPS_FAILED", target.sample.statusEnumId)
        assertTrue((target.sample.message as String).contains("NetSuite timed out"))
        assertEquals(1, openOrders().size())
        assertEquals(Timestamp.from(T0), openOrders()[0].lastCheckedStamp)
    }

    @Test
    void unaskedKeysAreCountedNotFlagged() {
        upstream["bad key"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        SyncPulseSupport.setReaders(null, { def rec, Map p, Map automation, Collection keys ->
            [ok: true, present: [] as Set, unasked: ["bad key"] as Set, errors: []]
        })
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(["#A1"], out.newlyPast*.recordKey)
        assertEquals("1 keys could not be asked", out.sample.message)
    }

    // ------------------------------------------------------------------------------------------------ the job

    @Test
    void admissionRefusedWritesSkipped() {
        AutomationExecutionSupport.setRunAdmission(admission(true))
        Map out = runJob(T0)

        assertEquals([], out.errors)
        assertEquals([], upstreamCalls, "a refused pulse reads nothing")
        List rows = samples()
        assertEquals(1, rows.size())
        assertEquals("SPS_SKIPPED", rows[0].statusEnumId)
        assertEquals(Timestamp.from(T0), rows[0].sampleStamp)
        assertEquals(A, rows[0].companyUserGroupId)
        Map p = pairMap(SP)
        assertEquals(["SPS_SKIPPED", Timestamp.from(T0)], [p.lastPulseStatusEnumId, p.lastPulseStamp])

        // a pulse already in flight is skipped silently: no second sample
        AutomationExecutionSupport.setRunAdmission(admission(false, true))
        pair(SP, AUTO, 60, 60)
        assertEquals([], runJob(T0.plus(Duration.ofHours(1))).errors)
        assertEquals(1, samples().size())
    }

    @Test
    void skippedHourThenNextPulseStillSeesEveryOrder() {
        // #LATE is created a minute before the first pulse: it waits then (one minute old), is not read by the skipped
        // pulse, and two hours on must still be inside the read window to be seen past its allowance.
        upstream["#LATE"] = T0.minus(Duration.ofMinutes(1))

        assertEquals([], runJob(T0).errors)
        assertEquals("SPS_OK", latestSample().statusEnumId)
        assertEquals(1, latestSample().waitingCount as Integer)
        assertNotNull(upstreamCalls[0].thread)
        assertTrue(upstreamCalls[0].thread != Thread.currentThread().name, "a pulse runs on its own thread")

        AutomationExecutionSupport.setRunAdmission(admission(true))
        assertEquals([], runJob(T0.plus(Duration.ofMinutes(60))).errors)
        assertEquals("SPS_SKIPPED", latestSample().statusEnumId)

        AutomationExecutionSupport.setRunAdmission(admission(false))
        Instant third = T0.plus(Duration.ofMinutes(120))
        assertEquals([], runJob(third).errors)
        assertEquals(third.minus(Duration.ofMinutes(60 + 120)), upstreamCalls.last().from, "allowance + 2 x cadence back")
        assertEquals(1, openOrders().size(), "the order the skipped pulse never saw is past its allowance")
        assertEquals("#LATE", openOrders()[0].recordKey)
        assertEquals(1, latestSample().pastAllowanceCount as Integer)
    }

    @Test
    void inactiveOrDeletedAutomationSkippedSilently() {
        pair("SPE_OFF", AUTO_OFF, 60, 60)
        pair("SPE_ARCH", AUTO_ARCH, 60, 60)
        pair("SPE_NS", AUTO_NS, 60, 60)
        pair("SPE_PAUSED", AUTO, 60, 60, [isActive: "N"])
        pair("SPE_NOPULSE", AUTO, 60, null)
        // the pair names a check its tenant does not have (another tenant's)
        pair("SPE_FOREIGN", AUTO_B, 60, 60)

        Map out = runJob(T0)

        assertEquals([], out.errors)
        assertEquals([SP], upstreamCalls*.syncPairId, "only the pair with a live HotWax-upstream check pulses")
        assertEquals([SP], samples()*.syncPairId)
        ["SPE_OFF", "SPE_ARCH", "SPE_NS", "SPE_PAUSED", "SPE_NOPULSE", "SPE_FOREIGN"].each {
            assertNull(pairMap(it).lastPulseStamp, it)
        }
    }

    @Test
    void oneBadPairNeverStopsTheOthers() {
        pair("SPE_BAD", AUTO, 60, 60)
        Closure upstreamRead = SyncPulseSupport.upstreamReader
        SyncPulseSupport.setReaders({ def rec, Map p, Map automation, Instant from, Instant thru ->
            if (p.syncPairId == "SPE_BAD") throw new IllegalStateException("a reader bug")
            upstreamRead.call(rec, p, automation, from, thru)
        }, null)
        Map out = runJob(T0)
        assertEquals([], out.errors)
        assertEquals("SPS_OK", samples().find { it.syncPairId == SP }?.statusEnumId)
        Map bad = samples().find { it.syncPairId == "SPE_BAD" }
        assertEquals("SPS_FAILED", bad?.statusEnumId, "a pulse that throws writes a FAILED sample")
        assertTrue((bad.message as String).contains("a reader bug"), bad.message as String)
    }

    @Test
    void periodOverridesCadence() {
        // pulses every 6 hours normally, last 1 hour ago: not due
        pair(SP, AUTO, 60, 360, [lastPulseStamp: Timestamp.from(T0.minus(Duration.ofHours(1)))])
        assertEquals([], runJob(T0).errors)
        assertEquals([], samples(), "not due on its normal cadence")

        LocalDate today = T0.atZone(ZoneOffset.UTC).toLocalDate()
        ec.entity.makeValue("darpan.peak.PeakPeriod").setAll([peakPeriodId: "PE_BF", companyUserGroupId: A, periodName: "BF",
                fromDate: java.sql.Date.valueOf(today), thruDate: java.sql.Date.valueOf(today.plusDays(2)), isActive: "Y"])
                .create()
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: "PE_BF", syncPairId: SP, companyUserGroupId: A,
                allowanceMinutes: 30, pulseEveryMinutes: 60]).create()

        assertEquals([], runJob(T0).errors)
        assertEquals(1, samples().size(), "the period's hourly cadence makes it due")
        assertEquals(30, samples()[0].allowanceMinutes as Integer, "and its allowance is the period's")
        assertEquals(T0.minus(Duration.ofMinutes(30 + 120)), upstreamCalls.last().from)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void pair(String id, String automationId, Integer allowance, Integer cadence, Map extra = [:]) {
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll([syncPairId: id, companyUserGroupId: A, automationId: automationId,
                upstreamSide: "FILE_1", allowanceMinutes: allowance, pulseEveryMinutes: cadence, isActive: "Y",
                lastPulseStamp: null, lastPulseStatusEnumId: null] + extra).createOrUpdate()
    }

    private Map pairMap(String id) {
        ec.entity.find("darpan.pulse.SyncPair").condition("syncPairId", id).disableAuthz().useCache(false).one()?.getMap()
    }

    private List<Map> samples() {
        ec.entity.find("darpan.pulse.SyncPulseSample").disableAuthz().orderBy("sampleStamp").useCache(false).list()*.getMap()
    }

    private Map latestSample() { samples().last() }

    private List<Map> orders(String key) {
        ec.entity.find("darpan.pulse.SyncPulseOrder").condition("recordKey", key).disableAuthz().useCache(false).list()*.getMap()
    }

    private List<Map> openOrders() {
        ec.entity.find("darpan.pulse.SyncPulseOrder").condition("closedStamp", null).disableAuthz().useCache(false).list()*.getMap()
    }

    /**
     * An admission whose pulses run on their own threads (joined by runJob), as RunAdmission's pool runs them; refusing
     * says no slot is free, busy says the pair's pulse is already in flight.
     */
    private RunAdmission admission(boolean refusing, boolean busy = false) {
        Executor ownThread = { Runnable r ->
            Thread t = new Thread(r, "pulse-test-" + pulseThreads.size())
            pulseThreads << t
            t.start()
        } as Executor
        return new RunAdmission(8, 5, 101, { -> 0 }, ownThread, 10) {
            @Override boolean canStartNow(String tenantId) { return !refusing && super.canStartNow(tenantId) }
            @Override synchronized boolean busy(String label) { return busy || super.busy(label) }
        }
    }

    /** run#DuePulses as its ServiceJob runs it: nobody logged in, authz on; then waits for the pulses it started. */
    private Map runJob(Instant now) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.user.logoutUser()
        ec.artifactExecution.enableAuthz()
        ec.message.clearAll()
        Map out = null
        List<String> errors = []
        try {
            assertNull(TenantAccessSupport.currentActiveTenantUserGroupId(ec), "test precondition: no tenant in the job")
            out = ec.service.sync().name(SERVICE).parameters([nowTimestamp: Timestamp.from(now)]).call()
            errors.addAll(ec.message.errors.collect { it.toString() })
        } catch (Exception e) {
            errors << e.toString()
        } finally {
            ec.message.clearAll()
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
        new ArrayList<Thread>(pulseThreads).each { it.join(60000) }
        return [out: out, errors: errors]
    }
}
