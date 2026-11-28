package darpan.facade.pulse

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationWindows
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
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328 plan 3: the sync pair facade end to end, and its cross-tenant containment. Tenant A (KREWE) owns a pair; tenant B
 * (GORJANA, the caller) must not list, read, change or delete it, nor name A's automation or chat space.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncPulseFacadeCrossTenantSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec
    private String pairA

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "sync-pulse-facade-cross-tenant-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SyncPulseSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(A, "Krewe")
        seedTenant(B, "Gorjana")
        String prev = AutomationWindows.WINDOW_PREVIOUS_DAY
        String week = AutomationWindows.WINDOW_PREVIOUS_WEEK
        String api = AutomationExecutionSupport.AUTOMATION_SOURCE_API
        StringBuilder xml = new StringBuilder('<entity-facade-xml type="test">' + """<darpan.rule.RuleSet ruleSetId="SPF_PAIR_RS" ruleSetName="Pair" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="SPF_SCOPE_A" ruleSetId="SPF_PAIR_RS" objectType="ORDER" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="SPF_SCOPE_A" fileSide="FILE_1" systemEnumId="OMS_GQL" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="orderId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="SPF_SCOPE_A" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="tranId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="SPF_SCOPE_A" fileSide="FILE_1" sequenceNum="1" fieldExpression="orderName" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="SPF_SCOPE_A" fileSide="FILE_2" sequenceNum="1" fieldExpression="otherRefNum" companyUserGroupId="${A}"/>""")
        [["SPF_A_DAILY", A, prev, "OMS_GQL", "NETSUITE_SUITEQL", "Y", "N"], ["SPF_A_WEEKLY", A, week, "OMS_GQL", "SHOPIFY", "Y", "N"],
         ["SPF_A_SHOP", A, prev, "SHOPIFY", "OMS_GQL", "Y", "N"], ["SPF_A_ARCH", A, prev, "OMS_GQL", "SHOPIFY", "Y", "Y"],
         ["SPF_A_OFF", A, prev, "OMS_GQL", "SHOPIFY", "N", "N"], ["SPF_B_DAILY", B, prev, "OMS_GQL", "SHOPIFY", "Y", "N"],
         ["SPF_A_NOKEY", A, prev, "OMS_GQL", "SHOPIFY", "Y", "N"]].each { a ->
            xml << """<darpan.reconciliation.ReconciliationAutomation automationId="${a[0]}" automationName="${a[0]}" companyUserGroupId="${a[1]}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="SPF_RS" compareScopeId="${a[0] == 'SPF_A_NOKEY' ? '' : 'SPF_SCOPE_A'}" isActive="${a[5]}" isArchived="${a[6]}"
                relativeWindowTypeEnumId="${a[2]}" windowTimeZone="UTC"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${a[0]}" fileSide="FILE_1" companyUserGroupId="${a[1]}"
                sourceTypeEnumId="${api}" systemEnumId="${a[3]}"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${a[0]}" fileSide="FILE_2" companyUserGroupId="${a[1]}"
                sourceTypeEnumId="${api}" systemEnumId="${a[4]}"/>"""
        }
        xml << """<darpan.reconciliation.TenantChatSpace chatSpaceId="SPF_CHAT_A" companyUserGroupId="${A}" spaceName="A space"/>
            <darpan.reconciliation.TenantChatSpace chatSpaceId="SPF_CHAT_B" companyUserGroupId="${B}" spaceName="B space"/></entity-facade-xml>"""
        ec.entity.makeDataLoader().dummyFks(true).xmlText(xml.toString()).load()
        actAs(A)
        Map saved = call("save#SyncPair", [automationId: "SPF_A_DAILY", upstreamSide: "FILE_1", allowanceMinutes: 120, pulseEveryMinutes: 60, chatSpaceId: "SPF_CHAT_A"])
        assertTrue(saved.ok as boolean, "setup: ${saved.errors}")
        pairA = saved.syncPairId
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void actAsB() {
        ec.message.clearErrors()
        actAs(B)
    }

    @Test
    void ownerListsAndReadsItsPair() {
        actAs(A)
        // dummyFks: the SPS_OK status enumeration is not seeded in the test database
        ec.entity.makeDataLoader().dummyFks(true).xmlText("""<entity-facade-xml type="test"><darpan.pulse.SyncPulseSample sampleId="SPF_S1"
            syncPairId="${pairA}" companyUserGroupId="${A}" sampleStamp="${new Timestamp(System.currentTimeMillis())}" waitingCount="4"
            pastAllowanceCount="1" statusEnumId="SPS_OK"/></entity-facade-xml>""").load()
        [["SPF_O2", 300], ["SPF_O1", 500]].each { o ->
            ec.entity.makeValue("darpan.pulse.SyncPulseOrder").setAll([pulseOrderId: o[0], syncPairId: pairA, companyUserGroupId: A, recordKey: o[0],
                    sourceCreatedStamp: Timestamp.from(Instant.now().minusSeconds((o[1] as int) * 60L))]).createOrUpdate()
        }
        Map listed = call("list#SyncPairs", [:])
        Map row = ((List) listed.syncPairs).find { it.syncPairId == pairA }
        assertNotNull(row)
        assertEquals("SPF_A_DAILY", row.automationId)
        assertEquals(4, row.waitingCount)
        assertEquals(2, row.pastAllowanceCount)
        assertEquals("Y", row.isActive)
        assertEquals("HotWax \u2192 NetSuite orders", row.label)

        Map got = call("get#SyncPair", [syncPairId: pairA])
        assertTrue(got.ok as boolean, "${got.errors}")
        assertEquals("HotWax \u2192 NetSuite orders", got.syncPair.label)
        assertEquals(4, got.syncPair.waitingCount)
        assertEquals(2, got.syncPair.pastAllowanceCount)
        assertEquals(120, got.syncPair.allowanceMinutes)
        assertEquals(120, got.syncPair.normalAllowanceMinutes)
        assertEquals(60, got.syncPair.normalPulseEveryMinutes)
        assertNull(got.syncPair.periodName)
        assertNull(got.syncPair.periodThruDate)
        assertEquals(["SPF_O1", "SPF_O2"], ((List) got.pastOrders).collect { it.recordKey }, "oldest first")
        assertTrue(((List) got.pastOrders).every { it.containsKey("reportedInRunId") && it.reportedInRunId == null })
        assertEquals(1, ((List) got.samplesToday).size())
    }

    @Test
    void theNewPairPageGetsOnlyPickers() {
        actAs(A)
        Map got = call("get#SyncPair", [:])
        assertTrue(got.ok as boolean)
        assertNull(got.syncPair)
        List autos = got.automations as List
        assertEquals(["SPF_A_DAILY", "SPF_A_NOKEY", "SPF_A_SHOP"], autos.collect { it.automationId }.sort(),
                "weekly, archived, paused and other tenants' checks are not offered")
        Map daily = autos.find { it.automationId == "SPF_A_DAILY" }
        assertEquals(["FILE_1", "FILE_2"], daily.upstreamOptions.collect { it.upstreamSide })
        assertTrue(daily.upstreamOptions.every { it.keySet() == ["upstreamSide", "label"] as Set })
        assertEquals(["SPF_CHAT_A"], ((List) got.chatSpaces).collect { it.chatSpaceId })
    }

    @Test
    void saveRefusalsUseTheExactSentences() {
        actAs(A)
        assertEquals(["Choose a daily check."], call("save#SyncPair", [automationId: "SPF_A_WEEKLY", upstreamSide: "FILE_1", allowanceMinutes: 60]).errors)
        assertEquals(["That run already has a sync pair."], call("save#SyncPair", [automationId: "SPF_A_DAILY", upstreamSide: "FILE_1", allowanceMinutes: 60]).errors)
        // DAR-BE-377: NetSuite's connector names no pulseCreatedField, so its side cannot be a pulse's upstream
        assertEquals(["A pulse needs HotWax or Shopify orders as the upstream side."],
                call("save#SyncPair", [syncPairId: pairA, automationId: "SPF_A_DAILY", upstreamSide: "FILE_2", allowanceMinutes: 60, pulseEveryMinutes: 120]).errors)
        assertEquals(["Choose a chat space of this tenant."],
                call("save#SyncPair", [syncPairId: pairA, automationId: "SPF_A_DAILY", upstreamSide: "FILE_1", allowanceMinutes: 60, chatSpaceId: "SPF_CHAT_B"]).errors)
        assertEquals([darpan.pulse.SyncPulseFacadeSupport.E_KEY],
                call("save#SyncPair", [automationId: "SPF_A_NOKEY", upstreamSide: "FILE_1", allowanceMinutes: 60, pulseEveryMinutes: 120]).errors)
        Map nokey = call("save#SyncPair", [automationId: "SPF_A_NOKEY", upstreamSide: "FILE_1", allowanceMinutes: 60])
        assertTrue(nokey.ok as boolean, "an allowance alone needs no key: ${nokey.errors}")
        assertTrue(call("delete#SyncPair", [syncPairId: nokey.syncPairId]).ok as boolean)
        Map shop = call("save#SyncPair", [automationId: "SPF_A_SHOP", upstreamSide: "FILE_1", allowanceMinutes: 60, pulseEveryMinutes: 120])
        assertTrue(shop.ok as boolean, "DAR-BE-377: Shopify orders upstream can pulse: ${shop.errors}")
        assertEquals(120, readPair((String) shop.syncPairId).pulseEveryMinutes as int)
        assertTrue(call("delete#SyncPair", [syncPairId: shop.syncPairId]).ok as boolean)
    }

    @Test
    void savingWithoutCadenceOrChatSpaceStoresNullNotTheOldValue() {
        actAs(A)
        String id = (String) call("save#SyncPair", [automationId: "SPF_A_SHOP", upstreamSide: "FILE_2", allowanceMinutes: 60,
                pulseEveryMinutes: 120, chatSpaceId: "SPF_CHAT_A"]).syncPairId
        try {
            assertEquals(120, readPair(id).pulseEveryMinutes as int)
            assertEquals("SPF_CHAT_A", readPair(id).chatSpaceId as String)
            // an archived check is not a valid pair target
            assertEquals(["Choose a daily check."], call("save#SyncPair", [automationId: "SPF_A_ARCH", upstreamSide: "FILE_1", allowanceMinutes: 60]).errors)
            Map r = call("save#SyncPair", [syncPairId: id, automationId: "SPF_A_SHOP", upstreamSide: "FILE_2", allowanceMinutes: 90])
            assertTrue(r.ok as boolean, "${r.errors}")
            def row = readPair(id)
            assertNull(row.pulseEveryMinutes)
            assertNull(row.chatSpaceId)
            assertEquals(90, row.allowanceMinutes as int)
            assertEquals("Y", row.isActive)
        } finally {
            call("delete#SyncPair", [syncPairId: id])
        }
    }

    @Test
    void detailUnderAnActivePeriodShowsTheEffectiveValues() {
        actAs(A)
        String today = java.time.LocalDate.now(TenantAccessSupport.tenantTimeZoneFor(ec, A)).toString()
        String period = (String) call("save#PeakPeriod", [periodName: "Sale", fromDate: today, thruDate: today], "facade.PeakPeriodFacadeServices").peakPeriodId
        assertNotNull(period)
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: period, syncPairId: pairA, companyUserGroupId: A,
                allowanceMinutes: 30, pulseEveryMinutes: 0]).createOrUpdate()
        try {
            Map sp = call("get#SyncPair", [syncPairId: pairA]).syncPair
            assertEquals("Sale", sp.periodName)
            assertEquals(today, sp.periodThruDate, "the overriding period's last day, tenant-local")
            assertEquals(30, sp.allowanceMinutes)
            assertEquals(0, sp.pulseEveryMinutes, "a period cadence of 0 is pulse off")
            assertEquals(120, sp.normalAllowanceMinutes)
            assertEquals(60, sp.normalPulseEveryMinutes)
        } finally {
            ec.entity.find("darpan.pulse.PeakPeriodSync").condition("peakPeriodId", period).disableAuthz().deleteAll()
            call("delete#PeakPeriod", [peakPeriodId: period], "facade.PeakPeriodFacadeServices")
        }
    }

    @Test
    void anotherTenantsPairIsNotListedOrReadable() {
        assertTrue(((List) call("list#SyncPairs", [:]).syncPairs).every { it.syncPairId != pairA })
        Map got = call("get#SyncPair", [syncPairId: pairA])
        assertFalse(got.ok as boolean)
        assertEquals(["Sync pair '${pairA}' was not found.".toString()], got.errors)
    }

    @Test
    void savingOrDeletingAnotherTenantsPairIsRefusedAndChangesNothing() {
        Map saved = call("save#SyncPair", [syncPairId: pairA, automationId: "SPF_B_DAILY", upstreamSide: "FILE_1", allowanceMinutes: 30])
        assertFalse(saved.ok as boolean)
        assertEquals(["Sync pair '${pairA}' was not found.".toString()], saved.errors)
        assertFalse(call("delete#SyncPair", [syncPairId: pairA]).ok as boolean)
        def row = readPair(pairA)
        assertNotNull(row, "another tenant's pair was deleted")
        assertEquals(A, row.companyUserGroupId as String)
        assertEquals("SPF_A_DAILY", row.automationId as String)
        assertEquals(120, row.allowanceMinutes as int)
    }

    @Test
    void aPairCannotNameAnotherTenantsAutomationOrChatSpace() {
        assertEquals(["Choose a daily check."], call("save#SyncPair", [automationId: "SPF_A_DAILY", upstreamSide: "FILE_1", allowanceMinutes: 60]).errors)
        assertEquals(["Choose a chat space of this tenant."],
                call("save#SyncPair", [automationId: "SPF_B_DAILY", upstreamSide: "FILE_1", allowanceMinutes: 60, chatSpaceId: "SPF_CHAT_A"]).errors)
        Map got = call("get#SyncPair", [:])
        assertEquals(["SPF_B_DAILY"], ((List) got.automations).collect { it.automationId })
    }

    @Test
    void deleteRemovesThePairWithItsSamplesAndOrders() {
        actAs(A)
        Map made = call("save#SyncPair", [automationId: "SPF_A_SHOP", upstreamSide: "FILE_2", allowanceMinutes: 60])
        String id = made.syncPairId
        assertNotNull(id)
        ec.entity.makeValue("darpan.pulse.SyncPulseOrder").setAll([pulseOrderId: "SPF_DEL_O", syncPairId: id, companyUserGroupId: A, recordKey: "k"]).createOrUpdate()
        assertTrue(call("delete#SyncPair", [syncPairId: id]).ok as boolean)
        assertNull(readPair(id))
        assertNull(ec.entity.find("darpan.pulse.SyncPulseOrder").condition("pulseOrderId", "SPF_DEL_O").disableAuthz().useCache(false).one())
    }

    private String seedRun(String id, String tenant, String automationId, String start, String windowFrom = null, String windowThru = null) {
        String window = windowFrom ? " windowStartDate=\"" + Timestamp.from(Instant.parse(windowFrom)) + "\" windowEndDate=\"" + Timestamp.from(Instant.parse(windowThru)) + "\"" : ""
        ec.entity.makeDataLoader().dummyFks(true).xmlText("""<entity-facade-xml type="test">
            <moqui.basic.Enumeration enumId="AUT_STAT_SUCCESS" enumTypeId="AutomationStatus"/>
            <moqui.basic.Enumeration enumId="AUT_STAT_PENDING" enumTypeId="AutomationStatus"/>
            <darpan.reconciliation.ReconciliationRunResult reconciliationRunResultId="${id}" companyUserGroupId="${tenant}" startedDate="${Timestamp.from(Instant.parse(start))}"/>
            <darpan.reconciliation.ReconciliationAutomationExecution automationExecutionId="${id}_X" automationId="${automationId}"
                companyUserGroupId="${tenant}" reconciliationRunResultId="${id}"${window}/></entity-facade-xml>""").load()
        return id
    }

    private void seedFlag(String id, String key, String first, String closed) {
        ec.entity.makeValue("darpan.pulse.SyncPulseOrder").setAll([pulseOrderId: id, syncPairId: pairA, companyUserGroupId: A, recordKey: key,
                firstPastStamp: Timestamp.from(Instant.parse(first)), closedStamp: closed ? Timestamp.from(Instant.parse(closed)) : null]).createOrUpdate()
    }

    private void dropRuns(List<String> runIds, List<String> orderIds) {
        runIds.each { id ->
            ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution").condition("automationExecutionId", id + "_X").disableAuthz().deleteAll()
            ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("reconciliationRunResultId", id).disableAuthz().deleteAll()
        }
        orderIds.each { ec.entity.find("darpan.pulse.SyncPulseOrder").condition("pulseOrderId", it).disableAuthz().deleteAll() }
    }

    @Test
    void runFlagsListOnlyOrdersFlaggedBeforeTheRunAndOpenAtItsStart() {
        actAs(A)
        seedRun("SPF_R1", A, "SPF_A_DAILY", "2026-11-21T08:00:00Z")
        seedFlag("SPF_F_OPEN", "K_OPEN", "2026-11-21T06:00:00Z", null)
        seedFlag("SPF_F_LATE", "K_LATE", "2026-11-21T09:00:00Z", null)
        seedFlag("SPF_F_GONE", "K_GONE", "2026-11-21T05:00:00Z", "2026-11-21T07:00:00Z")
        try {
            Map r = call("get#RunPulseFlags", [reconciliationRunResultId: "SPF_R1"])
            assertTrue(r.ok as boolean, "${r.errors}")
            assertEquals(pairA, r.syncPairId)
            assertEquals([K_OPEN: "2026-11-21T06:00:00Z"], r.flags)
        } finally { dropRuns(["SPF_R1"], ["SPF_F_OPEN", "SPF_F_LATE", "SPF_F_GONE"]) }
    }

    @Test
    void aRunWhoseCheckHasNoPairHasNoFlags() {
        actAs(A)
        seedRun("SPF_R2", A, "SPF_A_WEEKLY", "2026-11-21T08:00:00Z")
        try {
            Map r = call("get#RunPulseFlags", [reconciliationRunResultId: "SPF_R2"])
            assertTrue(r.ok as boolean, "${r.errors}")
            assertNull(r.syncPairId)
            assertEquals([:], r.flags)
        } finally { dropRuns(["SPF_R2"], []) }
    }

    @Test
    void anotherTenantsRunIsRefused() {
        actAs(A)
        seedRun("SPF_R3", A, "SPF_A_DAILY", "2026-11-21T08:00:00Z")
        try {
            actAs(B)
            Map r = call("get#RunPulseFlags", [reconciliationRunResultId: "SPF_R3"])
            assertFalse(r.ok as boolean)
            assertEquals(["Reconciliation run 'SPF_R3' was not found.".toString()], r.errors)
            assertNull(r.flags)
        } finally { dropRuns(["SPF_R3"], []) }
    }

    @Test
    void reportedInRunIsTheRunThatReadTheOrdersDayElseTheFirstAfterTheFlag() {
        actAs(A)
        seedRun("SPF_RB", A, "SPF_A_DAILY", "2026-11-21T05:00:00Z")
        seedRun("SPF_RC", A, "SPF_A_DAILY", "2026-11-21T08:00:00Z")
        seedRun("SPF_RD", A, "SPF_A_DAILY", "2026-11-21T10:00:00Z")
        ec.entity.makeValue("darpan.pulse.SyncPulseOrder").setAll([pulseOrderId: "SPF_RO1", syncPairId: pairA, companyUserGroupId: A, recordKey: "RO1",
                sourceCreatedStamp: Timestamp.from(Instant.parse("2026-11-21T03:00:00Z")), firstPastStamp: Timestamp.from(Instant.parse("2026-11-21T06:00:00Z"))]).createOrUpdate()
        ec.entity.makeValue("darpan.pulse.SyncPulseOrder").setAll([pulseOrderId: "SPF_RO2", syncPairId: pairA, companyUserGroupId: A, recordKey: "RO2",
                sourceCreatedStamp: Timestamp.from(Instant.parse("2026-11-21T04:00:00Z")), firstPastStamp: Timestamp.from(Instant.parse("2026-11-21T11:00:00Z"))]).createOrUpdate()
        try {
            Map got = call("get#SyncPair", [syncPairId: pairA])
            Map o1 = ((List) got.pastOrders).find { it.recordKey == "RO1" }
            Map o2 = ((List) got.pastOrders).find { it.recordKey == "RO2" }
            assertEquals("SPF_RC", o1.reportedInRunId, "no window recorded: the first run after the flag")
            assertEquals("SPF_RS", o1.reportedInSavedRunId)
            assertNull(o2.reportedInSavedRunId)
            assertNull(o2.reportedInRunId, "no run has started since it was flagged")
        } finally { dropRuns(["SPF_RB", "SPF_RC", "SPF_RD"], ["SPF_RO1", "SPF_RO2"]) }
    }

    @Test
    void reportedInRunIsTheRunWhoseWindowReadTheOrdersDay() {
        actAs(A)
        seedRun("SPF_RW1", A, "SPF_A_DAILY", "2026-11-21T08:00:00Z", "2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z")
        seedRun("SPF_RW2", A, "SPF_A_DAILY", "2026-11-22T08:00:00Z", "2026-11-21T00:00:00Z", "2026-11-22T00:00:00Z")
        ec.entity.makeValue("darpan.pulse.SyncPulseOrder").setAll([pulseOrderId: "SPF_RW_O", syncPairId: pairA, companyUserGroupId: A, recordKey: "RWO",
                sourceCreatedStamp: Timestamp.from(Instant.parse("2026-11-20T10:00:00Z")), firstPastStamp: Timestamp.from(Instant.parse("2026-11-20T14:00:00Z"))]).createOrUpdate()
        try {
            Map o = ((List) call("get#SyncPair", [syncPairId: pairA]).pastOrders).find { it.recordKey == "RWO" }
            assertEquals("SPF_RW1", o.reportedInRunId, "the run that read Nov 20, not the newest")
        } finally { dropRuns(["SPF_RW1", "SPF_RW2"], ["SPF_RW_O"]) }
    }

    @Test
    void anOrderFlaggedAfterItsDaysRunStartedLinksToThatDaysRun() {
        actAs(A)
        seedRun("SPF_RF1", A, "SPF_A_DAILY", "2026-11-21T08:00:00Z", "2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z")
        seedRun("SPF_RF2", A, "SPF_A_DAILY", "2026-11-22T08:00:00Z", "2026-11-21T00:00:00Z", "2026-11-22T00:00:00Z")
        ec.entity.makeValue("darpan.pulse.SyncPulseOrder").setAll([pulseOrderId: "SPF_RF_O", syncPairId: pairA, companyUserGroupId: A, recordKey: "RFO",
                sourceCreatedStamp: Timestamp.from(Instant.parse("2026-11-20T10:00:00Z")), firstPastStamp: Timestamp.from(Instant.parse("2026-11-21T09:00:00Z"))]).createOrUpdate()
        try {
            Map o = ((List) call("get#SyncPair", [syncPairId: pairA]).pastOrders).find { it.recordKey == "RFO" }
            assertEquals("SPF_RF1", o.reportedInRunId, "flagged after Nov 20's run started, still that run's day")
        } finally { dropRuns(["SPF_RF1", "SPF_RF2"], ["SPF_RF_O"]) }
    }

    @Test
    void noOpenOrdersMeansNoPastOrdersAndNoRunLookup() {
        actAs(A)
        seedRun("SPF_RN1", A, "SPF_A_DAILY", "2026-11-21T08:00:00Z", "2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z")
        def open = ec.entity.find("darpan.pulse.SyncPulseOrder").condition("syncPairId", pairA).condition("closedStamp", null).disableAuthz().useCache(false).list()
        Timestamp closed = new Timestamp(System.currentTimeMillis())
        open.each { it.set("closedStamp", closed); it.update() }
        try {
            assertEquals([], call("get#SyncPair", [syncPairId: pairA]).pastOrders)
        } finally {
            open.each { it.set("closedStamp", null); it.update() }
            dropRuns(["SPF_RN1"], [])
        }
    }

    private Map call(String name, Map params, String service = "facade.SyncPulseFacadeServices") {
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("${service}.${name}".toString()).parameters(params).disableAuthz().call()
    }

    private void actAs(String tenant) { ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant) }

    private def readPair(String id) {
        return ec.entity.find("darpan.pulse.SyncPair").condition("syncPairId", id).disableAuthz().useCache(false).one()
    }

    private void seedTenant(String tenantId, String label) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: tenantId, userId: TEST_USER_ID, fromDate: TEST_FROM_DATE]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: tenantId,
                userId: TEST_USER_ID, permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: TEST_FROM_DATE]).createOrUpdate()
    }
}
