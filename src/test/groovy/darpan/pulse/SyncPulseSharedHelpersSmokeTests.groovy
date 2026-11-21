package darpan.pulse

import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals

/** DAR-BE-328 plan 3, fix F1: the shared pair label, pair counts and pair cascade. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncPulseSharedHelpersSmokeTests {

    private static final String A = "KREWE"
    private static final String AUTO = "PULSE_H_AUTO"
    private static final String P1 = "SPH_ONE"
    private static final String P2 = "SPH_TWO"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "sync-pulse-helpers")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SyncPulseSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        String xml = """<entity-facade-xml type="seed">
            <darpan.reconciliation.ReconciliationAutomation automationId="${AUTO}" automationName="${AUTO}" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="PULSE_H_RS" isActive="Y" isArchived="N"
                relativeWindowTypeEnumId="${AutomationExecutionSupport.WINDOW_PREVIOUS_DAY}" windowTimeZone="UTC"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${AUTO}" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="OMS_GQL"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${AUTO}" fileSide="FILE_2" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="NETSUITE_SUITEQL"/>
        </entity-facade-xml>"""
        ec.entity.makeDataLoader().dummyFks(true).xmlText(xml).load()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ["darpan.pulse.SyncPulseSample", "darpan.pulse.SyncPulseOrder", "darpan.pulse.PeakPeriodSync", "darpan.peak.PeakPeriod", "darpan.pulse.SyncPair"]
                .each { ec.entity.find(it).disableAuthz().deleteAll() }
        [P1, P2].each { String id ->
            ec.entity.makeValue("darpan.pulse.SyncPair").setAll([syncPairId: id, companyUserGroupId: A, automationId: AUTO,
                    upstreamSide: "FILE_1", allowanceMinutes: 60, pulseEveryMinutes: 60, isActive: "Y"]).createOrUpdate()
        }
    }

    @Test
    void pairLabelNamesBothSystems() {
        assertEquals("HotWax → NetSuite orders", SyncPulseSupport.pairLabel(ec, [syncPairId: P1, companyUserGroupId: A,
                automationId: AUTO, upstreamSide: "FILE_1"]))
        assertEquals("NetSuite → HotWax orders", SyncPulseSupport.pairLabel(ec, [syncPairId: P1, companyUserGroupId: A,
                automationId: AUTO, upstreamSide: "FILE_2"]))
        assertEquals("Source → Target orders", SyncPulseSupport.pairLabel(ec, null))
    }

    @Test
    void pairCountsReadLatestOkSampleAndOpenOrders() {
        sample(P1, "2026-11-21T09:00:00Z", 9, "SPS_OK")
        sample(P1, "2026-11-21T10:00:00Z", 4, "SPS_OK")
        sample(P1, "2026-11-21T11:00:00Z", null, "SPS_FAILED")
        order(P1, "#1", false); order(P1, "#2", false); order(P1, "#3", true)
        order(P2, "#9", false)

        assertEquals([waitingCount: 4, pastAllowanceCount: 2], SyncPulseSupport.pairCounts(ec, A, P1))
        assertEquals([waitingCount: 0, pastAllowanceCount: 1], SyncPulseSupport.pairCounts(ec, A, P2))
        assertEquals([waitingCount: 0, pastAllowanceCount: 0], SyncPulseSupport.pairCounts(ec, "SOMEONE_ELSE", P1))
    }

    @Test
    void deletePairRemovesChildrenAndPairOnly() {
        [P1, P2].each { String id -> sample(id, "2026-11-21T10:00:00Z", 1, "SPS_OK"); order(id, "#" + id, false) }
        ec.entity.makeDataLoader().dummyFks(true).xmlText("""<entity-facade-xml type="seed">
            <darpan.peak.PeakPeriod peakPeriodId="PPH" companyUserGroupId="${A}" periodName="PPH" fromDate="2026-11-20" thruDate="2026-11-30"/>
            <darpan.pulse.PeakPeriodSync peakPeriodId="PPH" syncPairId="${P1}" companyUserGroupId="${A}"/></entity-facade-xml>""").load()

        assertEquals(1, SyncPulseCascade.deletePair(ec, A, P1))

        assertEquals(0, count("darpan.pulse.SyncPair", P1))
        assertEquals(0, count("darpan.pulse.SyncPulseSample", P1) + count("darpan.pulse.SyncPulseOrder", P1) + count("darpan.pulse.PeakPeriodSync", P1))
        assertEquals([1, 1, 1], ["darpan.pulse.SyncPair", "darpan.pulse.SyncPulseSample", "darpan.pulse.SyncPulseOrder"].collect { count(it, P2) })
        assertEquals(0, SyncPulseCascade.deletePair(ec, "SOMEONE_ELSE", P2))
        assertEquals(1, count("darpan.pulse.SyncPair", P2))
    }

    private void sample(String pair, String at, Integer waiting, String status) {
        def s = ec.entity.makeValue("darpan.pulse.SyncPulseSample")
        s.setAll([syncPairId: pair, companyUserGroupId: A, sampleStamp: Timestamp.from(Instant.parse(at)), waitingCount: waiting,
                  pastAllowanceCount: 0, statusEnumId: status])
        s.setSequencedIdPrimary(); s.create()
    }

    private void order(String pair, String key, boolean closed) {
        def o = ec.entity.makeValue("darpan.pulse.SyncPulseOrder")
        o.setAll([syncPairId: pair, companyUserGroupId: A, recordKey: key, orderName: key,
                  closedStamp: closed ? Timestamp.from(Instant.parse("2026-11-21T12:00:00Z")) : null])
        o.setSequencedIdPrimary(); o.create()
    }

    private int count(String entity, String pair) {
        return (int) ec.entity.find(entity).condition("syncPairId", pair).disableAuthz().useCache(false).count()
    }
}
