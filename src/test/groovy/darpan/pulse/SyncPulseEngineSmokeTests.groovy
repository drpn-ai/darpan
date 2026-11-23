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
        ["darpan.pulse.SyncPulsePresentKey", "darpan.pulse.SyncPulseSample", "darpan.pulse.SyncPulseOrder", "darpan.pulse.PeakPeriodSync",
         "darpan.peak.PeakPeriod", "darpan.pulse.SyncPair"].each { ec.entity.find(it).disableAuthz().deleteAll() }
        upstream.clear(); arrived.clear(); upstreamCalls.clear(); targetCalls.clear(); pulseThreads.clear()
        upstreamFailure = null
        SyncPulseSupport.resetForTests()
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
        SyncPulseSupport.resetForTests()
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
        assertEquals("#A1", orders("#A1")[0].otherSystemId, "the key the target is asked by")
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
    void anOrderCancelledUpstreamInsideTheWindowClosesWithoutCountingAsSynced() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, openOrders().size())

        upstream.clear()
        Instant later = T0.plus(Duration.ofMinutes(60))
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), later)

        assertEquals([], out.closed*.recordKey, "it did not sync")
        assertEquals(["#A1"], out.dropped*.recordKey)
        assertEquals(0, out.pastCount)
        assertEquals(0, openOrders().size())
        assertEquals(Timestamp.from(later), orders("#A1")[0].closedStamp)
        Map post = SyncPulseSupport.decidePost("L", out, 1, ["SPS_OK"], false, false)
        assertEquals("clear", post.kind, "past reached 0: the caught-up post still fires")
        assertTrue((post.text as String).endsWith("0 findings closed \u00b7 1 left the check"), post.text as String)
    }

    @Test
    void anOrderOlderThanTheWindowClosesOnlyAfterTheAllowancePlus24Hours() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        upstream.clear()

        // allowance 60 min + 24 h after it was first flagged: not before
        Instant edge = T0.plus(Duration.ofHours(25))
        Map early = SyncPulseSupport.runPulse(ec, pairMap(SP), edge.minus(Duration.ofMinutes(1)))
        assertEquals(1, early.pastCount, "still inside allowance + 24h")
        assertEquals([], early.dropped*.recordKey)

        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), edge.plus(Duration.ofMinutes(1)))
        assertEquals(["#A1"], out.dropped*.recordKey)
        assertEquals([], out.closed*.recordKey)
        assertEquals(0, out.pastCount)
        assertEquals(0, openOrders().size())
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

    // ------------------------------------------------------------------------------------------ present keys (task 13)

    @Test
    void aSecondPulseAsksTheTargetOnlyAboutKeysItHasNotConfirmed() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(10))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(["#A1", "#A2"] as Set, targetCalls.last() as Set)

        upstream["#A3"] = T0.plus(Duration.ofMinutes(20))
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofHours(1)))

        assertEquals(["#A2", "#A3"] as Set, targetCalls.last() as Set, "#A1 was confirmed: not asked again")
        assertEquals(["#A1"] as Set, SyncPulseSupport.presentKeysOf(SP))
        assertEquals(2, out.waitingCount + out.pastCount, "#A1 still counts as present, not waiting or past")
    }

    @Test
    void aWaitingKeyThatThenArrivesIsRememberedAndNotAskedAgain() {
        upstream["#A2"] = T0.minus(Duration.ofMinutes(10))
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))

        arrived << "#A2"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(60)))
        assertEquals(["#A2"], targetCalls.last())
        assertEquals(["#A2"] as Set, SyncPulseSupport.presentKeysOf(SP))

        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(120)))
        assertEquals([], targetCalls.last())
    }

    @Test
    void keysOlderThanTheWindowArePruned() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(["#A1"] as Set, SyncPulseSupport.presentKeysOf(SP))

        // a window of 60 + 2 x 60 + 2 + 5 minutes: four hours on, #A1 can no longer be asked
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofHours(4)))
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))
    }

    @Test
    void aFailedPulseAddsNoKeys() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        upstreamFailure = [ok: false, records: [], errors: ["HotWax answered 503"]]
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))

        upstreamFailure = null
        SyncPulseSupport.setReaders(null, { def rec, Map p, Map automation, Collection keys ->
            [ok: false, present: ["#A1"] as Set, unasked: [] as Set, errors: ["The target read failed"]]
        })
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals("SPS_FAILED", out.status)
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))
    }

    @Test
    void anAllowanceChangeKeepsTheSetButAChangedUpstreamSideDropsIt() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(["#A1"] as Set, SyncPulseSupport.presentKeysOf(SP))

        ec.entity.makeValue("darpan.pulse.SyncPair").setAll(pairMap(SP) + [allowanceMinutes: 90]).update()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(30)))
        assertEquals([], targetCalls.last(), "an allowance change keeps what the target confirmed")
        assertEquals(["#A1"] as Set, SyncPulseSupport.presentKeysOf(SP))

        // the other side as upstream is a different question: the set starts over (the pair's check may pulse or not)
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll(pairMap(SP) + [upstreamSide: "FILE_2"]).update()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(60)))
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))
    }

    @Test
    void aScheduledFireKeepsTheSetButAnEditedCompareScopeDropsIt() {
        ec.entity.makeDataLoader().dummyFks(true).xmlText('<entity-facade-xml type="seed"><darpan.rule.RuleSetCompareScope ' +
                'compareScopeId="PULSE_E_SCOPE" ruleSetId="PULSE_E_RS" companyUserGroupId="' + A + '" objectType="Order"/></entity-facade-xml>').load()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([automationId: AUTO, compareScopeId: "PULSE_E_SCOPE"]).update()
        upstream["#A1"] = T0.minus(Duration.ofMinutes(50))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(40))
        arrived << "#A1" << "#A2"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(["#A1", "#A2"] as Set, SyncPulseSupport.presentKeysOf(SP))

        // what a scheduled fire writes: the automation's own stamps move; the set stays
        Thread.sleep(5)
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([automationId: AUTO,
                lastScheduledFireTime: Timestamp.from(T0)]).update()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(10)))
        assertEquals([], targetCalls.last(), "a scheduled fire does not make the pulse re-ask")

        // an edit of the compare scope: the target is asked about every remembered key again
        Thread.sleep(5)
        ec.entity.makeValue("darpan.rule.RuleSetCompareScope").setAll([compareScopeId: "PULSE_E_SCOPE", objectType: "OrderLine"]).update()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(20)))
        assertEquals(["#A1", "#A2"] as Set, targetCalls.last() as Set)
    }

    @Test
    void aPeakPeriodBoundaryDoesNotReAskRememberedKeys() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(50))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        // the next pulse runs with a different effective allowance and cadence (a period's)
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll(pairMap(SP) + [allowanceMinutes: 30, pulseEveryMinutes: 30]).update()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(30)))
        assertEquals([], targetCalls.last())
    }

    @Test
    void beyondTheCapNothingMoreIsAddedAndTheKeysAreAskedAgain() {
        SyncPulseSupport.setPresentCapForTests(1)
        upstream["#A1"] = T0.minus(Duration.ofMinutes(50))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(40))
        arrived << "#A1" << "#A2"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, SyncPulseSupport.presentKeysOf(SP).size())
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(30)))
        assertEquals(1, targetCalls.last().size(), "the key that did not fit is asked about again")
    }

    @Test
    void theScanDropsAnInactivePairsSet() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(50))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(["#A1"] as Set, SyncPulseSupport.presentKeysOf(SP))
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll(pairMap(SP) + [isActive: "N"]).update()
        SyncPulseSupport.runDuePulses(ec, Timestamp.from(T0.plus(Duration.ofHours(1))))
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))
    }

    @Test
    void resetForTestsEmptiesTheSet() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        SyncPulseSupport.resetForTests()
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))
    }

    @Test
    void countsMatchARunWithoutTheCache() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(100))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A3"] = T0.minus(Duration.ofMinutes(10))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        Instant later = T0.plus(Duration.ofMinutes(60))
        Map cached = SyncPulseSupport.runPulse(ec, pairMap(SP), later)

        SyncPulseSupport.dropPresent(ec, A, SP)   // forget the cache only; resetForTests() also clears the readers (DAR-OPS-026)
        Map fresh = SyncPulseSupport.runPulse(ec, pairMap(SP), later)

        assertTrue(targetCalls[1].size() < targetCalls[2].size(), "the cached pulse asked about fewer keys")
        assertEquals([fresh.status, fresh.waitingCount, fresh.pastCount],
                [cached.status, cached.waitingCount, cached.pastCount])
        assertEquals(fresh.sample.pastAllowanceCount, cached.sample.pastAllowanceCount)
    }

    // ------------------------------------------------------------------------------------------ present keys survive a restart (task 14)

    private int presentRows(String pairId = SP, String tenant = A) {
        return (int) ec.entity.find("darpan.pulse.SyncPulsePresentKey").condition("syncPairId", pairId)
                .condition("companyUserGroupId", tenant).disableAuthz().useCache(false).count()
    }

    @Test
    void afterARestartTheNextPulseAsksOnlyAboutNewKeys() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(10))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, presentRows())

        SyncPulseSupport.forgetPresentMemoryForTests()
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))
        upstream["#A3"] = T0.plus(Duration.ofMinutes(20))
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofHours(1)))

        assertEquals(["#A2", "#A3"] as Set, targetCalls.last() as Set, "#A1 came back from the table: not asked")
        assertEquals(["#A1"] as Set, SyncPulseSupport.presentKeysOf(SP))
    }

    @Test
    void aColdStartUnderAChangedSignatureAsksAboutEveryKey() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        upstream["#A2"] = T0.minus(Duration.ofMinutes(80))
        arrived << "#A1" << "#A2"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(2, presentRows())
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll([syncPairId: SP, presentSignature: "SOMETHING|ELSE"]).update()

        SyncPulseSupport.forgetPresentMemoryForTests()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(10)))
        assertEquals(["#A1", "#A2"] as Set, targetCalls.last() as Set)
        assertEquals(2, presentRows(), "re-confirmed under the new signature")
    }

    @Test
    void aWarmSignatureChangeDeletesTheRows() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, presentRows())
        upstream.clear()
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll(pairMap(SP) + [upstreamSide: "FILE_2"]).update()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(30)))
        assertEquals(0, presentRows())
    }

    @Test
    void pruningDeletesRowsOlderThanTheWindow() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, presentRows())
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofHours(4)))
        assertEquals(0, presentRows())
    }

    @Test
    void deletingThePairOrTheAutomationRemovesItsRows() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, presentRows())
        assertEquals(1, SyncPulseCascade.deletePair(ec, A, SP))
        assertEquals(0, presentRows())
        assertEquals([] as Set, SyncPulseSupport.presentKeysOf(SP))

        pair(SP, AUTO, 60, 60)
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, presentRows())
        assertEquals(1, SyncPulseCascade.deleteForAutomation(ec, A, AUTO))
        assertEquals(0, presentRows())
    }

    @Test
    void theScanDeletesAnInactivePairsRows() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(50))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals(1, presentRows())
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll(pairMap(SP) + [isActive: "N"]).update()
        SyncPulseSupport.runDuePulses(ec, Timestamp.from(T0.plus(Duration.ofHours(1))))
        assertEquals(0, presentRows())
    }

    @Test
    void aKeyWriteFailureDoesNotFailThePulse() {
        String tooLong = "#" + ("X" * 400)   // text-medium holds 255: the insert fails
        upstream[tooLong] = T0.minus(Duration.ofMinutes(10))
        upstream["#A1"] = T0.minus(Duration.ofMinutes(20))
        arrived << tooLong << "#A1"
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals("SPS_OK", out.status)
        assertEquals(2, SyncPulseSupport.presentKeysOf(SP).size(), "the in-memory set keeps the keys")
        assertEquals(0, presentRows())
        assertEquals(1, ec.entity.find("darpan.pulse.SyncPulseSample").condition("syncPairId", SP).disableAuthz().useCache(false).count(),
                "the pulse's sample is written")
    }

    @Test
    void aBadKeyLosesOnlyItsChunkOfTheWrite() {
        upstream["#" + ("X" * 400)] = T0.minus(Duration.ofMinutes(10))   // first, so it is in the first chunk of 500
        arrived << ("#" + ("X" * 400))
        (1..599).each { int i -> String k = "#K${i}".toString(); upstream[k] = T0.minus(Duration.ofMinutes(10)); arrived << k }
        Map out = SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        assertEquals("SPS_OK", out.status)
        assertEquals(600, SyncPulseSupport.presentKeysOf(SP).size())
        assertEquals(100, presentRows(), "the second chunk of 100 is stored; the first chunk (with the bad key) is not")
    }

    @Test
    void anotherTenantsRowsAreNeverLoaded() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        arrived << "#A1"
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
        // a row under the same pair id stamped for tenant B: tenant A's pair must not load it
        ec.entity.makeValue("darpan.pulse.SyncPulsePresentKey").setAll([syncPairId: SP, recordKey: "#FOREIGN", companyUserGroupId: B,
                sourceCreatedStamp: Timestamp.from(T0.minus(Duration.ofMinutes(5)))]).create()
        SyncPulseSupport.forgetPresentMemoryForTests()
        SyncPulseSupport.runPulse(ec, pairMap(SP), T0.plus(Duration.ofMinutes(10)))
        assertEquals(["#A1"] as Set, SyncPulseSupport.presentKeysOf(SP))
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

    /**
     * canStartNow said yes, but another start took the slot before the submit: RunAdmission queues the pulse. The scan writes
     * nothing; the pulse samples when it starts, at that moment, not at the scan's now.
     */
    @Test
    void aQueuedPulseSamplesWhenItStartsNotAtTheScansNow() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1)
        RunAdmission full = new RunAdmission(1, 1, 101, { -> 0 }, { Runnable r ->
            Thread t = new Thread(r, "pulse-test-" + pulseThreads.size()); pulseThreads << t; t.start()
        } as Executor, 10) {
            @Override boolean canStartNow(String tenantId) { return true }
        }
        full.submit("OTHER_TENANT", "blocker", { -> release.await(60, java.util.concurrent.TimeUnit.SECONDS) } as Runnable)
        AutomationExecutionSupport.setRunAdmission(full)

        Map out = runJob(T0, false)
        assertEquals([], out.errors)
        assertEquals(1, out.out.queuedCount as Integer)
        assertEquals(0, out.out.submittedCount as Integer)
        assertEquals([], samples(), "a queued pulse writes no sample at the scan")
        assertNull(pairMap(SP).lastPulseStamp)
        assertTrue(full.busy("pulse " + SP), "and the next tick sees it in flight")

        long before = System.currentTimeMillis()
        release.countDown()
        long deadline = before + 60000
        while (samples().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        new ArrayList<Thread>(pulseThreads).each { it.join(60000) }

        assertEquals(1, samples().size())
        Map s = latestSample()
        assertEquals("SPS_OK", s.statusEnumId)
        long stamp = (s.sampleStamp as Timestamp).time
        assertTrue(stamp >= before - 1000 && stamp <= System.currentTimeMillis() + 1000,
                "sampled when it started (${s.sampleStamp}), not at the scan's ${T0}")
        Instant started = (s.sampleStamp as Timestamp).toInstant()
        assertEquals(started.minus(Duration.ofMinutes(60 + 127)), upstreamCalls.last().from, "its window ends when it starts")
    }

    /** A sample write that throws (no SPS_* enums, FK SPSAMPLE_STATUS) leaves the claim stamped: no re-read every tick. */
    @Test
    void aSampleWriteThatThrowsStillClaimsThePulse() {
        upstream["#A1"] = T0.minus(Duration.ofMinutes(90))
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "in", ["SPS_OK", "SPS_FAILED", "SPS_SKIPPED"])
                .disableAuthz().deleteAll()
        try {
            SyncPulseSupport.runPulse(ec, pairMap(SP), T0)
            assertEquals([], samples(), "the sample write failed")
            assertEquals(Timestamp.from(T0), pairMap(SP).lastPulseStamp, "the claim survived the rollback")
            assertEquals(false, SyncPulseSupport.isDue(pairMap(SP), 60, T0.plus(Duration.ofMinutes(5))), "not due on the next tick")
            assertEquals(1, upstreamCalls.size())
        } finally {
            ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SyncPulseSeedData.xml")
        }
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
        assertEquals(third.minus(Duration.ofMinutes(60 + 127)), upstreamCalls.last().from, "allowance + 2 x cadence back")
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
        assertEquals(T0.minus(Duration.ofMinutes(30 + 127)), upstreamCalls.last().from)
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
    private Map runJob(Instant now, boolean join = true) {
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
        if (join) new ArrayList<Thread>(pulseThreads).each { it.join(60000) }
        return [out: out, errors: errors]
    }
}
